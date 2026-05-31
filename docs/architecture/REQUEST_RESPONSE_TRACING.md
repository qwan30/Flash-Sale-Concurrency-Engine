# Request → Response Code Tracing

Complete code tracing for all HTTP APIs showing exact flow from HTTP request to JSON response.

---

## 1. POST /orders - Create Order

### Request Arrives

```
HTTP Request:
POST /orders HTTP/1.1
Host: localhost:1122
Content-Type: application/json

{
  "ticketItemId": 4,
  "userId": 42,
  "quantity": 1,
  "strategy": "REDIS_LUA_WITH_COMPENSATION",
  "idempotencyKey": "user-42-run-1"
}
```

### Step 1: Spring HTTP Binding
```java
// Spring automatically parses JSON → CreateOrderRequest object
@PostMapping("/orders")
public ResponseEntity<ResultMessage<CreateOrderResponse>> createOrder(
    @RequestBody CreateOrderRequest request    // ← Spring binds JSON here
)
```

**Object created:**
```java
CreateOrderRequest {
  ticketItemId: 4
  userId: 42
  quantity: 1
  strategy: REDIS_LUA_WITH_COMPENSATION
  idempotencyKey: "user-42-run-1"
}
```

### Step 2: Call TicketOrderAppService
```java
// TicketOrderController.java line 32
CreateOrderResponse response = ticketOrderAppService.createOrder(request);
```

**Delegation chain:**
```
TicketOrderController.createOrder(request)
    ↓
TicketOrderAppService.createOrder(request)
    ↓
TicketOrderAppServiceImpl.createOrder(request)
    ├─ return orderCreationService.createOrder(request);
```

### Step 3: OrderCreationService Entry
```java
// OrderCreationService.java line 68
@Transactional(rollbackFor = Exception.class)
public CreateOrderResponse createOrder(CreateOrderRequest request) {
    // Step 3a: Validate
    String validationError = validateCreateOrderRequest(request);
    if (validationError != null) {
        // Request null, ticketItemId invalid, etc.
        CreateOrderResponse response = CreateOrderResponse.failure(
            request, 
            "INVALID_REQUEST", 
            validationError
        );
        recordOrderMetric(request == null ? null : request.getStrategy(), false);
        return response;
    }
    
    // Step 3b: Check idempotency
    String idempotencyKey = request.getUserId() + ":" + request.getIdempotencyKey();
    // idempotencyKey = "42:user-42-run-1"
    
    // Step 3c: Get or create idempotent response
    return idempotencyService.getOrCreate(
        idempotencyKey, 
        () -> doCreateOrder(request)
    );
}

// validateCreateOrderRequest() - line 143
private String validateCreateOrderRequest(CreateOrderRequest request) {
    if (request == null) {
        return "Request body is required";
    }
    if (request.getTicketItemId() == null || request.getTicketItemId() <= 0) {
        return "ticketItemId must be positive";
    }
    if (request.getUserId() == null || request.getUserId() <= 0) {
        return "userId must be positive";
    }
    if (request.getQuantity() == null || request.getQuantity() <= 0) {
        return "quantity must be positive";
    }
    if (request.getStrategy() == null) {
        return "strategy is required";
    }
    if (!StringUtils.hasText(request.getIdempotencyKey())) {
        return "idempotencyKey is required";
    }
    return null;  // ← All valid, return null
}
```

**Validation Result:** ✓ All valid, proceed

### Step 4: Idempotency Check
```java
// IdempotencyService
public <T> T getOrCreate(String key, Supplier<T> supplier) {
    // Check if "42:user-42-run-1" exists in cache
    T cached = cache.get(key);
    if (cached != null) {
        // Found cached response, return immediately (no new order created)
        return cached;
    }
    
    // Not in cache, execute supplier
    T result = supplier.get();  // ← Calls doCreateOrder(request)
    
    // Cache the result
    cache.put(key, result);
    return result;
}
```

**In our case:** First request, not in cache → Execute doCreateOrder()

