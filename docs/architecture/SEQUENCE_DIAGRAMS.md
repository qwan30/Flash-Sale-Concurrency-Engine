# Sequence Diagrams - All APIs

Complete sequence diagrams for all HTTP APIs in the Flash-Sale-Concurrency-Engine project.

---

## 1. POST /orders - Create Order (Main Flow)

```
CLIENT              CONTROLLER          APPLICATION         DOMAIN              INFRASTRUCTURE
  │                    │                    │                  │                      │
  ├─ HTTP POST ──────→ │                    │                  │                      │
  │ /orders            │                    │                  │                      │
  │ Content-Type:      │                    │                  │                      │
  │ application/json   │                    │                  │                      │
  │                    │                    │                  │                      │
  │  RequestBody:      │                    │                  │                      │
  │  {                 │                    │                  │                      │
  │    ticketItemId:4, │                    │                  │                      │
  │    userId: 42,     │                    │                  │                      │
  │    quantity: 1,    │                    │                  │                      │
  │    strategy:       │                    │                  │                      │
  │      REDIS_LUA_... │                    │                  │                      │
  │    idempotencyKey: │                    │                  │                      │
  │      "user-42-r1"  │                    │                  │                      │
  │  }                 │                    │                  │                      │
  │                    │                    │                  │                      │
  │  @PostMapping("/orders")                 │                  │                      │
  │  ────────────────→ │ ← Line 29          │                  │                      │
  │                    │ @RequestBody       │                  │                      │
  │                    │ binding            │                  │                      │
  │                    │ CreateOrderRequest │                  │                      │
  │                    │ object created     │                  │                      │
  │                    │                    │                  │                      │
  │                    │ public ResponseEntity<ResultMessage<CreateOrderResponse>> createOrder(
  │                    │   @RequestBody CreateOrderRequest request)
  │                    │ Line 30: createOrder(request)         │                      │
  │                    │ ──────────────────→│                  │                      │
  │                    │                    │                  │                      │
  │                    │                    │ validateCreateOrderRequest()            │
  │                    │                    │ ├─ Validate ticketItemId > 0           │
  │                    │                    │ ├─ Validate userId > 0                 │
  │                    │                    │ ├─ Validate quantity > 0               │
  │                    │                    │ ├─ Validate strategy not null          │
  │                    │                    │ └─ Validate idempotencyKey not empty   │
  │                    │                    │ ✓ All valid                            │
  │                    │                    │                  │                      │
  │                    │                    │ idempotencyService.getOrCreate()       │
  │                    │                    │ ├─ Build idempotency key: "42:user-..." │
  │                    │                    │ ├─ Check ConcurrentHashMap cache      │
  │                    │                    │ └─ Not in cache → proceed               │
  │                    │                    │                  │                      │
  │                    │                    │ doCreateOrder()  │                      │
  │                    │                    │ ─────────────────────────────────────→ │
  │                    │                    │                  │ buildOrderNumber()  │
  │                    │                    │                  │ "OKX-SGN-42-1777..." │
  │                    │                    │                  │                      │
  │                    │                    │                  │ formatYearMonth()   │
  │                    │                    │                  │ "202605"             │
  │                    │                    │                  │                      │
  │                    │                    │ stockDeductionStrategyRegistry         │
  │                    │                    │ ─────────────────────────────────────→ │
  │                    │                    │ .get(REDIS_LUA_WITH_COMPENSATION)      │
  │                    │                    │                  │                      │
  │                    │                    │ .decrease(request)                     │
  │                    │                    │ ────────────────────────────────────→ │
  │                    │                    │                  │    Redis.EVALSHA()  │
  │                    │                    │                  │    Lua script:      │
  │                    │                    │                  │    if stock >= qty: │
  │                    │                    │                  │      stock -= qty   │
  │                    │                    │                  │      return OK      │
  │                    │                    │                  │                      │
  │                    │                    │ StockDeductionResult                   │
  │                    │                    │ ←──────────────────────────────────── │
  │                    │                    │ {                                      │
  │                    │                    │   success: true,                       │
  │                    │                    │   code: "SUCCESS",                     │
  │                    │                    │   redisNewStock: 999,                  │
  │                    │                    │   compensateOnOrderFailure: true       │
  │                    │                    │ }                                      │
  │                    │                    │                  │                      │
  │                    │                    │ ensureMonthlyOrderTable("202605")      │
  │                    │                    │ ─────────────────────────────────────→ │
  │                    │                    │                  │   CREATE TABLE IF NOT│
  │                    │                    │                  │   EXISTS            │
  │                    │                    │                  │   ticket_order_...  │
  │                    │                    │ ←──────────────────────────────────── │
  │                    │                    │ Table created/confirmed                │
  │                    │                    │                  │                      │
  │                    │                    │ @Transactional   │                      │
  │                    │                    │ BEGIN TRANSACTION│                      │
  │                    │                    │ insertOrder()    │                      │
  │                    │                    │ ─────────────────────────────────────→ │
  │                    │                    │                  │   INSERT INTO       │
  │                    │                    │                  │   ticket_order_202605│
  │                    │                    │                  │   VALUES (          │
  │                    │                    │                  │     orderNumber: ..│
  │                    │                    │                  │     userId: 42     │
  │                    │                    │                  │     quantity: 1    │
  │                    │                    │                  │     ...            │
  │                    │                    │                  │   )                 │
  │                    │                    │ ←──────────────────────────────────── │
  │                    │                    │ 1 row inserted                         │
  │                    │                    │ COMMIT TRANSACTION                     │
  │                    │                    │                  │                      │
  │                    │                    │ getStockCache()  │                      │
  │                    │                    │ ─────────────────────────────────────→ │
  │                    │                    │                  │   SELECT from Redis │
  │                    │                    │                  │   key:              │
  │                    │                    │                  │   "TICKET:4:STOCK" │
  │                    │                    │ ←──────────────────────────────────── │
  │                    │                    │ redisStockAfter: 999                   │
  │                    │                    │                  │                      │
  │                    │                    │ getStockAvailable()                    │
  │                    │                    │ ─────────────────────────────────────→ │
  │                    │                    │                  │   SELECT           │
  │                    │                    │                  │   stock_available  │
  │                    │                    │                  │   FROM ticket_item │
  │                    │                    │                  │   WHERE id = 4     │
  │                    │                    │ ←──────────────────────────────────── │
  │                    │                    │ dbStockAfter: 999                      │
  │                    │                    │                  │                      │
  │                    │                    │ CreateOrderResponse.success()          │
  │                    │                    │ {                                      │
  │                    │                    │   success: true,                       │
  │                    │                    │   code: "SUCCESS",                     │
  │                    │                    │   message: "Order created",            │
  │                    │                    │   orderNumber: "OKX-SGN-42-1777...",   │
  │                    │                    │   redisStockAfter: 999,                │
  │                    │                    │   dbStockAfter: 999                    │
  │                    │                    │ }                                      │
  │                    │ ←─────────────────────────────────────                   │
  │                    │ CreateOrderResponse                    │                      │
  │                    │ ← Returned from Application Layer     │                      │
  │                    │ {                                      │                      │
  │                    │   success: true,                       │                      │
  │                    │   code: "SUCCESS",                     │                      │
  │                    │   orderNumber: "OKX-SGN-42-1777..",    │                      │
  │                    │   redisStockAfter: 999,                │                      │
  │                    │   dbStockAfter: 999                    │                      │
  │                    │ }                                      │                      │
  │                    │                    │                  │                      │
  │                    │ Line 34: statusFor(response)           │                      │
  │                    │ ═══════════════════════════════        │                      │
  │                    │ response.getCode() = "SUCCESS"         │                      │
  │                    │ NOT equal to "INVALID_REQUEST"         │                      │
  │                    │ → return HttpStatus.OK (200)           │                      │
  │                    │                    │                  │                      │
  │                    │ Line 34: message(response)             │                      │
  │                    │ ═════════════════════════════          │                      │
  │                    │ Create ResultMessage wrapper:          │                      │
  │                    │ ├─ success = true                      │                      │
  │                    │ ├─ code = 200                          │                      │
  │                    │ ├─ message = "Order created"           │                      │
  │                    │ ├─ timestamp = current                 │                      │
  │                    │ └─ result = CreateOrderResponse        │                      │
  │                    │                    │                  │                      │
  │                    │ Spring @RestController                 │                      │
  │                    │ Auto-converts ResultMessage → JSON     │                      │
  │                    │                    │                  │                      │
  │←─ HTTP 200 OK ─────┤                    │                  │                      │
  │ Content-Type:      │                    │                  │                      │
  │ application/json   │                    │                  │                      │
  │                    │                    │                  │                      │
  │ {                  │                    │                  │                      │
  │   "success": true, │                    │                  │                      │
  │   "code": 200,     │                    │                  │                      │
  │   "message":       │                    │                  │                      │
  │     "Order created"│                    │                  │                      │
  │   "timestamp": 1777280000000,           │                  │                      │
  │   "result": {                           │                  │                      │
  │     "success": true,                    │                  │                      │
  │     "code": "SUCCESS",                  │                  │                      │
  │     "message": "Order created",         │                  │                      │
  │     "orderNumber": "OKX-SGN-42-....",   │                  │                      │
  │     "strategy": "REDIS_LUA_WITH_...",   │                  │                      │
  │     "ticketItemId": 4,                  │                  │                      │
  │     "userId": 42,                       │                  │                      │
  │     "quantity": 1,                      │                  │                      │
  │     "redisStockAfter": 999,             │                  │                      │
  │     "dbStockAfter": 999                 │                  │                      │
  │   }                                     │                  │                      │
  │ }                  │                    │                  │                      │
  │                    │                    │                  │                      │
```

