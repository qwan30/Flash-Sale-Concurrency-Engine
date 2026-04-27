# Flash-Sale Ticketing Backend Lab

This repository is a Spring Boot backend lab for comparing inventory deduction strategies under concurrent flash-sale load. It is intentionally backend-only: MySQL, Redis/Lua, Redisson, monthly order tables, Actuator/Micrometer metrics, and JMeter benchmarks.

## Run Locally

```bash
docker compose -f environment/docker-compose-dev.yml up -d
mvn spring-boot:run -pl xxxx-start
```

Default local services:

| Service | URL |
|---|---|
| App | `http://localhost:1122` |
| MySQL | `localhost:3316`, database `vetautet` |
| Redis | `localhost:6319` |
| Actuator Prometheus | `http://localhost:1122/actuator/prometheus` |

Optional observability stack:

```bash
docker compose -f environment/docker-compose-dev.yml --profile observability up -d
```

## API Contract

| API | Purpose |
|---|---|
| `POST /orders` | Place order with selected strategy |
| `GET /orders/{orderNumber}` | Fetch one order |
| `GET /orders?userId=&yearMonth=` | List orders in a monthly table |
| `GET /tickets/{ticketItemId}` | Ticket detail |
| `POST /admin/tickets/{ticketItemId}/stock/warmup` | Warm Redis stock from DB |
| `POST /admin/benchmarks/reset` | Reset DB stock, Redis stock, and monthly orders |
| `GET /admin/benchmarks/consistency?ticketItemId=&yearMonth=` | Compare Redis stock, DB stock, and order count |

Admin benchmark endpoints are lab controls for local and benchmark runs only. Do not expose reset, warmup, or consistency endpoints in a public production deployment.

Create order request:

```json
{
  "ticketItemId": 4,
  "userId": 42,
  "quantity": 1,
  "strategy": "REDIS_LUA_WITH_COMPENSATION",
  "idempotencyKey": "user-42-run-1"
}
```

Strategies:

| Strategy | Correctness | Performance | Complexity | Interview Talking Point |
|---|---|---|---|---|
| `UNSAFE_DB` | Intentionally unsafe and can oversell | Fast | Low | Demo-only baseline that proves why unconditional stock updates fail under load |
| `CONDITIONAL_DB` | No oversell | Lower peak throughput | Low | Uses `WHERE stock_available >= quantity` as the simplest safe baseline |
| `REDIS_LUA` | Redis gate prevents excess accepts, but DB failure can cause Redis-DB drift | High | Medium | Shows Redis as a fast pre-deduction gate |
| `REDIS_LUA_WITH_COMPENSATION` | No oversell and compensates Redis on DB/order failure | High | Medium | Shows the practical consistency rule for Redis-first ordering |

## Smoke Flow

```bash
curl -X POST http://localhost:1122/admin/benchmarks/reset ^
  -H "Content-Type: application/json" ^
  -d "{\"ticketItemId\":4,\"stock\":1000,\"yearMonth\":\"202604\"}"

curl -X POST http://localhost:1122/admin/tickets/4/stock/warmup

curl -X POST http://localhost:1122/orders ^
  -H "Content-Type: application/json" ^
  -d "{\"ticketItemId\":4,\"userId\":42,\"quantity\":1,\"strategy\":\"REDIS_LUA_WITH_COMPENSATION\",\"idempotencyKey\":\"smoke-1\"}"

curl "http://localhost:1122/admin/benchmarks/consistency?ticketItemId=4&yearMonth=202604"
```

## Benchmark Scenarios

Run each scenario with the same initial DB stock and warmed Redis stock.

| Scenario | Strategy | Total Requests | Concurrency | Initial Stock | Expected Result |
|---|---|---:|---:|---:|---|
| Baseline unsafe | `UNSAFE_DB` | 5,000 | 100 | 1,000 | Demonstrates oversell risk |
| DB guarded | `CONDITIONAL_DB` | 5,000 | 100 | 1,000 | No oversell, lower throughput |
| Redis gate | `REDIS_LUA` | 5,000 | 100 | 1,000 | Fast rejection, possible inconsistency if DB fails |
| Redis compensated | `REDIS_LUA_WITH_COMPENSATION` | 5,000 | 100 | 1,000 | No oversell, inconsistency repaired |
| Stress | `REDIS_LUA_WITH_COMPENSATION` | 20,000 | 300 | 5,000 | Stable P95/P99 and zero oversell |

Result table format:

| Date | Machine | Strategy | Total Requests | Concurrency | Throughput req/s | Avg ms | P95 ms | P99 ms | Success Orders | Failed Orders | Oversold Count | Redis Stock After | DB Stock After | DB Order Count | Redis-DB Inconsistency Count |
|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| TODO | TODO | TODO | TODO | TODO | TODO | TODO | TODO | TODO | TODO | TODO | TODO | TODO | TODO | TODO | TODO |

## Portfolio Framing

Use this framing on a junior backend CV:

> Built a Spring Boot flash-sale ticketing lab comparing DB conditional updates, Redis Lua gating, and Redis-DB compensation under concurrent load, with benchmark reports and consistency checks.

Do not present this as a complete ticketing product. It deliberately excludes frontend, payment, microservices, Kubernetes, and message queues.