### Step 5: Core Order Creation Logic
```java
// OrderCreationService.java line 83
private CreateOrderResponse doCreateOrder(CreateOrderRequest request) {
    StockDeductionResult deductionResult = null;
    try {
        // 5a: Build order number
        long nowMillis = System.currentTimeMillis();  // 1777280000000
        String orderNumber = buildOrderNumber(request.getUserId(), nowMillis);
        // buildOrderNumber: "OKX-SGN" + "-" + "42" + "-" + "1777280000000"
        // Result: "OKX-SGN-42-1777280000000"
        
        String yearMonth = OrderDateSupport.formatYearMonth(nowMillis);
        // Result: "202605"
        
        // 5b: Select strategy and deduct stock
        deductionResult = stockDeductionStrategyRegistry
            .get(request.getStrategy())  // Get REDIS_LUA_WITH_COMPENSATION strategy
            .decrease(request);           // Execute Lua script in Redis
        
        // Returns StockDeductionResult:
        // {
        //   success: true,
        //   code: "SUCCESS",
        //   message: "Stock deducted",
        //   redisNewStock: 999,
        //   compensateOnOrderFailure: true
        // }
        
        if (!deductionResult.isSuccess()) {
            // Stock unavailable or validation error
            CreateOrderResponse response = failureWithCurrentStock(
                request,
                deductionResult.getCode(),
                deductionResult.getMessage()
            );
            recordOrderMetric(request.getStrategy(), false);
            return response;  // ← Return FAILURE response
        }
        
        // 5c: Ensure monthly order table exists
        orderDeductionDomainService.ensureMonthlyOrderTable(yearMonth);
        // SQL: CREATE TABLE IF NOT EXISTS ticket_order_202605 (...)
        
        // 5d: Build order entity
        TickerOrder tickerOrder = buildOrder(request, orderNumber);
        // TickerOrder:
        // {
        //   userId: 42,
        //   orderNumber: "OKX-SGN-42-1777280000000",
        //   totalAmount: 5000 * 1 = 5000,
        //   terminalId: "OKX-SGN",
        //   orderNotes: "Order -> Pending"
        // }
        
        // 5e: INSERT order into database (transactional)
        orderDeductionDomainService.insertOrder(yearMonth, tickerOrder);
        // SQL: BEGIN TRANSACTION
        //      INSERT INTO ticket_order_202605 (
        //        user_id, order_number, quantity, total_amount, terminal_id, ...
        //      ) VALUES (42, "OKX-SGN-42-1777280000000", 1, 5000, "OKX-SGN", ...)
        //      COMMIT
        
        // 5f: Read current stock values
        long redisStock = stockOrderCacheService.getStockCache(request.getTicketItemId());
        // Redis GET "TICKET:4:STOCK" -> 999
        
        long dbStock = tickerOrderDomainService.getStockAvailable(request.getTicketItemId());
        // SQL: SELECT stock_available FROM ticket_item WHERE id = 4 → 999
        
        // 5g: Build SUCCESS response
        CreateOrderResponse response = CreateOrderResponse.success(
            request,
            orderNumber,      // "OKX-SGN-42-1777280000000"
            redisStock,       // 999
            dbStock           // 999
        );
        // response = CreateOrderResponse {
        //   success: true,
        //   code: "SUCCESS",
        //   message: "Order created",
        //   orderNumber: "OKX-SGN-42-1777280000000",
        //   strategy: "REDIS_LUA_WITH_COMPENSATION",
        //   ticketItemId: 4,
        //   userId: 42,
        //   quantity: 1,
        //   redisStockAfter: 999,
        //   dbStockAfter: 999
        // }
        
        recordOrderMetric(request.getStrategy(), true);
        return response;
        
    } catch (Exception e) {
        // Step 5h: Error handling & compensation
        markRollbackOnly();  // Rollback transaction
        
        if (deductionResult != null && deductionResult.isCompensateOnOrderFailure()) {
            try {
                // Compensation: Restore Redis stock
                stockOrderCacheService.restoreStockCache(
                    request.getTicketItemId(),
                    request.getQuantity()
                );
                // Redis INCR "TICKET:4:STOCK" by 1 -> stock back to 1000
            } catch (Exception compensationEx) {
                // CRITICAL: Double fault
                // Redis is decremented but no order in DB
                // OrderReconciliationService will repair this
                log.error("COMPENSATION_FAILURE...");
            }
        }
        
        CreateOrderResponse response = failureWithCurrentStock(
            request,
            "ORDER_CREATE_FAILED",
            "Order creation failed"
        );
        recordOrderMetric(request.getStrategy(), false);
        return response;
    }
}
```