---

## 2. GET /orders - List Orders

```
CLIENT              CONTROLLER          APPLICATION         DOMAIN              INFRASTRUCTURE
  │                    │                    │                  │                      │
  ├─ HTTP GET ────────→│                    │                  │                      │
  │ /orders            │                    │                  │                      │
  │ ?userId=42         │                    │                  │                      │
  │ &yearMonth=202605  │                    │                  │                      │
  │                    │                    │                  │                      │
  │  @GetMapping       │                    │                  │                      │
  │  ────────────────→ │                    │                  │                      │
  │                    │ @RequestParam      │                  │                      │
  │                    │ binding:           │                  │                      │
  │                    │ userId = 42        │                  │                      │
  │                    │ yearMonth = 202605 │                  │                      │
  │                    │                    │                  │                      │
  │                    │ log.info()         │                  │                      │
  │                    │ "listOrders | ..."│                  │                      │
  │                    │                    │                  │                      │
  │                    │ findAllByUser()    │                  │                      │
  │                    │ ──────────────────→│                  │                      │
  │                    │                    │                  │                      │
  │                    │                    │ OrderQueryService.findAllByUser()      │
  │                    │                    │ ──────────────────────────────────────→│
  │                    │                    │                  │                      │
  │                    │                    │                  │  SELECT * FROM      │
  │                    │                    │                  │  ticket_order_202605│
  │                    │                    │                  │  WHERE user_id = 42 │
  │                    │                    │ ←──────────────────────────────────── │
  │                    │                    │ List<TicketOrderDTO>                   │
  │                    │                    │ [                                      │
  │                    │                    │   {                                    │
  │                    │                    │     orderNumber: "OKX-SGN-42-...",     │
  │                    │                    │     userId: 42,                        │
  │                    │                    │     quantity: 1,                       │
  │                    │                    │     ...                                │
  │                    │                    │   },                                   │
  │                    │                    │   { ... more orders ... }              │
  │                    │                    │ ]                                      │
  │                    │ ←──────────────────                                          │
  │                    │ List<TicketOrderDTO>                   │                      │
  │                    │                    │                  │                      │
  │                    │ ResultUtil.data()  │                  │                      │
  │                    │ Wrap in            │                  │                      │
  │                    │ ResultMessage:     │                  │                      │
  │                    │ {                  │                  │                      │
  │                    │   success: true,   │                  │                      │
  │                    │   code: 200,       │                  │                      │
  │                    │   message: "",     │                  │                      │
  │                    │   result: [...]    │                  │                      │
  │                    │ }                  │                  │                      │
  │                    │                    │                  │                      │
  │←─ HTTP 200 OK ─────┤                    │                  │                      │
  │ JSON:              │                    │                  │                      │
  │ {                  │                    │                  │                      │
  │   "success": true, │                    │                  │                      │
  │   "code": 200,     │                    │                  │                      │
  │   "result": [      │                    │                  │                      │
  │     {              │                    │                  │                      │
  │       "orderNum":..│                    │                  │                      │
  │       "userId": 42,│                    │                  │                      │
  │       "quantity": 1│                    │                  │                      │
  │     }              │                    │                  │                      │
  │   ]                │                    │                  │                      │
  │ }                  │                    │                  │                      │
  │                    │                    │                  │                      │
```

