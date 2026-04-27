# Flash-Sale Concurrency Backend Lab

This repository is a Spring Boot backend reliability lab for proving inventory deduction behavior under concurrent flash-sale load. The core story is correctness first: stock must not oversell, Redis/Lua behavior must be explainable, database state must stay consistent, and benchmark results must be reproducible.

The ticket domain is only the test fixture. The project is not positioned as a complete ticket sales platform.

## What This Project Proves

- Naive stock updates can oversell under load.
- MySQL conditional updates provide a simple safe baseline.
- Redis/Lua can reject excess demand quickly before hitting the database.
- Redis-first strategies need compensation rules when database/order writes fail.
- Consistency checks can compare Redis stock, DB stock, order count, oversold rows, and drift after each run.
- JMeter results are only useful when the reset, warmup, workload, and result table are reproducible.

## Intentionally Out Of Scope

- Buyer-facing product flows, account management, gateway integrations, and post-order business workflows.
- Production hardening of public admin endpoints.
- Microservices, Kubernetes, message queues, and distributed workflow orchestration.
- A full frontend application. Any UI in this repository is an operator dashboard for lab setup, benchmark review, stock consistency, and system health.

## Repository Layout

| Path | Purpose |
|---|---|
| `app/backend/xxxx-domain`, `app/backend/xxxx-application`, `app/backend/xxxx-infrastructure`, `app/backend/xxxx-controller`, `app/backend/xxxx-start` | Spring Boot backend modules |
| `app/frontend` | Optional operator dashboard for lab controls and result inspection |
| `benchmark` | JMeter plans, smoke scripts, and reproducibility assets |
| `environment` | Local Docker dependencies and database bootstrap |
| `docs` | Supporting design notes and dashboard screenshots |
| `uml` | Lightweight process diagrams |

## Run Locally

```bash
docker compose -f environment/docker-compose-dev.yml up -d
mvn -q -DskipTests install
mvn -pl app/backend/xxxx-start -am spring-boot:run -DskipTests
```

Docker-gated integration test:

```bash
mvn -pl app/backend/xxxx-start -am "-Dflashsale.integration=true" test
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

## Operator Dashboard Artifacts

- [Design notes](docs/DESIGN.md)
- [Lab overview](docs/screenshots/home.png)
- [Fixture board](docs/screenshots/events.png)
- [Order traces](docs/screenshots/order-traces.png)
- [Control desk](docs/screenshots/admin-control-desk.png)
- [Benchmark report](docs/screenshots/admin-benchmark.png)
- [Consistency view](docs/screenshots/admin-consistency.png)

## Lab API Contract

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

## How To Run And Verify

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

The machine-readable experiment contract is [benchmark/experiment-spec.json](benchmark/experiment-spec.json). Each `benchmark/run-jmeter.ps1` execution writes a run folder under `benchmark/results/` with raw samples, JMeter HTML, a markdown summary row, and `run.json`. The admin benchmark dashboard reads saved runs through `GET /admin/benchmarks/runs`.

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
| 2026-04-27 | ACER | `CONDITIONAL_DB` | 5000 | 100 | 38.64 | 2501.58 | 20590 | 30025 | 1000 | 4000 | 0 | 1000 | 0 | 1000 | 1 |
| 2026-04-27 | ACER | `REDIS_LUA` | 5000 | 100 | 288.33 | 275.13 | 598 | 654 | 1000 | 4000 | 0 | 0 | 0 | 1000 | 0 |
| 2026-04-27 | ACER | `REDIS_LUA_WITH_COMPENSATION` | 5000 | 100 | 354.33 | 219.35 | 477 | 516 | 1000 | 4000 | 0 | 0 | 0 | 1000 | 0 |

Notes:

| Observation | Explanation |
|---|---|
| `CONDITIONAL_DB` shows Redis-DB inconsistency | This strategy intentionally updates DB only. Redis was warmed for measurement, but it is not the deduction gate for this strategy. |
| `REDIS_LUA_WITH_COMPENSATION` was fastest in this local run | Treat local numbers as directional. Rerun benchmarks on the target machine before making claims. |
| Failed orders are expected | Each run starts with stock 1,000 and sends 5,000 order attempts. The safe strategies should accept 1,000 and reject 4,000 without overselling. |

## Portfolio Framing

Use this framing on a junior backend CV:

> Built a Spring Boot flash-sale concurrency lab comparing DB conditional updates, Redis Lua gating, and Redis-DB compensation under load, with benchmark reports and consistency checks.

Do not present this as a complete ticket sales product. The project is a backend reliability lab, not an end-user platform.

## Conclusion

This project is useful when judged as a focused backend lab: it makes the correctness and performance trade-offs of flash-sale stock deduction visible, measurable, and reproducible. The strongest story is not "a ticketing app"; it is "a practical concurrency lab that proves why naive stock updates oversell and how safer Redis/DB strategies behave under load."

Keep future work centered on the backend proof: clearer strategy separation, stronger concurrency tests, reproducible benchmark scripts, consistency checks, and concise architecture documentation. Any UI should stay secondary as an operator dashboard for running and explaining the lab.