**Result:** CreateOrderResponse with success=true, orderNumber, stock values

### Step 6: Back to Controller
```java
// TicketOrderController.java line 32-34
CreateOrderResponse response = ticketOrderAppService.createOrder(request);
// response = {
//   success: true,
//   code: "SUCCESS",
//   message: "Order created",
//   orderNumber: "OKX-SGN-42-1777280000000",
//   ...
// }

return ResponseEntity
    .status(statusFor(response))      // Decide HTTP status
    .body(message(response));         // Wrap in ResultMessage
```

### Step 7: Determine HTTP Status Code
```java
// TicketOrderController.java line 101
private HttpStatus statusFor(CreateOrderResponse response) {
    if ("INVALID_REQUEST".equals(response.getCode())) {
        return HttpStatus.BAD_REQUEST;  // 400
    }
    return HttpStatus.OK;  // 200
}

// In our case:
// response.getCode() = "SUCCESS"
// → return HttpStatus.OK (200)
```

### Step 8: Wrap Response
```java
// TicketOrderController.java line 105
private ResultMessage<CreateOrderResponse> message(CreateOrderResponse response) {
    ResultMessage<CreateOrderResponse> message = new ResultMessage<>();
    
    message.setSuccess(response.isSuccess());
    // → true
    
    message.setCode(response.isSuccess() ? 200 : 
                    "INVALID_REQUEST".equals(response.getCode()) ? 400 : 409);
    // → 200 (because response.isSuccess() == true)
    
    message.setMessage(response.getMessage());
    // → "Order created"
    
    message.setResult(response);
    // → Full CreateOrderResponse object
    
    return message;
}

// Result: ResultMessage wrapper
// {
//   success: true,
//   code: 200,
//   message: "Order created",
//   timestamp: 1777280000000,
//   result: CreateOrderResponse { ... }
// }
```

### Step 9: Spring Serializes to JSON
```
Spring @RestController automatically converts ResultMessage to JSON
using Jackson ObjectMapper
```

### HTTP Response
```
HTTP/1.1 200 OK
Content-Type: application/json

{
  "success": true,
  "code": 200,
  "message": "Order created",
  "timestamp": 1777280000000,
  "result": {
    "success": true,
    "code": "SUCCESS",
    "message": "Order created",
    "orderNumber": "OKX-SGN-42-1777280000000",
    "strategy": "REDIS_LUA_WITH_COMPENSATION",
    "ticketItemId": 4,
    "userId": 42,
    "quantity": 1,
    "redisStockAfter": 999,
    "dbStockAfter": 999
  }
}
```

---

## 2. GET /orders - List Orders

### Request Arrives
```
HTTP Request:
GET /orders?userId=42&yearMonth=202605 HTTP/1.1
```

### Code Trace
```java
// TicketOrderController.java line 37
@GetMapping("/orders")
public ResultMessage<List<TicketOrderDTO>> listOrders(
    @RequestParam("userId") Long userId,      // ← 42
    @RequestParam("yearMonth") String yearMonth  // ← "202605"
) {
    log.info("Controller:->listOrders | {}, {}", userId, yearMonth);
    
    List<TicketOrderDTO> orders = ticketOrderAppService.findAllByUser(
        yearMonth,  // "202605"
        userId      // 42
    );
    // Call: OrderQueryService.findAllByUser()
    
    return ResultUtil.data(orders);
    // Wrap in ResultMessage:
    // {
    //   success: true,
    //   code: 200,
    //   result: [ { orderNumber: "...", userId: 42, ... }, ... ]
    // }
}

// OrderQueryService
public List<TicketOrderDTO> findAllByUser(String yearMonth, Long userId) {
    String tableName = "ticket_order_" + yearMonth;
    // SQL: SELECT * FROM ticket_order_202605 WHERE user_id = 42
    
    return repository.queryByUserInMonthly(tableName, userId);
    // Returns:
    // [
    //   TicketOrderDTO {
    //     orderNumber: "OKX-SGN-42-1777280000000",
    //     userId: 42,
    //     quantity: 1,
    //     totalAmount: 5000,
    //     ...
    //   }
    // ]
}
```

