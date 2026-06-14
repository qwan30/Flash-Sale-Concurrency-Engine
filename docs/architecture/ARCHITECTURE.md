# Architecture

The system is a Spring Boot backend reliability lab with an optional Next.js operator dashboard. It uses ticket ordering as a fixture to prove stock-deduction correctness under concurrent flash-sale load.

## Module Layout

| Module | Responsibility |
|---|---|
| `app/backend/xxxx-domain` | Domain entities, repository ports, and domain services |
| `app/backend/xxxx-application` | Order orchestration, validation, strategies, idempotency, reconciliation, benchmark models, and benchmark-result reading |
| `app/backend/xxxx-infrastructure` | MySQL repository adapters, Redis cache adapters, Redisson configuration, and JPA mappers |
| `app/backend/xxxx-controller` | HTTP controllers and `ResultMessage<T>` envelope creation |
| `app/backend/xxxx-start` | Spring Boot entry point, runtime config, scheduling, actuator, and Springdoc OpenAPI config |
| `app/frontend` | Optional Next.js operator dashboard |
| `benchmark` | JMeter plan, smoke script, benchmark runner, and experiment contract |
| `environment` | Docker Compose services for MySQL, Redis, Nginx, and optional observability |

## Runtime Stack

| Component | Current setting |
|---|---|
| Java | 21 |
| Spring Boot | `3.3.5` |
| Springdoc OpenAPI | `2.6.0` |
| Backend port | `1122` |
| MySQL | `localhost:3316`, database `vetautet` |
| Redis | `localhost:6319` |
| Kafka | `localhost:9094`, KRaft mode, topic `flashsale.orders` |
| Tomcat | virtual threads enabled, max 500 threads, min-spare 50 |
| Benchmark result path | `${BENCHMARK_RESULTS_DIR:benchmark/results}` |
| Frontend | Next.js 16 on `http://localhost:3000` |

## Main Order Flow

`POST /orders` is the modern order path used by the dashboard and benchmark runner.

```text
TicketOrderController.createOrder
  -> TicketOrderAppServiceImpl.createOrder
  -> OrderCreationService.createOrder
  -> validateCreateOrderRequest
  -> IdempotencyService.getOrCreate(userId:idempotencyKey)
  -> StockDeductionStrategyRegistry.get(strategy)
  -> selected StockDeductionStrategy.decrease
  -> OrderDeductionDomainService.ensureMonthlyOrderTable(yearMonth)
  -> OrderDeductionDomainService.insertOrder(yearMonth, order)
  -> ResultMessage<CreateOrderResponse>
```

The order service validates the request, builds an idempotency key from `userId:idempotencyKey`, reserves stock with the selected strategy, creates `ticket_order_{yyyyMM}` when needed, inserts the order row, and returns current Redis/DB stock values.

## Stock Strategy Boundary

`OrderStrategy` has four values:

| Strategy | Implementation | Main dependency |
|---|---|---|
| `UNSAFE_DB` | `UnsafeDbStockDeductionStrategy` | MySQL unconditional update |
| `CONDITIONAL_DB` | `ConditionalDbStockDeductionStrategy` | MySQL conditional update |
| `REDIS_LUA` | `RedisLuaStockDeductionStrategy` | Redis Lua gate plus DB conditional update |
| `REDIS_LUA_WITH_COMPENSATION` | `RedisLuaCompensatingStockDeductionStrategy` | Redis Lua gate, DB conditional update, Redis restore |

The registry maps each enum to one strategy implementation. Adding a strategy requires a new enum value, a new `StockDeductionStrategy`, and tests that prove oversell and drift expectations.

## Storage Model

MySQL is durable truth.

| Table | Role |
|---|---|
| `ticket` | Event/activity fixture |
| `ticket_item` | Stock fixture; strategies mutate `stock_available` |
| `ticket_order_YYYYMM` | Monthly order table created on demand |
| `ticket_order_details_YYYYMM` | Detail fixture from seed data |

The main safe DB predicate is:

```sql
UPDATE TicketDetail t
SET t.stockAvailable = t.stockAvailable - :quantity
WHERE t.id = :ticketId
  AND t.stockAvailable >= :quantity
```

The unsafe baseline intentionally omits `stockAvailable >= :quantity`.

## Redis Model

Redis stores fast stock state under:

```text
TICKET:{ticketItemId}:STOCK
```

`StockOrderCacheService` owns the Redis key, warmup, Lua decrement, direct set, and restore operations. Redis is not the source of truth; it is the fast gate/cache used by Redis-backed strategies.

## Consistency And Reconciliation

`ConsistencyCheckService` builds the run snapshot:

```text
initialStock = dbStockAfter + dbOrderCount
expectedRedisStock = initialStock - dbOrderCount
driftAmount = redisStockAfter - expectedRedisStock
redisDbInconsistencyCount = driftAmount != 0 ? 1 : 0
oversoldCount = max(0, -dbStockAfter)
```

Because `expectedRedisStock` equals the DB stock after a completed run, a healthy compensated run should finish with:

```text
oversoldCount = 0
redisDbInconsistencyCount = 0
```

`OrderReconciliationService` runs every 30 seconds after a 10 second initial delay for default ticket item `4`. Manual reconciliation is available through `POST /admin/benchmarks/reconcile`.

## API Documentation

Springdoc is configured in `OpenApiConfig`.

| Surface | URL |
|---|---|
| Swagger UI | `http://localhost:1122/swagger-ui.html` |
| OpenAPI JSON | `http://localhost:1122/v3/api-docs` |
| Grouped lab API JSON | `http://localhost:1122/v3/api-docs/lab-api` |

The grouped API scans `com.xxxx.ddd.controller.http`.

## Dashboard Integration

The frontend calls the backend through a Next.js proxy:

```text
browser -> /api/backend/* -> BACKEND_BASE_URL -> Spring Boot backend
```

`BACKEND_BASE_URL` defaults to `http://localhost:1122`. The dashboard is an operator tool for reset, warmup, order probes, benchmark review, and consistency checks.

## Observability

The backend exposes actuator endpoints and Micrometer metrics:

```text
GET /actuator/health
GET /actuator/prometheus
GET /actuator/metrics
flashsale.orders{strategy,result}
flashsale.reconciliation{action,direction}
outbox.publish.{success,failure,latency}
outbox.backlog.{pending,failed}
```

`@Observed` spans are created for `order.create`, `stock.warmup`, `benchmark.reset`, and `consistency.check` service methods via Micrometer Tracing with the Brave bridge.

The optional Docker observability profile starts Prometheus and Grafana.

## Messaging — Kafka And Transactional Outbox

The backend uses the transactional outbox pattern for reliable event publishing to Kafka.

```text
OrderCreationService (within @Transactional)
  → domain change persisted to MySQL
  → OutboxService.record() atomically writes event row
                                                    ↓
OutboxPublishScheduler (every 1s)
  → OutboxService.publishPendingEvents() reads pending batch
  → KafkaTemplate sends to topic flashsale.orders
  → marks event PUBLISHED or FAILED (retryable)
```

Key configuration in `application.yml`:

| Property | Default | Meaning |
|---|---|---|
| `app.kafka.topic` | `flashsale.orders` | Kafka topic for outbox events |
| `app.outbox.publish-batch-size` | `50` | Events processed per scheduler tick |
| `app.outbox.retry-delay` | `10s` | Delay before retrying a failed event |
| `app.outbox.max-attempts` | `5` | Max publish attempts before event is abandoned |

Events published:

| Type | Emitted by | Payload |
|---|---|---|
| `ORDER_CREATED` | `OrderCreationService` after order persistence | `CreateOrderResponse` fields |
| `RECONCILIATION` | `OrderReconciliationService` on drift repair | `ReconciliationResult` fields |

Kafka runs in KRaft mode (no ZooKeeper) via `apache/kafka:3.9.0`. The producer uses `acks=all` for durability. The outbox guarantees at-least-once delivery: if Kafka is unreachable, events stay in the database and are retried.

## CI/CD Pipeline

**CI** (`.github/workflows/ci.yml`): runs on every push and PR to master.

| Job | What it validates |
|---|---|
| Backend — Unit Tests | `mvn test` for application, controller, and infrastructure modules |
| Backend — Integration Tests | Docker-gated tests with Testcontainers |
| Backend — Package JAR | Builds and uploads the Spring Boot fat JAR |
| Observability Smoke Test | Starts the backend JAR, verifies health, Prometheus metrics, and structured log format |
| Frontend — Lint, Typecheck & Build | `npm run lint`, `npm run typecheck`, `npm run build` |
| Infra — Config Validation | Validates docker-compose files, Prometheus config, ELK configs, Nginx, and Redis Sentinel |

**CD** (`.github/workflows/cd.yml`): on push to master, builds and pushes Docker images to GitHub Container Registry.

| Image | Registry path |
|---|---|
| Backend | `ghcr.io/qwan30/flashsale-backend:latest` |
| Frontend | `ghcr.io/qwan30/flashsale-frontend:latest` |

Production deployment uses `environment/docker-compose.prod.yml` which references these pre-built images alongside MySQL, Redis, and Kafka services.