---

## 3. GET /orders/{orderNumber} - Get Order Detail

```
CLIENT              CONTROLLER          APPLICATION         DOMAIN              INFRASTRUCTURE
  │                    │                    │                  │                      │
  ├─ HTTP GET ────────→│                    │                  │                      │
  │ /orders/OKX-SGN-42 │                    │                  │                      │
  │ -1777280000000     │                    │                  │                      │
  │                    │                    │                  │                      │
  │  @GetMapping       │                    │                  │                      │
  │  /{orderNumber}    │                    │                  │                      │
  │  ────────────────→ │                    │                  │                      │
  │                    │ @PathVariable      │                  │                      │
  │                    │ binding:           │                  │                      │
  │                    │ orderNumber =      │                  │                      │
  │                    │ "OKX-SGN-42-..."   │                  │                      │
  │                    │                    │                  │                      │
  │                    │ log.info()         │                  │                      │
  │                    │                    │                  │                      │
  │                    │ findByOrderNumber()│                  │                      │
  │                    │ ──────────────────→│                  │                      │
  │                    │                    │                  │                      │
  │                    │                    │ OrderQueryService                      │
  │                    │                    │ .findByOrderNumber(null, orderNum)     │
  │                    │                    │ ──────────────────────────────────────→│
  │                    │                    │                  │                      │
  │                    │                    │                  │  SELECT * FROM      │
  │                    │                    │                  │  ticket_order_202605│
  │                    │                    │                  │  WHERE              │
  │                    │                    │                  │  order_number=".."  │
  │                    │                    │ ←──────────────────────────────────── │
  │                    │                    │ TicketOrderDTO:                        │
  │                    │                    │ {                                      │
  │                    │                    │   orderNumber: "OKX-SGN-42-...",       │
  │                    │                    │   userId: 42,                          │
  │                    │                    │   quantity: 1,                         │
  │                    │                    │   totalAmount: 5000,                   │
  │                    │                    │   ...                                  │
  │                    │                    │ }                                      │
  │                    │ ←──────────────────                                          │
  │                    │ TicketOrderDTO     │                  │                      │
  │                    │                    │                  │                      │
  │                    │ ResultUtil.data()  │                  │                      │
  │                    │ Wrap:              │                  │                      │
  │                    │ {                  │                  │                      │
  │                    │   success: true,   │                  │                      │
  │                    │   code: 200,       │                  │                      │
  │                    │   result: {...}    │                  │                      │
  │                    │ }                  │                  │                      │
  │                    │                    │                  │                      │
  │←─ HTTP 200 OK ─────┤                    │                  │                      │
  │ JSON:              │                    │                  │                      │
  │ {                  │                    │                  │                      │
  │   "success": true, │                    │                  │                      │
  │   "result": {      │                    │                  │                      │
  │     "orderNumber": │                    │                  │                      │
  │       "OKX-SGN-42..│                    │                  │                      │
  │     "userId": 42,  │                    │                  │                      │
  │     "quantity": 1, │                    │                  │                      │
  │     ...            │                    │                  │                      │
  │   }                │                    │                  │                      │
  │ }                  │                    │                  │                      │
  │                    │                    │                  │                      │
```