### HTTP Response
```json
{
  "success": true,
  "code": 200,
  "message": "",
  "timestamp": 1777280000001,
  "result": [
    {
      "orderNumber": "OKX-SGN-42-1777280000000",
      "userId": 42,
      "quantity": 1,
      "totalAmount": 5000,
      "terminalId": "OKX-SGN"
    }
  ]
}
```

---

## 3. GET /orders/{orderNumber} - Get Order Detail

### Request Arrives
```
HTTP Request:
GET /orders/OKX-SGN-42-1777280000000 HTTP/1.1
```

### Code Trace
```java
// TicketOrderController.java line 44
@GetMapping("/orders/{orderNumber}")
public ResultMessage<TicketOrderDTO> getOrder(
    @PathVariable("orderNumber") String orderNumber  // ← "OKX-SGN-42-1777280000000"
) {
    log.info("Controller:->getOrder | {}", orderNumber);
    
    TicketOrderDTO order = ticketOrderAppService.findByOrderNumber(
        null,          // yearMonth (can be null, will search current)
        orderNumber    // "OKX-SGN-42-1777280000000"
    );
    
    return ResultUtil.data(order);
}

// OrderQueryService.findByOrderNumber()
public TicketOrderDTO findByOrderNumber(String yearMonth, String orderNumber) {
    // If yearMonth not provided, extract from orderNumber or use current
    if (!StringUtils.hasText(yearMonth)) {
        // Extract month from orderNumber timestamp
        long timestamp = extractTimestamp(orderNumber);  // 1777280000000
        yearMonth = OrderDateSupport.formatYearMonth(timestamp);  // "202605"
    }
    
    String tableName = "ticket_order_" + yearMonth;
    // SQL: SELECT * FROM ticket_order_202605 WHERE order_number = "OKX-SGN-42-1777280000000"
    
    return repository.findByOrderNumber(tableName, orderNumber);
    // Returns: TicketOrderDTO { orderNumber: "...", userId: 42, ... }
}
```

### HTTP Response
```json
{
  "success": true,
  "code": 200,
  "result": {
    "orderNumber": "OKX-SGN-42-1777280000000",
    "userId": 42,
    "quantity": 1,
    "totalAmount": 5000,
    "terminalId": "OKX-SGN"
  }
}
```

---

## 4. POST /admin/benchmarks/reset - Reset Data

### Request Arrives
```
HTTP Request:
POST /admin/benchmarks/reset HTTP/1.1
Content-Type: application/json

{
  "ticketItemId": 4,
  "stock": 1000,
  "yearMonth": "202605"
}
```

### Code Trace
```java
// AdminBenchmarkController.java
@PostMapping("/admin/benchmarks/reset")
public ResultMessage<BenchmarkResetResponse> resetBenchmark(
    @RequestBody BenchmarkResetRequest request
) {
    BenchmarkResetResponse response = ticketOrderAppService.resetBenchmark(request);
    return ResultUtil.data(response);
}

// TicketOrderAppServiceImpl.java
@Override
public BenchmarkResetResponse resetBenchmark(BenchmarkResetRequest request) {
    return benchmarkFixtureService.resetBenchmark(request);
}

// BenchmarkFixtureService.java
public BenchmarkResetResponse resetBenchmark(BenchmarkResetRequest request) {
    // Step 1: Update DB stock
    Long ticketItemId = request.getTicketItemId();  // 4
    int stock = request.getStock();                  // 1000
    String yearMonth = normalizeYearMonth(request.getYearMonth());  // "202605"
    
    // SQL: UPDATE ticket_item SET stock_available = 1000, stock_initial = 1000 WHERE id = 4
    tickerOrderDomainService.updateStock(ticketItemId, stock);
    
    // Step 2: Clear monthly orders
    String tableName = "ticket_order_" + yearMonth;
    // SQL: DELETE FROM ticket_order_202605
    orderDeductionDomainService.clearMonthlyOrders(tableName);
    
    // Step 3: Update Redis cache
    // Redis: SET "TICKET:4:STOCK" 1000
    stockOrderCacheService.resetStockCache(ticketItemId, stock);
    
    // Step 4: Build response
    BenchmarkResetResponse response = new BenchmarkResetResponse();
    response.setSuccess(true);
    response.setTicketItemId(ticketItemId);
    response.setStock(stock);
    response.setYearMonth(yearMonth);
    response.setRedisStockAfter(stockOrderCacheService.getStockCache(ticketItemId));  // 1000
    response.setDbStockAfter(tickerOrderDomainService.getStockAvailable(ticketItemId));  // 1000
    response.setDbOrderCount(0);
    
    return response;
}
```

