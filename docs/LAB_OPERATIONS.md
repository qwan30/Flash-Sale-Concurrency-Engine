# Lab Operations

This guide covers local setup, smoke tests, benchmark execution, and release verification.

## Prerequisites

- Java 21
- Maven
- Docker Desktop or compatible Docker engine
- PowerShell for benchmark scripts on Windows
- JMeter distribution under `benchmark/jmeter`

## Local Services

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
| Prometheus endpoint | `http://localhost:1122/actuator/prometheus` |

Optional observability profile:

```bash
docker compose -f environment/docker-compose-dev.yml --profile observability up -d
```

## Backend Commands

Build:

```bash
mvn -q -DskipTests install
```

Run:

```bash
mvn -pl app/backend/xxxx-start -am spring-boot:run -DskipTests
```

Normal tests:

```bash
mvn -pl app/backend/xxxx-start -am test
```

Docker-gated integration test:

```bash
mvn -pl app/backend/xxxx-start -am "-Dflashsale.integration=true" test
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
| `BaseUrl` | `http://localhost:1122` | backend base URL |
| `TicketItemId` | `4` | fixture ticket item |
| `Stock` | `1000` | DB and Redis reset stock |
| `YearMonth` | current `yyyyMM` | monthly order table suffix |
| `Strategy` | `REDIS_LUA_WITH_COMPENSATION` | order strategy |
| `Threads` | `100` | JMeter thread count |
| `TotalRequests` | `5000` | desired request count |
| `JMeterBin` | `.\benchmark\jmeter\bin\jmeter.bat` | local JMeter runner |

The script calculates `loops = ceil(TotalRequests / Threads)`, so actual request count can be slightly above `TotalRequests` when the values do not divide evenly.

Output folder:

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

The backend `GET /admin/benchmarks/runs` API reads saved `run.json` files from the configured `benchmark.results-dir`.

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
| high `redisDbInconsistencyCount` | Check strategy context, then run manual reconcile |
| `oversoldCount > 0` | Confirm whether `UNSAFE_DB` was used; reset before next run |
| Swagger UI missing | Confirm `springdoc-openapi-starter-webmvc-ui` is on `xxxx-start` classpath |
| no benchmark runs in dashboard | Run `benchmark/run-jmeter.ps1` or inspect `benchmark/results` |