---

## 4. POST /admin/benchmarks/reset - Reset Benchmark Data

```
CLIENT              CONTROLLER          APPLICATION         DOMAIN              INFRASTRUCTURE
  │                    │                    │                  │                      │
  ├─ HTTP POST ──────→ │                    │                  │                      │
  │ /admin/benchmarks/ │                    │                  │                      │
  │ reset              │                    │                  │                      │
  │                    │                    │                  │                      │
  │ RequestBody:       │                    │                  │                      │
  │ {                  │                    │                  │                      │
  │   ticketItemId: 4, │                    │                  │                      │
  │   stock: 1000,     │                    │                  │                      │
  │   yearMonth: "2026│                    │                  │                      │
  │     05"            │                    │                  │                      │
  │ }                  │                    │                  │                      │
  │                    │                    │                  │                      │
  │  @PostMapping      │                    │                  │                      │
  │  ────────────────→ │                    │                  │                      │
  │                    │ @RequestBody       │                  │                      │
  │                    │ binding:           │                  │                      │
  │                    │ BenchmarkResetReq  │                  │                      │
  │                    │                    │                  │                      │
  │                    │ resetBenchmark()   │                  │                      │
  │                    │ ──────────────────→│                  │                      │
  │                    │                    │                  │                      │
  │                    │                    │ BenchmarkFixtureService               │
  │                    │                    │ .resetBenchmark(request)              │
  │                    │                    │ ──────────────────────────────────────→│
  │                    │                    │                  │                      │
  │                    │                    │                  │  UPDATE ticket_item  │
  │                    │                    │                  │  SET                 │
  │                    │                    │                  │    stock_available=10│
  │                    │                    │                  │    stock_initial=100 │
  │                    │                    │                  │  WHERE id = 4        │
  │                    │                    │ ←──────────────────────────────────── │
  │                    │                    │                  │                      │
  │                    │                    │                  │  DELETE FROM         │
  │                    │                    │                  │  ticket_order_202605 │
  │                    │                    │ ←──────────────────────────────────── │
  │                    │                    │ (orders cleared)  │                      │
  │                    │                    │                  │                      │
  │                    │                    │  Redis SET       │                      │
  │                    │                    │ ──────────────────────────────────────→│
  │                    │                    │  key: "ticket:   │                      │
  │                    │                    │    stock:4"      │                      │
  │                    │                    │  value: 1000     │                      │
  │                    │ ←──────────────────────────────────────────────────────── │
  │                    │ BenchmarkResetResponse:                │                      │
  │                    │ {                  │                  │                      │
  │                    │   success: true,   │                  │                      │
  │                    │   ticketItemId: 4, │                  │                      │
  │                    │   stock: 1000,     │                  │                      │
  │                    │   redisStockAfter: │                  │                      │
  │                    │     1000,          │                  │                      │
  │                    │   dbStockAfter:    │                  │                      │
  │                    │     1000,          │                  │                      │
  │                    │   dbOrderCount: 0  │                  │                      │
  │                    │ }                  │                  │                      │
  │                    │                    │                  │                      │
  │                    │ ResultUtil.data()  │                  │                      │
  │                    │ Wrap response      │                  │                      │
  │                    │                    │                  │                      │
  │←─ HTTP 200 OK ─────┤                    │                  │                      │
  │ JSON:              │                    │                  │                      │
  │ {                  │                    │                  │                      │
  │   "success": true, │                    │                  │                      │
  │   "result": {      │                    │                  │                      │
  │     "ticketItemId" │                    │                  │                      │
  │       : 4,         │                    │                  │                      │
  │     "stock": 1000, │                    │                  │                      │
  │     "redisStockAf│                    │                  │                      │
  │       ter": 1000,  │                    │                  │                      │
  │     "dbStockAfter" │                    │                  │                      │
  │       : 1000       │                    │                  │                      │
  │   }                │                    │                  │                      │
  │ }                  │                    │                  │                      │
  │                    │                    │                  │                      │
```

---

## 5. POST /admin/tickets/{ticketItemId}/stock/warmup - Warm Cache