### HTTP Response
```json
{
  "success": true,
  "code": 200,
  "result": {
    "success": true,
    "ticketItemId": 4,
    "stock": 1000,
    "yearMonth": "202605",
    "redisStockAfter": 1000,
    "dbStockAfter": 1000,
    "dbOrderCount": 0
  }
}
```

---

## 5. POST /admin/tickets/{ticketItemId}/stock/warmup - Warm Cache

### Request Arrives
```
HTTP Request:
POST /admin/tickets/4/stock/warmup HTTP/1.1
```

### Code Trace
```java
// AdminBenchmarkController.java
@PostMapping("/admin/tickets/{ticketItemId}/stock/warmup")
public ResultMessage<CreateOrderResponse> warmupStock(
    @PathVariable Long ticketItemId  // ← 4
) {
    CreateOrderResponse response = ticketOrderAppService.warmupStock(ticketItemId);
    return ResultUtil.data(response);
}

// TicketOrderAppServiceImpl.java
@Override
public CreateOrderResponse warmupStock(Long ticketItemId) {
    return benchmarkFixtureService.warmupStock(ticketItemId);
}

// BenchmarkFixtureService.java
public CreateOrderResponse warmupStock(Long ticketItemId) {
    // Step 1: Read DB stock
    // SQL: SELECT stock_available FROM ticket_item WHERE id = 4
    long dbStock = tickerOrderDomainService.getStockAvailable(ticketItemId);  // 1000
    
    // Step 2: Write to Redis
    // Redis: SET "TICKET:4:STOCK" 1000
    stockOrderCacheService.resetStockCache(ticketItemId, (int) dbStock);
    
    // Step 3: Build response
    CreateOrderResponse response = new CreateOrderResponse();
    response.setSuccess(true);
    response.setMessage("Stock warmed up");
    response.setRedisStockAfter(dbStock);
    response.setDbStockAfter(dbStock);
    
    return response;
}
```

### HTTP Response
```json
{
  "success": true,
  "code": 200,
  "result": {
    "success": true,
    "message": "Stock warmed up",
    "redisStockAfter": 1000,
    "dbStockAfter": 1000
  }
}
```

---

## 6. GET /admin/benchmarks/consistency - Check Consistency

### Request Arrives
```
HTTP Request:
GET /admin/benchmarks/consistency?ticketItemId=4&yearMonth=202605 HTTP/1.1
```

