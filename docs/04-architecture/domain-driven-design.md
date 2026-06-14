# Domain-Driven Design

> DDD-layered architecture applied to the Flash-Sale Concurrency Engine.

## 1. Bounded Contexts

| Context | Module | Ownership |
|---|---|---|
| **Order** | `xxxx-domain` | `TickerOrder`, `TickerOrderRepository`, `TickerOrderDomainService` — stock deduction and order persistence |
| **Ticket** | `xxxx-domain` | `Ticket`, `TicketDetail`, repositories — fixture data |
| **Benchmark** | `xxxx-application` | `BenchmarkFixtureService`, `BenchmarkRunService`, `ConsistencyCheckService` — lab operations |
| **Messaging** | `xxxx-application/MQ` | `OutboxService`, `OutboxPublishScheduler` — reliable event publishing |

## 2. Ubiquitous Language

| Term | Definition |
|---|---|
| **Stock** | Available quantity for sale |
| **Stock Deduction** | Atomic stock reduction |
| **Strategy** | One of 4 algorithms (UNSAFE_DB, CONDITIONAL_DB, REDIS_LUA, REDIS_LUA_WITH_COMPENSATION) |
| **Oversell** | Selling beyond stock (negative dbStockAfter) |
| **Drift** | Redis stock ≠ DB stock |
| **Compensation** | Restoring Redis when DB rejects |
| **Reconciliation** | Scheduled Redis-DB sync |
| **Warmup** | Copying DB stock → Redis before benchmark |
| **Reset** | Restoring initial stock, clearing orders |
| **Idempotency Key** | `userId:idempotencyKey` preventing duplicate orders |
| **Outbox Event** | Persisted event row atomically written with domain change |
| **Consistency Snapshot** | Point-in-time Redis/DB/order/drift view |

## 3. Aggregates

### Order Aggregate
- **Root**: `TickerOrder` (monthly table `ticket_order_YYYYMM`)
- **Invariants**: Stock available before creation, unique order number, one order per idempotency key
- **Repository**: `TickerOrderRepository` (port) → `TickerOrderRepositoryImpl`

### Stock Aggregate
- **Root**: `TicketDetail` (`ticket_item` table)
- **Invariants**: `stock_available >= 0` for safe strategies, Redis matches after compensation
- **Repository**: `TicketDetailRepository` (port) → `TicketDetailInfrasRepositoryImpl`

## 4. Domain Events

| Event | Trigger | Payload |
|---|---|---|
| `ORDER_CREATED` | `OrderCreationService` successful create | `CreateOrderResponse` fields |
| `RECONCILIATION` | `OrderReconciliationService` drift detected | Drift amount, direction, ticketItemId |

### Event Flow

```text
OrderCreationService (@Transactional)
  → TickerOrderDomainService.decreaseStockLevel1()
  → OrderDeductionDomainService.insertOrder()
  → OutboxService.record("Order", orderNumber, "ORDER_CREATED", response)
  → COMMIT (order + outbox atomically)

OutboxPublishScheduler (every 1s)
  → publishPendingEvents() → KafkaTemplate → flashsale.orders
```

## 5. Repository Pattern

**Domain port** (`xxxx-domain`): pure interface, zero framework dependencies.
**Infrastructure adapter** (`xxxx-infrastructure`): JPA/JDBC implementation.

Cross-context references use IDs only — no direct entity traversal.

## 6. Value Objects

| VO | Fields | Factory |
|---|---|---|
| `StockDeductionResult` | `success`, `code`, `message`, `requiresCompensation` | `.success()` / `.failure()` |
| `ConsistencySnapshot` | `redisStockAfter`, `dbStockAfter`, `dbOrderCount`, `oversoldCount`, `driftAmount` | Built by `ConsistencyCheckService` |

## 7. DDD Compliance

- [x] Bounded contexts identified
- [x] Ubiquitous language defined
- [x] Aggregates with roots and invariants
- [x] Domain events via outbox
- [x] Repository pattern (port in domain, impl in infrastructure)
- [x] Domain layer has zero framework dependencies
- [ ] Event versioning (future)