```
CLIENT              CONTROLLER          APPLICATION         DOMAIN              INFRASTRUCTURE
  │                    │                    │                  │                      │
  ├─ HTTP POST ──────→ │                    │                  │                      │
  │ /admin/tickets/4   │                    │                  │                      │
  │ /stock/warmup      │                    │                  │                      │
  │                    │                    │                  │                      │
  │  @PostMapping      │                    │                  │                      │
  │  /{ticketItemId}/  │                    │                  │                      │
  │  stock/warmup      │                    │                  │                      │
  │  ────────────────→ │                    │                  │                      │
  │                    │ @PathVariable:     │                  │                      │
  │                    │ ticketItemId = 4   │                  │                      │
  │                    │                    │                  │                      │
  │                    │ warmupStock()      │                  │                      │
  │                    │ ──────────────────→│                  │                      │
  │                    │                    │                  │                      │
  │                    │                    │ BenchmarkFixtureService               │
  │                    │                    │ .warmupStock(4)                       │
  │                    │                    │ ──────────────────────────────────────→│
  │                    │                    │                  │                      │
  │                    │                    │                  │  SELECT             │
  │                    │                    │                  │  stock_available    │
  │                    │                    │                  │  FROM               │
  │                    │                    │                  │  ticket_item        │
  │                    │                    │                  │  WHERE id = 4       │
  │                    │                    │ ←──────────────────────────────────── │
  │                    │                    │ dbStock = 1000   │                      │
  │                    │                    │                  │                      │
  │                    │                    │  Redis SET       │                      │
  │                    │                    │ ──────────────────────────────────────→│
  │                    │                    │  key: "ticket:   │                      │
  │                    │                    │    stock:4"      │                      │
  │                    │                    │  value: 1000     │                      │
  │                    │ ←──────────────────────────────────────────────────────── │
  │                    │ CreateOrderResponse:                   │                      │
  │                    │ {                  │                  │                      │
  │                    │   success: true,   │                  │                      │
  │                    │   message:         │                  │                      │
  │                    │     "Stock warmed" │                  │                      │
  │                    │ }                  │                  │                      │
  │                    │                    │                  │                      │
  │←─ HTTP 200 OK ─────┤                    │                  │                      │
  │ JSON:              │                    │                  │                      │
  │ {                  │                    │                  │                      │
  │   "success": true, │                    │                  │                      │
  │   "message":       │                    │                  │                      │
  │     "Stock warmed" │                    │                  │                      │
  │ }                  │                    │                  │                      │
  │                    │                    │                  │                      │
```

---

## 6. GET /admin/benchmarks/consistency - Check Consistency

```
CLIENT              CONTROLLER          APPLICATION         DOMAIN              INFRASTRUCTURE
  │                    │                    │                  │                      │
  ├─ HTTP GET ────────→│                    │                  │                      │
  │ /admin/benchmarks/ │                    │                  │                      │
  │ consistency        │                    │                  │                      │
  │ ?ticketItemId=4    │                    │                  │                      │
  │ &yearMonth=202605  │                    │                  │                      │
  │                    │                    │                  │                      │
  │  @GetMapping       │                    │                  │                      │
  │  ────────────────→ │                    │                  │                      │
  │                    │ @RequestParam:     │                  │                      │
  │                    │ ticketItemId = 4   │                  │                      │
  │                    │ yearMonth = 202605 │                  │                      │
  │                    │                    │                  │                      │
  │                    │ getConsistency()   │                  │                      │
  │                    │ ──────────────────→│                  │                      │
  │                    │                    │                  │                      │
  │                    │                    │ ConsistencyCheckService               │
  │                    │                    │ .getConsistency(4, "202605")          │
  │                    │                    │ ──────────────────────────────────────→│
  │                    │                    │                  │                      │
  │                    │                    │                  │  Redis GET           │
  │                    │                    │                  │ ──────────────────→  │
  │                    │                    │                  │  key: "ticket:     │
  │                    │                    │                  │   stock:4"         │
  │                    │                    │ ←──────────────────────────────────── │
  │                    │                    │ redisStock = 999 │                      │
  │                    │                    │                  │                      │
  │                    │                    │                  │  SELECT             │
  │                    │                    │                  │  stock_available    │
  │                    │                    │                  │  FROM               │
  │                    │                    │                  │  ticket_item        │
  │                    │                    │ ←──────────────────────────────────── │
  │                    │                    │ dbStock = 999    │                      │
  │                    │                    │                  │                      │
  │                    │                    │                  │  SELECT COUNT(*)    │
  │                    │                    │                  │  FROM               │
  │                    │                    │                  │  ticket_order_202605│
  │                    │                    │ ←──────────────────────────────────── │
  │                    │                    │ dbOrderCount = 1 │                      │
  │                    │                    │                  │                      │
  │                    │                    │ Calculate metrics:                     │
  │                    │                    │ initialStock = 999 + 1 = 1000          │
  │                    │                    │ expectedRedisStock = 999               │
  │                    │                    │ driftAmount = 999 - 999 = 0            │
  │                    │                    │ oversoldCount = 0                      │
  │                    │                    │ inconsistency = 0                      │
  │                    │                    │                  │                      │
  │                    │                    │ ConsistencySnapshot:                   │
  │                    │                    │ {                                      │
  │                    │                    │   redisStockAfter: 999,                │
  │                    │                    │   dbStockAfter: 999,                   │
  │                    │                    │   dbOrderCount: 1,                     │
  │                    │                    │   initialStock: 1000,                  │
  │                    │                    │   expectedRedisStock: 999,             │
  │                    │                    │   driftAmount: 0,                      │
  │                    │                    │   oversoldCount: 0,                    │
  │                    │                    │   redisDbInconsistencyCount: 0         │
  │                    │                    │ }                                      │
  │                    │ ←──────────────────                                          │
  │                    │ ConsistencySnapshot│                  │                      │
  │                    │                    │                  │                      │
  │                    │ ResultUtil.data()  │                  │                      │
  │                    │ Wrap response      │                  │                      │
  │                    │                    │                  │                      │
  │←─ HTTP 200 OK ─────┤                    │                  │                      │
  │ JSON:              │                    │                  │                      │
  │ {                  │                    │                  │                      │
  │   "success": true, │                    │                  │                      │
  │   "result": {      │                    │                  │                      │
  │     "redisStockAf│                    │                  │                      │
  │       ter": 999,   │                    │                  │                      │
  │     "dbStockAfter" │                    │                  │                      │
  │       : 999,       │                    │                  │                      │
  │     "driftAmount": │                    │                  │                      │
  │       0,           │                    │                  │                      │
  │     "oversoldCount│                    │                  │                      │
  │       ": 0         │                    │                  │                      │
  │   }                │                    │                  │                      │
  │ }                  │                    │                  │                      │
  │                    │                    │                  │                      │
```