### Code Trace
```java
// AdminBenchmarkController.java
@GetMapping("/admin/benchmarks/consistency")
public ResultMessage<ConsistencySnapshot> getConsistency(
    @RequestParam Long ticketItemId,      // ← 4
    @RequestParam String yearMonth         // ← "202605"
) {
    ConsistencySnapshot snapshot = ticketOrderAppService.getConsistency(
        ticketItemId,
        yearMonth
    );
    return ResultUtil.data(snapshot);
}

// TicketOrderAppServiceImpl.java
@Override
public ConsistencySnapshot getConsistency(Long ticketItemId, String yearMonth) {
    return consistencyCheckService.getConsistency(ticketItemId, yearMonth);
}

// ConsistencyCheckService.java
public ConsistencySnapshot getConsistency(Long ticketItemId, String yearMonth) {
    // Step 1: Read Redis stock
    // Redis: GET "TICKET:4:STOCK"
    long redisStock = stockOrderCacheService.getStockCache(ticketItemId);  // 999
    
    // Step 2: Read DB stock
    // SQL: SELECT stock_available FROM ticket_item WHERE id = 4
    long dbStock = tickerOrderDomainService.getStockAvailable(ticketItemId);  // 999
    
    // Step 3: Count orders
    String tableName = "ticket_order_" + yearMonth;
    // SQL: SELECT COUNT(*) FROM ticket_order_202605
    long orderCount = orderDeductionDomainService.countOrders(tableName, ticketItemId);  // 1
    
    // Step 4: Calculate metrics
    long initialStock = dbStock + orderCount;  // 999 + 1 = 1000
    long expectedRedisStock = dbStock;         // 999 (source of truth)
    long driftAmount = redisStock - expectedRedisStock;  // 999 - 999 = 0
    long oversoldCount = dbStock < 0 ? Math.abs(dbStock) : 0;  // 0
    int inconsistency = (redisStock == expectedRedisStock) ? 0 : 1;  // 0
    
    // Step 5: Build response
    ConsistencySnapshot snapshot = new ConsistencySnapshot();
    snapshot.setRedisStockAfter(redisStock);
    snapshot.setDbStockAfter(dbStock);
    snapshot.setDbOrderCount(orderCount);
    snapshot.setInitialStock(initialStock);
    snapshot.setExpectedRedisStock(expectedRedisStock);
    snapshot.setDriftAmount(driftAmount);
    snapshot.setOversoldCount(oversoldCount);
    snapshot.setRedisDbInconsistencyCount(inconsistency);
    
    return snapshot;
}
```

### HTTP Response
```json
{
  "success": true,
  "code": 200,
  "result": {
    "redisStockAfter": 999,
    "dbStockAfter": 999,
    "dbOrderCount": 1,
    "initialStock": 1000,
    "expectedRedisStock": 999,
    "driftAmount": 0,
    "oversoldCount": 0,
    "redisDbInconsistencyCount": 0
  }
}
```

---

## 7. POST /admin/benchmarks/reconcile - Reconcile Stock

### Request Arrives
```
HTTP Request:
POST /admin/benchmarks/reconcile?ticketItemId=4&yearMonth=202605 HTTP/1.1
```

### Code Trace (Scenario: Redis=500, DB=999 - Drift Detected)
```java
// AdminBenchmarkController.java
@PostMapping("/admin/benchmarks/reconcile")
public ResultMessage<ConsistencySnapshot> reconcileStock(
    @RequestParam Long ticketItemId,      // ← 4
    @RequestParam String yearMonth         // ← "202605"
) {
    // Step 1: Get current consistency state
    ConsistencySnapshot beforeReconcile = consistencyCheckService.getConsistency(
        ticketItemId,
        yearMonth
    );
    // beforeReconcile: { redisStockAfter: 500, dbStockAfter: 999, driftAmount: -499 }
    
    // Step 2: Detect drift
    if (beforeReconcile.getDriftAmount() != 0) {
        // Drift exists! Need to reconcile
        long dbStockTruth = beforeReconcile.getDbStockAfter();  // 999
        
        // Redis SET to match DB (source of truth)
        // Redis: SET "TICKET:4:STOCK" 999
        stockOrderCacheService.resetStockCache(ticketItemId, (int) dbStockTruth);
    }
    
    // Step 3: Get consistency after reconcile
    ConsistencySnapshot afterReconcile = consistencyCheckService.getConsistency(
        ticketItemId,
        yearMonth
    );
    // afterReconcile: { redisStockAfter: 999, dbStockAfter: 999, driftAmount: 0 }
    
    // Step 4: Wrap response
    return ResultUtil.data(afterReconcile);
}
```

### HTTP Response (Before Reconcile)
```json
{
  "success": true,
  "code": 200,
  "result": {
    "redisStockAfter": 500,
    "dbStockAfter": 999,
    "driftAmount": -499,
    "redisDbInconsistencyCount": 1
  }
}
```

### After Reconcile Applied
```json
{
  "success": true,
  "code": 200,
  "result": {
    "redisStockAfter": 999,
    "dbStockAfter": 999,
    "driftAmount": 0,
    "redisDbInconsistencyCount": 0
  }
}
```

---

## 8. GET /tickets/{ticketItemId} - Get Ticket Info

### Request Arrives
```
HTTP Request:
GET /tickets/4 HTTP/1.1
```

