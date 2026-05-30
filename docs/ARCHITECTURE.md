# Architecture

The project is a Spring Boot backend reliability lab with an optional Next.js operator dashboard. The ticket domain is the fixture; the main system behavior is concurrent stock deduction, consistency inspection, and reproducible benchmark evidence.

## Module Layout

| Module | Responsibility |
|---|---|
| `app/backend/xxxx-domain` | Domain entities, repository ports, and domain services |
| `app/backend/xxxx-application` | Order orchestration, strategies, idempotency, reconciliation, and benchmark models |
| `app/backend/xxxx-infrastructure` | MySQL repositories, Redis cache adapters, Redisson configuration |
| `app/backend/xxxx-controller` | HTTP controllers and response envelope |
| `app/backend/xxxx-start` | Spring Boot entry point, runtime config, Swagger/OpenAPI config |
| `app/frontend` | Optional operator dashboard for lab control and result inspection |
| `benchmark` | JMeter plan, PowerShell runners, experiment contract, result folders |
| `environment` | Docker Compose for MySQL, Redis, and optional observability services |

## Runtime Stack

| Component | Current setting |
|---|---|
| Java | 21 |
| Spring Boot | `3.3.5` |
| Springdoc OpenAPI | `2.6.0` |
| Backend port | `1122` |
| MySQL | `localhost:3316`, database `vetautet` |
| Redis | `localhost:6319` |
| Benchmark result path | `benchmark/results` by default |
| Frontend | Next.js 16 on `http://localhost:3000` |

## Request Flow

`POST /orders` follows this path:

```text
TicketOrderController
  -> TicketOrderAppServiceImpl
  -> OrderCreationService
  -> StockDeductionStrategyRegistry
  -> selected StockDeductionStrategy
  -> TickerOrderDomainService / StockOrderCacheService
  -> OrderDeductionDomainService
  -> OrderDeductionInfrasRepositoryImpl
```

The order service validates the request, builds an idempotency key from `userId:idempotencyKey`, deducts stock using the selected strategy, inserts an order into `ticket_order_{yyyyMM}`, and returns a `CreateOrderResponse` wrapped in `ResultMessage`.

## Stock And Order Storage

MySQL is the durable source of truth.

| Table | Purpose |
|---|---|
| `ticket` | ticket activity fixture |
| `ticket_item` | stock fixture; strategies mutate `stock_available` |
| `ticket_order_YYYYMM` | monthly order table, created with `CREATE TABLE IF NOT EXISTS` |
| `ticket_order_details_YYYYMM` | detail fixture from seed data |

Redis is the fast stock gate for Redis-backed strategies.

| Key pattern | Purpose |
|---|---|
| `ticket:stock:{ticketItemId}` | cached available stock |
| idempotency cache | in-process cache in `IdempotencyService` |

## Strategy Model

`StockDeductionStrategy` implementations are selected by `OrderStrategy`:

| Strategy | Main dependency | Safety role |
|---|---|---|
| `UNSAFE_DB` | MySQL unconditional update | intentionally unsafe baseline |
| `CONDITIONAL_DB` | MySQL conditional update | safe DB baseline |
| `REDIS_LUA` | Redis Lua plus DB conditional update | fast gate without compensation |
| `REDIS_LUA_WITH_COMPENSATION` | Redis Lua plus DB conditional update and restore | safe Redis-first lab strategy |

The Redis strategies still update MySQL stock through `decreaseStockLevel1`. The difference is how they handle the case where Redis was decremented but the DB step or order insertion fails.

## Reconciliation

`OrderReconciliationService` runs every 30 seconds after a 10 second initial delay. The lab reconciles the default ticket item `4`.

```text
Read Redis stock
Read DB stock
drift = redisStock - dbStock
if drift != 0: set Redis stock to DB stock
```

This keeps Redis aligned with the DB source of truth after compensation failures, application crashes, or manual cache changes.

## API Documentation

Swagger/OpenAPI is configured in `app/backend/xxxx-start/src/main/java/com/xxxx/config/OpenApiConfig.java`.

| Surface | URL |
|---|---|
| Swagger UI | `http://localhost:1122/swagger-ui.html` |
| OpenAPI JSON | `http://localhost:1122/v3/api-docs` |
| Grouped lab API JSON | `http://localhost:1122/v3/api-docs/lab-api` |

The group scans `com.xxxx.ddd.controller.http`.

## Frontend Integration

The dashboard calls the backend through the Next.js route proxy:

```text
browser -> /api/backend/* -> BACKEND_BASE_URL -> Spring Boot backend
```

`BACKEND_BASE_URL` defaults to `http://localhost:1122`. Benchmark pages read `GET /admin/benchmarks/runs`; if no saved backend runs exist, the dashboard shows sample rows from `src/lib/benchmark-data.ts`.

## Observability

Micrometer counters are emitted for order results and reconciliation repairs:

```text
flashsale.orders{strategy,result}
flashsale.reconciliation{action,direction}
```

Prometheus reads them from `GET /actuator/prometheus`. The optional Docker profile starts Prometheus and Grafana.