---

## 7. POST /admin/benchmarks/reconcile - Reconcile Stock

```
CLIENT              CONTROLLER          APPLICATION         DOMAIN              INFRASTRUCTURE
  │                    │                    │                  │                      │
  ├─ HTTP POST ──────→ │                    │                  │                      │
  │ /admin/benchmarks/ │                    │                  │                      │
  │ reconcile          │                    │                  │                      │
  │ ?ticketItemId=4    │                    │                  │                      │
  │                    │                    │                  │                      │
  │  @PostMapping      │                    │                  │                      │
  │  ────────────────→ │                    │                  │                      │
  │                    │ @RequestParam:     │                  │                      │
  │                    │ ticketItemId = 4   │                  │                      │
  │                    │                    │                  │                      │
  │                    │ reconcileStock()   │                    │                      │
  │                    │ ──────────────────→│                  │                      │
  │                    │                    │                  │                      │
  │                    │                    │ AdminBenchmarkService                 │
  │                    │                    │ .reconcileStock(4, "202605")          │
  │                    │                    │ ──────────────────────────────────────→│
  │                    │                    │                  │                      │
  │                    │                    │ // Scenario: Redis=500, DB=999         │
  │                    │                    │ // Drift detected!                     │
  │                    │                    │                  │                      │
  │                    │                    │                  │  SELECT             │
  │                    │                    │                  │  stock_available    │
  │                    │                    │                  │  FROM               │
  │                    │                    │                  │  ticket_item        │
  │                    │                    │ ←──────────────────────────────────── │
  │                    │                    │ dbStock = 999    │                      │
  │                    │                    │                  │                      │
  │                    │                    │  Redis SET       │                      │
  │                    │                    │ ──────────────────────────────────────→│
  │                    │                    │  key: "ticket:   │                      │
  │                    │                    │    stock:4"      │                      │
  │                    │                    │  value: 999      │                      │
  │                    │                    │  (Fixed! Now     │                      │
  │                    │                    │   consistent)    │                      │
  │                    │ ←──────────────────────────────────────────────────────── │
  │                    │ ConsistencySnapshot:                   │                      │
  │                    │ {                  │                  │                      │
  │                    │   redisStockAfter: │                  │                      │
  │                    │     999,           │                  │                      │
  │                    │   dbStockAfter: 999│                  │                      │
  │                    │   driftAmount: 0   │                  │                      │
  │                    │ }                  │                  │                      │
  │                    │                    │                  │                      │
  │←─ HTTP 200 OK ─────┤                    │                  │                      │
  │ JSON:              │                    │                  │                      │
  │ {                  │                    │                  │                      │
  │   "success": true, │                    │                  │                      │
  │   "message":       │                    │                  │                      │
  │     "Reconciled",  │                    │                  │                      │
  │   "result": {      │                    │                  │                      │
  │     "redisStockAf│                    │                  │                      │
  │       ter": 999    │                    │                  │                      │
  │   }                │                    │                  │                      │
  │ }                  │                    │                  │                      │
  │                    │                    │                  │                      │
```

---

## 8. GET /tickets/{ticketItemId} - Get Ticket Info

