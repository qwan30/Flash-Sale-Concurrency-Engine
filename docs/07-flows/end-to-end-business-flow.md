# End-to-End Business Flow

> Complete order → outbox → reconciliation flow across all layers.

## 1. Order Creation (POST /orders)

```text
CLIENT → TicketOrderController.createOrder()
  → TicketOrderAppServiceImpl.createOrder(request)           [@Observed: order.create]
  → OrderCreationService.createOrder(request)                [@Transactional]
    │
    ├─ 1. validateCreateOrderRequest(request)
    │     ticketItemId>0, userId>0, quantity>0, valid strategy, idempotencyKey non-empty
    │
    ├─ 2. IdempotencyService.getOrCreate(userId, idempotencyKey)
    │     In-memory check → cached response if duplicate
    │
    ├─ 3. StockDeductionStrategyRegistry.get(strategy)
    │     EnumMap lookup → StockDeductionStrategy impl
    │
    ├─ 4. strategy.decrease(request)
    │     UNSAFE_DB:               UPDATE without stock check
    │     CONDITIONAL_DB:          UPDATE WHERE stock_available >= qty
    │     REDIS_LUA:               Redis Lua → DB conditional → no restore
    │     REDIS_LUA_WITH_COMP:     Redis Lua → DB conditional → restore Redis if DB rejects
    │     → StockDeductionResult { success, code, message, requiresCompensation }
    │
    ├─ 5. ensureMonthlyOrderTable(yearMonth)
    │     CREATE TABLE IF NOT EXISTS ticket_order_YYYYMM
    │
    ├─ 6. insertOrder(yearMonth, order)
    │     INSERT INTO ticket_order_YYYYMM
    │     orderNumber = "OKX-SGN-{userId}-{timestamp}"
    │
    ├─ 7. OutboxService.record("Order", orderNumber, "ORDER_CREATED", response)
    │     INSERT INTO outbox_event (atomic with step 6)
    │
    └─ 8. If step 6 fails + requiresCompensation:
          stockOrderCacheService.restoreStockCache(id, qty)  → Redis TICKET:{id}:STOCK += qty
  → ResultMessage<CreateOrderResponse> { success, code, orderNumber, strategy, redisStockAfter, dbStockAfter }
```

## 2. Outbox → Kafka

```text
OutboxPublishScheduler (every 1s)
  → publishPendingEvents()
    SELECT * FROM outbox_event WHERE status='PENDING' ORDER BY created_at LIMIT 50
    For each event:
      1. Build OutboxEnvelope { id, aggregateType, aggregateId, eventType, version, payload }
      2. kafkaTemplate.send("flashsale.orders", aggregateId, message).get(5s)
      3. Success → markPublished()  → status='PUBLISHED'
      4. Failure → markFailed()     → status='FAILED', attempts++, next_attempt_at=NOW+10s
    Metrics: outbox.publish.{success,failure,latency}

  → retryFailedEvents()
    SELECT * FROM outbox_event WHERE status='FAILED' AND next_attempt_at <= NOW() LIMIT 50
    resetForRetry() → status='PENDING'
    Metrics: outbox.retry.scheduled

  After 5 attempts: stays FAILED → manual inspection
```

## 3. Reconciliation

```text
OrderReconciliationService.scheduledReconcile()    [every 30s, 10s initial delay, ticket=4]
  → reconcile(4, null)
    ├─ ConsistencyCheckService.getConsistency(4, yearMonth)
    │   redisStock = cacheStore.getIntOrNull("TICKET:4:STOCK")
    │   dbStock = tickerOrderDomainService.getStockAvailable(4)
    │   orderCount = orderDeductionDomainService.countOrders(yearMonth)
    │   driftAmount = redisStock - dbStock
    ├─ If driftAmount != 0:
    │   stockOrderCacheService.setStockCache(4, dbStock)   → repair
    ├─ flashsale.reconciliation metric
    └─ OutboxService.record("Reconciliation", "4", "RECONCILIATION", result)

MANUAL: POST /admin/benchmarks/reconcile?ticketItemId=4&yearMonth=202604
```

## 4. Benchmark Cycle

```text
1. POST /admin/benchmarks/reset { ticketItemId:4, stock:1000, yearMonth }
     DB=1000, clear orders, Redis=1000, clear idempotency

2. POST /admin/tickets/4/stock/warmup
     Redis TICKET:4:STOCK = DB stock

3. JMeter: concurrent POST /orders × N
     Each → Order Creation Flow (above)

4. GET /admin/benchmarks/consistency?ticketItemId=4&yearMonth=...
     Snapshot: Redis, DB, order count, drift, oversold

5. Save: benchmark/results/{Strategy}-{yyyyMMdd-HHmmss}/
     reset.json, warmup.json, results.jtl, consistency.json, run.json, summary-row.md
```

## 5. Outbox Event State Machine

```text
    record()        publishPendingEvents()
 (none) ──► PENDING ─────────┬─ success ──► PUBLISHED
                              │
                              └─ failure ──► FAILED
                                               │
                              retryFailedEvents() (after delay, < 5 attempts)
                                               │
                                               └──► PENDING (retry)
                                               │
                              (5 attempts exhausted)
                                               │
                                               └──► FAILED (terminal)
```

## 6. Key Participants

| Layer | Component | Role |
|---|---|---|
| Controller | `TicketOrderController` | HTTP POST /orders |
| Controller | `AdminBenchmarkController` | Admin reset/warmup/consistency |
| Application | `OrderCreationService` | Orchestration, validation, idempotency |
| Application | `StockDeductionStrategyRegistry` | Strategy lookup |
| Application | `OutboxService` | Event record & publish |
| Application | `OutboxPublishScheduler` | Scheduled drain |
| Application | `OrderReconciliationService` | Drift repair |
| Application | `BenchmarkFixtureService` | Reset/warmup |
| Application | `ConsistencyCheckService` | Snapshot calc |
| Domain | `TickerOrderDomainService` | Stock ops |
| Domain | `OrderDeductionDomainService` | Order persistence |
| Infrastructure | `StockOrderCacheService` | Redis stock cache |
| Infrastructure | `RedisInfrasService` | Redis primitives |
| Infrastructure | `KafkaTemplate` (Spring Kafka) | Kafka producer |