### Code Trace
```java
// TicketDetailController.java
@GetMapping("/tickets/{ticketItemId}")
public ResultMessage<TicketDetailDTO> getTicketDetail(
    @PathVariable Long ticketItemId  // ← 4
) {
    // SQL: SELECT * FROM ticket_item WHERE id = 4
    TicketDetailDTO ticket = ticketService.getTicketDetail(ticketItemId);
    
    return ResultUtil.data(ticket);
}

// TicketService
public TicketDetailDTO getTicketDetail(Long ticketItemId) {
    // Query ticket from database
    Ticket ticketEntity = ticketRepository.findById(ticketItemId);
    // ticketEntity: {
    //   id: 4,
    //   name: "Flash Sale Ticket",
    //   stockInitial: 10000,
    //   stockAvailable: 999,
    //   priceOriginal: 5000,
    //   priceFlash: 5000,
    //   status: "ACTIVE",
    //   ...
    // }
    
    // Convert to DTO
    return new TicketDetailDTO(ticketEntity);
}
```

### HTTP Response
```json
{
  "success": true,
  "code": 200,
  "result": {
    "id": 4,
    "name": "Flash Sale Ticket",
    "stockInitial": 10000,
    "stockAvailable": 999,
    "priceOriginal": 5000,
    "priceFlash": 5000,
    "status": "ACTIVE",
    "createdAt": "2026-05-20T10:00:00Z"
  }
}
```

---

## Error Case Example: Stock Unavailable

### Request Arrives
```
HTTP Request:
POST /orders HTTP/1.1
{
  "ticketItemId": 4,
  "userId": 43,
  "quantity": 1,
  "strategy": "REDIS_LUA_WITH_COMPENSATION",
  "idempotencyKey": "user-43-run-1"
}

Scenario: Stock is already 0 (exhausted)
```

### Code Trace
```java
// OrderCreationService.java doCreateOrder()
private CreateOrderResponse doCreateOrder(CreateOrderRequest request) {
    try {
        long nowMillis = System.currentTimeMillis();
        String orderNumber = buildOrderNumber(request.getUserId(), nowMillis);
        String yearMonth = OrderDateSupport.formatYearMonth(nowMillis);
        
        // Execute strategy: Deduct stock
        deductionResult = stockDeductionStrategyRegistry
            .get(request.getStrategy())
            .decrease(request);
        
        // Lua script in Redis:
        // if redis_stock >= quantity:
        //   redis_stock -= quantity
        //   return SUCCESS
        // else:
        //   return FAIL
        
        // Redis stock = 0, quantity = 1
        // 0 >= 1? NO!
        // → return FAIL
        
        // deductionResult:
        // {
        //   success: false,
        //   code: "REDIS_STOCK_UNAVAILABLE",
        //   message: "Redis stock is missing or not enough",
        //   compensateOnOrderFailure: true
        // }
        
        if (!deductionResult.isSuccess()) {
            // Stock deduction failed, no order inserted
            CreateOrderResponse response = failureWithCurrentStock(
                request,
                deductionResult.getCode(),
                deductionResult.getMessage()
            );
            // failureWithCurrentStock():
            response.setRedisStockAfter(
                stockOrderCacheService.getStockCache(4)  // 0
            );
            response.setDbStockAfter(
                tickerOrderDomainService.getStockAvailable(4)  // 0
            );
            
            recordOrderMetric(request.getStrategy(), false);
            return response;
            // response = CreateOrderResponse {
            //   success: false,
            //   code: "REDIS_STOCK_UNAVAILABLE",
            //   message: "Redis stock is missing or not enough",
            //   redisStockAfter: 0,
            //   dbStockAfter: 0
            // }
        }
    } catch (Exception e) {
        // ... error handling
    }
}

// Back in Controller
// statusFor(response):
// response.getCode() = "REDIS_STOCK_UNAVAILABLE"
// NOT equal to "INVALID_REQUEST"
// → return HttpStatus.OK (200)

// message(response):
// response.isSuccess() = false
// → code = 409 (not 400, not 200)

ResultMessage wrapper:
// {
//   success: false,
//   code: 409,
//   message: "Redis stock is missing or not enough",
//   result: CreateOrderResponse { ... }
// }
```