```
CLIENT              CONTROLLER          APPLICATION         DOMAIN              INFRASTRUCTURE
  │                    │                    │                  │                      │
  ├─ HTTP GET ────────→│                    │                  │                      │
  │ /tickets/4         │                    │                  │                      │
  │                    │                    │                  │                      │
  │  @GetMapping       │                    │                  │                      │
  │  /{ticketItemId}   │                    │                  │                      │
  │  ────────────────→ │                    │                  │                      │
  │                    │ @PathVariable:     │                  │                      │
  │                    │ ticketItemId = 4   │                  │                      │
  │                    │                    │                  │                      │
  │                    │ getTicketDetail()  │                  │                      │
  │                    │ ──────────────────→│                  │                      │
  │                    │                    │                  │                      │
  │                    │                    │ TicketService   │                      │
  │                    │                    │ .findById(4)    │                      │
  │                    │                    │ ──────────────────────────────────────→│
  │                    │                    │                  │                      │
  │                    │                    │                  │  SELECT * FROM      │
  │                    │                    │                  │  ticket_item        │
  │                    │                    │                  │  WHERE id = 4       │
  │                    │                    │ ←──────────────────────────────────── │
  │                    │                    │ TicketDetailDTO: │                      │
  │                    │                    │ {                                      │
  │                    │                    │   id: 4,                               │
  │                    │                    │   name: "Flash Sale Ticket",           │
  │                    │                    │   stockInitial: 10000,                 │
  │                    │                    │   stockAvailable: 999,                 │
  │                    │                    │   priceOriginal: 5000,                 │
  │                    │                    │   priceFlash: 5000,                    │
  │                    │                    │   status: "ACTIVE",                    │
  │                    │                    │   ...                                  │
  │                    │                    │ }                                      │
  │                    │ ←──────────────────                                          │
  │                    │ TicketDetailDTO    │                  │                      │
  │                    │                    │                  │                      │
  │                    │ ResultUtil.data()  │                  │                      │
  │                    │ Wrap response      │                  │                      │
  │                    │                    │                  │                      │
  │←─ HTTP 200 OK ─────┤                    │                  │                      │
  │ JSON:              │                    │                  │                      │
  │ {                  │                    │                  │                      │
  │   "success": true, │                    │                  │                      │
  │   "result": {      │                    │                  │                      │
  │     "id": 4,       │                    │                  │                      │
  │     "name":        │                    │                  │                      │
  │       "Flash Sale" │                    │                  │                      │
  │     "stockAvailabl│                    │                  │                      │
  │       e": 999,     │                    │                  │                      │
  │     "priceFlash":  │                    │                  │                      │
  │       5000         │                    │                  │                      │
  │   }                │                    │                  │                      │
  │ }                  │                    │                  │                      │
  │                    │                    │                  │                      │
```

---

## POST /orders - Step-by-Step Breakdown with Code Lines

### Full Request → Response Journey (9 Steps)

```
┌─────────────────────────────────────────────────────────────────────────────┐
│ REQUEST PHASE                                                               │
└─────────────────────────────────────────────────────────────────────────────┘

STEP 1: Spring HTTP Binding
  Client sends: POST /orders with JSON body
  Spring parses: @RequestBody CreateOrderRequest
  Result: CreateOrderRequest object {ticketItemId:4, userId:42, quantity:1,...}

STEP 2: TicketOrderController - Line 29-32
  @PostMapping("/orders")
  public ResponseEntity<ResultMessage<CreateOrderResponse>> createOrder(
      @RequestBody CreateOrderRequest request) {
    CreateOrderResponse response = ticketOrderAppService.createOrder(request);
    return ResponseEntity.status(statusFor(response)).body(message(response));
  }

STEP 3: OrderCreationService - Validate & Idempotency
  ✓ Validate: ticketItemId > 0, userId > 0, quantity > 0, strategy not null
  ✓ Build idempotency key: "42:user-42-run-1"
  ✓ Check cache: Not found, proceed to create

STEP 4: OrderCreationService.doCreateOrder() - Build Order Number
  buildOrderNumber(42, 1777280000000) → "OKX-SGN-42-1777280000000"
  formatYearMonth(1777280000000) → "202605"

STEP 5: Stock Deduction Strategy - REDIS_LUA_WITH_COMPENSATION
  Strategy: get(REDIS_LUA_WITH_COMPENSATION)
  Action: decrease(request) → Execute Lua script in Redis
  Redis: if stock >= quantity → stock -= quantity (999)
  Result: StockDeductionResult {success: true, compensateOnOrderFailure: true}

STEP 6: Infrastructure - Create Order Table & Insert Row
  SQL: CREATE TABLE IF NOT EXISTS ticket_order_202605 (...)
  SQL: BEGIN TRANSACTION
  SQL: INSERT INTO ticket_order_202605 (orderNumber, userId, quantity, ...)
  SQL: COMMIT TRANSACTION

STEP 7: Read Current State
  Query Redis: GET "TICKET:4:STOCK" -> 999
  Query DB: SELECT stock_available FROM ticket_item WHERE id = 4 → 999

┌─────────────────────────────────────────────────────────────────────────────┐
│ RESPONSE PHASE                                                              │
└─────────────────────────────────────────────────────────────────────────────┘

STEP 8: Build CreateOrderResponse (Application Layer)
  CreateOrderResponse.success(
    request,
    "OKX-SGN-42-1777280000000",  // orderNumber
    999,                          // redisStockAfter
    999                           // dbStockAfter
  )
  Result: CreateOrderResponse {
    success: true,
    code: "SUCCESS",
    message: "Order created",
    orderNumber: "OKX-SGN-42-1777280000000",
    redisStockAfter: 999,
    dbStockAfter: 999
  }

STEP 9: TicketOrderController - Determine Status & Wrap Response
  Line 34: statusFor(response)
    if ("INVALID_REQUEST".equals(response.getCode())) → 400
    else → 200
    Result: HttpStatus.OK (200)

  Line 34: message(response)
    new ResultMessage<>()
      .setSuccess(true)                                    // response.isSuccess()
      .setCode(response.isSuccess() ? 200 : 409)           // 200
      .setMessage("Order created")                         // response.getMessage()
      .setResult(response)                                 // Full CreateOrderResponse
      .setTimestamp(current_time)
    Result: ResultMessage<CreateOrderResponse> {
      success: true,
      code: 200,
      message: "Order created",
      timestamp: 1777280000000,
      result: {
        success: true,
        code: "SUCCESS",
        message: "Order created",
        orderNumber: "OKX-SGN-42-1777280000000",
        strategy: "REDIS_LUA_WITH_COMPENSATION",
        ticketItemId: 4,
        userId: 42,
        quantity: 1,
        redisStockAfter: 999,
        dbStockAfter: 999
      }
    }

  Spring converts ResultMessage to JSON

HTTP 200 OK Response:
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

## Legend

```
CLIENT = HTTP client (curl, Postman, browser)
CONTROLLER = HTTP entry point (Spring REST Controller)
APPLICATION = Business orchestration & coordination layer
DOMAIN = Business rules & logic
INFRASTRUCTURE = Data access (MySQL, Redis, repositories)

