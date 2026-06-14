# Benchmarking And Lab Operations

This guide covers local setup, smoke testing, JMeter benchmarking, result artifacts, and benchmark interpretation.

## Prerequisites

- Java 21
- Maven
- Docker Desktop or compatible Docker engine
- PowerShell for benchmark scripts on Windows
- JMeter distribution under `benchmark/jmeter`

## Local Services

Start MySQL, Redis, and Kafka:

```bash
docker compose -f environment/docker-compose-dev.yml up -d
```

Default services:

| Service | URL or port |
|---|---|
| Backend | `http://localhost:1122` |
| Swagger UI | `http://localhost:1122/swagger-ui.html` |
| MySQL | `localhost:3316` |
| Redis | `localhost:6319` |
| Kafka | `localhost:9094` |
| Prometheus endpoint | `http://localhost:1122/actuator/prometheus` |

Optional observability profile:

```bash
docker compose -f environment/docker-compose-dev.yml --profile observability up -d
```

## Backend Commands

Build:

```bash
mvn -pl app/backend/xxxx-start -am -DskipTests package
```

Run:

```bash
java -jar app/backend/xxxx-start/target/xxxx-start-1.0-SNAPSHOT.jar
```

Normal tests:

```bash
mvn -pl app/backend/xxxx-start -am test
```

Docker-gated integration test:

```bash
mvn -pl app/backend/xxxx-start -am "-Dflashsale.integration=true" test
```

## Default Experiment Contract

The machine-readable contract is [../../benchmark/experiment-spec.json](../../benchmark/experiment-spec.json).

| Field | Default |
|---|---|
| `baseUrl` | `http://localhost:1122` |
| `ticketItemId` | `4` |
| `stock` | `1000` |
| `yearMonth` | current `yyyyMM` |
| `quantityPerOrder` | `1` |
| `totalRequests` | `5000` |
| `concurrency` | `100` |
| default strategy | `REDIS_LUA_WITH_COMPENSATION` |

The standard cycle is:

```text
Reset fixture -> Warm Redis -> Run requests -> Check consistency -> Save evidence
```

## Smoke Test

```powershell
powershell -ExecutionPolicy Bypass -File benchmark/smoke-local.ps1
```

Parameters:

| Parameter | Default |
|---|---|
| `BaseUrl` | `http://localhost:1122` |
| `TicketItemId` | `4` |
| `Stock` | `1000` |
| `YearMonth` | current `yyyyMM` |
| `Strategy` | `REDIS_LUA_WITH_COMPENSATION` |

The smoke script resets the benchmark fixture, warms Redis, submits one order, and reads a consistency snapshot.

## JMeter Benchmark

```powershell
powershell -ExecutionPolicy Bypass -File benchmark/run-jmeter.ps1 `
  -Strategy REDIS_LUA_WITH_COMPENSATION `
  -Threads 100 `
  -TotalRequests 5000 `
  -Stock 1000
```

Parameters:

| Parameter | Default | Meaning |
|---|---|---|
| `BaseUrl` | `http://localhost:1122` | Backend base URL |
| `TicketItemId` | `4` | Fixture ticket item |
| `Stock` | `1000` | DB and Redis reset stock |
| `YearMonth` | current `yyyyMM` | Monthly order table suffix |
| `Strategy` | `REDIS_LUA_WITH_COMPENSATION` | Order strategy |
| `Threads` | `100` | JMeter thread count |
| `TotalRequests` | `5000` | Desired request count |
| `JMeterBin` | `.\benchmark\jmeter\bin\jmeter.bat` | Local JMeter runner |

The script calculates:

```text
loops = ceil(TotalRequests / Threads)
actualRequests = loops * Threads
```

`actualRequests` can be slightly higher than `TotalRequests` when the values do not divide evenly.

## Result Artifacts

Each benchmark run writes:

```text
benchmark/results/{Strategy}-{yyyyMMdd-HHmmss}/
  reset.json
  warmup.json
  results.jtl
  html/index.html
  consistency.json
  run.json
  summary-row.md
```

The backend reads saved `run.json` files from `${BENCHMARK_RESULTS_DIR:benchmark/results}` through:

```text
GET /admin/benchmarks/runs
GET /admin/benchmarks/runs/{runId}
```

The dashboard benchmark page uses the same APIs.

## Result Table

Use this table shape when publishing local benchmark evidence:

| Date | Machine | Strategy | Total Requests | Concurrency | Throughput req/s | Avg ms | P95 ms | P99 ms | Success Orders | Failed Orders | Oversold Count | Redis Stock After | DB Stock After | DB Order Count | Redis-DB Inconsistency Count |
|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 2026-04-27 | ACER | `REDIS_LUA_WITH_COMPENSATION` | 5000 | 100 | 354.33 | 219.35 | 477 | 516 | 1000 | 4000 | 0 | 0 | 0 | 1000 | 0 |

## Latest Local Benchmark Evidence

Environment for the latest local run:

| Field | Value |
|---|---|
| Date | 2026-05-31 |
| Machine | ACER |
| Backend start path | `mvn -pl app/backend/xxxx-start -am -DskipTests package`, then `java -jar app/backend/xxxx-start/target/xxxx-start-1.0-SNAPSHOT.jar` |
| Dependencies | `docker compose -f environment/docker-compose-dev.yml up -d mysql redis` |
| Fixture | `ticketItemId=4`, `stock=1000`, `yearMonth=202605` |
| Workload | `TotalRequests=5000`, `Threads=100`, `quantityPerOrder=1` |

Required artifacts exist for each run: `reset.json`, `warmup.json`, `results.jtl`, `html/index.html`, `consistency.json`, `run.json`, and `summary-row.md`.

| Run ID | Strategy | Total Requests | Concurrency | Throughput req/s | Avg ms | P95 ms | P99 ms | Success Orders | Failed Orders | Oversold Count | Redis Stock After | DB Stock After | DB Order Count | Redis-DB Inconsistency Count |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| `UNSAFE_DB-20260531-185255` | `UNSAFE_DB` | 5000 | 100 | 84.71 | 1084.86 | 1778 | 2165 | 5000 | 0 | 4000 | -2278 | -4000 | 5000 | 1 |
| `CONDITIONAL_DB-20260531-185409` | `CONDITIONAL_DB` | 5000 | 100 | 173.08 | 494.35 | 741 | 1049 | 1000 | 4000 | 0 | 0 | 0 | 1000 | 0 |
| `REDIS_LUA-20260531-185452` | `REDIS_LUA` | 5000 | 100 | 226.25 | 361.33 | 829 | 1092 | 1000 | 4000 | 0 | 0 | 0 | 1000 | 0 |
| `REDIS_LUA_WITH_COMPENSATION-20260531-185527` | `REDIS_LUA_WITH_COMPENSATION` | 5000 | 100 | 443.03 | 165.95 | 492 | 715 | 1000 | 4000 | 0 | 0 | 0 | 1000 | 0 |

Interpretation for the latest run:

- `UNSAFE_DB` confirms the Phase 2 race-condition baseline: all 5000 requests created orders against stock 1000, producing `oversoldCount = 4000`.
- `CONDITIONAL_DB` confirms the atomic database check-and-deduct pattern: 1000 orders succeeded, 4000 were rejected, and `oversoldCount = 0`.
- `REDIS_LUA` completed without drift on this healthy path, but it still lacks compensation for DB/order failures.
- `REDIS_LUA_WITH_COMPENSATION` is the fastest measured strategy in this local run and finished with both `oversoldCount = 0` and `redisDbInconsistencyCount = 0`.
- Treat these as local-machine benchmark results. Re-run the matrix before publishing current performance claims for another machine or environment.

Interpretation rules:

- Safe strategies should finish with `oversoldCount = 0`.
- `REDIS_LUA_WITH_COMPENSATION` should finish with `redisDbInconsistencyCount = 0` on healthy completed runs.
- `CONDITIONAL_DB` can show Redis/DB mismatch if Redis was warmed but the strategy did not use Redis.
- Failed orders are expected when attempts exceed stock.
- Local throughput numbers are directional; rerun on the target machine before making performance claims.

## Manual Lab Cycle

Reset:

```bash
curl -X POST http://localhost:1122/admin/benchmarks/reset ^
  -H "Content-Type: application/json" ^
  -d "{\"ticketItemId\":4,\"stock\":1000,\"yearMonth\":\"202604\"}"
```

Warm Redis:

```bash
curl -X POST http://localhost:1122/admin/tickets/4/stock/warmup
```

Create one order:

```bash
curl -X POST http://localhost:1122/orders ^
  -H "Content-Type: application/json" ^
  -d "{\"ticketItemId\":4,\"userId\":42,\"quantity\":1,\"strategy\":\"REDIS_LUA_WITH_COMPENSATION\",\"idempotencyKey\":\"manual-1\"}"
```

Check consistency:

```bash
curl "http://localhost:1122/admin/benchmarks/consistency?ticketItemId=4&yearMonth=202604"
```

Force reconciliation:

```bash
curl -X POST "http://localhost:1122/admin/benchmarks/reconcile?ticketItemId=4&yearMonth=202604"
```

## Frontend Dashboard

```bash
cd app/frontend
npm install
copy .env.local.example .env.local
npm run dev
```

The frontend proxy uses `BACKEND_BASE_URL`, defaulting to `http://localhost:1122`.

Verification:

```bash
npm run lint
npm run typecheck
npm run build
```

## Troubleshooting

| Symptom | Check |
|---|---|
| Docker connection error | Start Docker Desktop before Docker Compose or integration tests |
| `/orders` returns Redis stock unavailable | Run reset and warmup; confirm Redis is running |
| Kafka connection refused | Confirm Kafka is running in dev compose; check `KAFKA_BOOTSTRAP_SERVERS` |
| Outbox backlog growing | Check Kafka connectivity; inspect `outbox.backlog.pending` metric |
| high `redisDbInconsistencyCount` | Check strategy context, then run manual reconcile |
| `oversoldCount > 0` | Confirm whether `UNSAFE_DB` was used; reset before the next run |
| Swagger UI missing | Confirm `springdoc-openapi-starter-webmvc-ui` is on the `xxxx-start` classpath |
| no benchmark runs in dashboard | Run `benchmark/run-jmeter.ps1` or inspect `benchmark/results` |