### HTTP Response
```json
{
  "success": false,
  "code": 409,
  "message": "Redis stock is missing or not enough",
  "timestamp": 1777280000002,
  "result": {
    "success": false,
    "code": "REDIS_STOCK_UNAVAILABLE",
    "message": "Redis stock is missing or not enough",
    "ticketItemId": 4,
    "userId": 43,
    "quantity": 1,
    "redisStockAfter": 0,
    "dbStockAfter": 0
  }
}
```

---

## Error Case Example: Validation Error

### Request Arrives (Invalid)
```
HTTP Request:
POST /orders HTTP/1.1
{
  "ticketItemId": 4,
  "userId": -1,          // ← INVALID (negative)
  "quantity": 1,
  "strategy": "REDIS_LUA_WITH_COMPENSATION",
  "idempotencyKey": "user-test"
}
```

### Code Trace
```java
// OrderCreationService.createOrder()
String validationError = validateCreateOrderRequest(request);
// validateCreateOrderRequest():
if (request.getUserId() == null || request.getUserId() <= 0) {
    return "userId must be positive";  // ← Validation fails!
}

if (validationError != null) {
    CreateOrderResponse response = CreateOrderResponse.failure(
        request,
        "INVALID_REQUEST",
        "userId must be positive"
    );
    recordOrderMetric(request == null ? null : request.getStrategy(), false);
    return response;
    // response = CreateOrderResponse {
    //   success: false,
    //   code: "INVALID_REQUEST",
    //   message: "userId must be positive"
    // }
}

// Back in Controller
// statusFor(response):
if ("INVALID_REQUEST".equals(response.getCode())) {
    return HttpStatus.BAD_REQUEST;  // ← 400!
}

// message(response):
// response.isSuccess() = false
// "INVALID_REQUEST".equals(...) = true
// → code = 400

ResultMessage wrapper:
// {
//   success: false,
//   code: 400,
//   message: "userId must be positive",
//   result: CreateOrderResponse { ... }
// }
```

### HTTP Response
```json
{
  "success": false,
  "code": 400,
  "message": "userId must be positive",
  "timestamp": 1777280000003,
  "result": {
    "success": false,
    "code": "INVALID_REQUEST",
    "message": "userId must be positive"
  }
}
```

---

## HTTP Status Code Decision Logic

```
                     ┌─────────────────────┐
                     │ CreateOrderResponse │
                     │     received        │
                     └──────────┬──────────┘
                                │
                    ┌───────────┴───────────┐
                    │                       │
            ┌───────v────────┐     ┌──────v───────┐
            │ code ==        │     │   code ==    │
            │ "INVALID_      │ YES │  "SUCCESS"   │ YES
            │  REQUEST"?     │────→│  or other    │────→ HTTP 200
            └────┬─────────┬─┘     │  business?   │
                 │ NO      │       └──────────────┘
                 │         │
                 │    ┌────v──────────┐
                 │    │ All others:   │
                 │    │ STOCK_, ORDER_│
                 │    │ _FAILED, etc  │
                 │    └───┬──────┬────┘
                 │        │      │
                 v        v      v
              HTTP 400  HTTP 200 with code 409
              (Bad      (But success=false in
              Request)  body!)
```

---

## Key Patterns Summary

| Pattern | HTTP Status | Code in Body | Success Flag | When |
|---------|-------------|--------------|--------------|------|
| **Validation Error** | 400 | 400 | false | Request body invalid (negative userId, null strategy) |
| **Business Error** | 200 | 409 | false | Valid request but business failed (stock unavailable) |
| **Success** | 200 | 200 | true | Order created, data found, operation succeeded |

---

## Layer Boundaries

```
CONTROLLER        → HTTP binding, status decision, response wrapping
    ↓
APPLICATION       → Orchestration, validation, idempotency, coordination
    ↓
DOMAIN            → Business rules, strategy selection
    ↓
INFRASTRUCTURE    → Database queries, Redis operations, data access
    ↓
DATA              → MySQL tables, Redis keys, actual values
```

Each layer decides specific aspects:
- **Controller**: HTTP status + response wrapper
- **Application**: Idempotency + validation + coordination
- **Domain**: Business logic + compensation
- **Infrastructure**: Persistence + reads

---