→ = Synchronous call/request
← = Return/response
```

---

## Key Patterns

### ✅ Success Pattern
```
Request → Validate → Execute → Query state → Wrap response → HTTP 200
```

### ❌ Failure Pattern (Business Error)
```
Request → Validate → Execute fails → Query current state → Wrap error → HTTP 200/409
```

### ❌ Failure Pattern (Validation Error)
```
Request → Validate fails → Return error → HTTP 400
```

### 🔄 Compensation Pattern (REDIS_LUA_WITH_COMPENSATION)
```
Redis decrease ✓
DB insert ✗ FAILS
→ Trigger compensation: Redis restore ✓
→ Return failure response
```

---

## Code Reference - TicketOrderController.java

### POST /orders Implementation

```java
// Line 29-32: Main endpoint
@PostMapping("/orders")
public ResponseEntity<ResultMessage<CreateOrderResponse>> createOrder(
    @RequestBody CreateOrderRequest request) {
    CreateOrderResponse response = ticketOrderAppService.createOrder(request);
    return ResponseEntity.status(statusFor(response)).body(message(response));
}
```

### HTTP Status Decision Logic - Lines 101-104

```java
private HttpStatus statusFor(CreateOrderResponse response) {
    if ("INVALID_REQUEST".equals(response.getCode())) {
        return HttpStatus.BAD_REQUEST;  // 400
    }
    return HttpStatus.OK;  // 200
}
```

### Response Wrapping Logic - Lines 105-114

```java
private ResultMessage<CreateOrderResponse> message(CreateOrderResponse response) {
    ResultMessage<CreateOrderResponse> message = new ResultMessage<>();
    message.setSuccess(response.isSuccess());  // true/false
    message.setCode(response.isSuccess() ? 200 : 
                    "INVALID_REQUEST".equals(response.getCode()) ? 400 : 409);
    message.setMessage(response.getMessage());
    message.setResult(response);
    return message;
}
```

---

## Why Response Looks This Way

### ResponseEntity Structure
```
ResponseEntity<ResultMessage<CreateOrderResponse>>
    │
    ├─ HTTP Status Code (line 34: statusFor)
    │  └─ 200 OK (success case)
    │  └─ 400 Bad Request (validation error)
    │  └─ 200 OK (business error - code in body is 409)
    │
    └─ Body: ResultMessage<CreateOrderResponse>
       │
       ├─ success: boolean (from CreateOrderResponse)
       ├─ code: int (200, 400, or 409)
       ├─ message: String (from CreateOrderResponse)
       ├─ timestamp: long (auto-added)
       └─ result: CreateOrderResponse
          ├─ success: boolean
          ├─ code: String ("SUCCESS", "REDIS_STOCK_UNAVAILABLE", etc)
          ├─ message: String
          ├─ orderNumber: String
          ├─ strategy: OrderStrategy
          ├─ ticketItemId: Long
          ├─ userId: Long
          ├─ quantity: Integer
          ├─ redisStockAfter: Long
          └─ dbStockAfter: Long
```

### Data Flow Summary

```
1. HTTP Request
   ↓
2. Spring binds JSON → CreateOrderRequest
   ↓
3. TicketOrderController.createOrder(request) [Line 29]
   ↓
4. TicketOrderAppService.createOrder(request)
   ↓
5. OrderCreationService.createOrder(request)
   → Validates, checks idempotency, coordinates strategy, inserts DB order
   → Returns: CreateOrderResponse {success, code, orderNumber, stocks}
   ↓
6. Back to TicketOrderController [Line 34]
   → statusFor(response) decides HTTP status (200 or 400)
   → message(response) wraps in ResultMessage envelope
   ↓
7. ResponseEntity<ResultMessage<CreateOrderResponse>>
   ↓
8. Spring @RestController Jackson serialization
   ↓
9. HTTP Response with JSON body
```

### Key Takeaways

- **HTTP Status (200 vs 400)**: Determined by `statusFor()` based on response.getCode()
  - 400 = Validation error ("INVALID_REQUEST")
  - 200 = Everything else (success or business failure)
  
- **Response Code (code field, 200 vs 409)**: Determined by `message()` method
  - 200 = response.isSuccess() == true
  - 400 = response.getCode() == "INVALID_REQUEST"
  - 409 = Other failures (business errors)

- **result.code (String)**: Business-level status from CreateOrderResponse
  - "SUCCESS" = Order created
  - "REDIS_STOCK_UNAVAILABLE" = Stock exhausted
  - "INVALID_REQUEST" = Validation failed
  - "ORDER_CREATE_FAILED" = Database error

- **Nested Structure**: ResultMessage wraps CreateOrderResponse to provide envelope while preserving original response data

---

