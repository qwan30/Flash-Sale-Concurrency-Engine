# Source Status

This page records the current source-backed project status. It is grounded in the Maven root, backend controllers, runtime configuration, frontend package and proxy code, benchmark scripts, tests, and current docs topology.

## Status Summary

| Area | Current Status | Canonical Source |
|---|---|---|
| Project type | Backend flash-sale concurrency lab, not a full ticket-sales product | `README.md`, `docs/reference/REVIEWER_GUIDE.md` |
| Backend modules | Five Maven modules: domain, application, infrastructure, controller, start | `pom.xml` |
| Backend runtime | Java 21, Spring Boot 3.3.5, app port `1122` | `pom.xml`, `app/backend/xxxx-start/src/main/resources/application.yml` |
| API documentation | Swagger UI and OpenAPI are configured through Springdoc 2.6.0 | `pom.xml`, `OpenApiConfig.java`, `application.yml` |
| Actuator exposure | Only `health` and `prometheus` are exposed; health details are hidden | `application.yml` |
| Order strategies | `UNSAFE_DB`, `CONDITIONAL_DB`, `REDIS_LUA`, `REDIS_LUA_WITH_COMPENSATION` | `OrderStrategy.java` |
| Frontend dashboard | Optional Next.js 16.2.4 operator dashboard, not a consumer app | `app/frontend/package.json`, `app/frontend/src/app` |
| Frontend proxy | Browser calls go through `/api/backend/*` and are restricted to known dashboard backend paths | `app/frontend/src/app/api/backend/[...path]/route.ts` |
| Benchmark workflow | Reset, warmup, JMeter run, consistency check, saved run manifest | `benchmark/experiment-spec.json`, `benchmark/run-jmeter.ps1` |
| Local dependencies | MySQL and Redis by default; Prometheus/Grafana and ELK are Docker Compose profiles | `environment/docker-compose-dev.yml` |

## Backend Source Status

The backend is a multi-module Maven project:

| Module | Role |
|---|---|
| `app/backend/xxxx-domain` | Domain services and stock/order invariants |
| `app/backend/xxxx-application` | Application services, strategy registry, benchmark run service, DTO models |
| `app/backend/xxxx-infrastructure` | MySQL repositories, Redis adapters, persistence support |
| `app/backend/xxxx-controller` | HTTP controllers and API DTO boundaries |
| `app/backend/xxxx-start` | Spring Boot entrypoint, OpenAPI config, runtime config, integration tests |

The backend starts on `http://localhost:1122`. MySQL defaults to `localhost:3316/vetautet`; Redis defaults to `127.0.0.1:6319`. Environment variables in `.env.example` and `application.yml` override the local defaults.

## API Surface

Modern lab APIs:

| Route | Purpose |
|---|---|
| `POST /orders` | Create an order with a selected stock strategy |
| `GET /orders` | List user orders by `userId` and `yearMonth` |
| `GET /orders/{orderNumber}` | Read one order trace |
| `GET /tickets/{ticketItemId}` | Read a fixture ticket item |
| `POST /admin/tickets/{ticketItemId}/stock/warmup` | Warm Redis stock from DB stock |
| `POST /admin/benchmarks/reset` | Reset DB stock, Redis stock, monthly orders, and idempotency cache |
| `GET /admin/benchmarks/consistency` | Compare Redis stock, DB stock, order count, oversold rows, and drift |
| `POST /admin/benchmarks/reconcile` | Set Redis stock back to DB truth when drift exists |
| `GET /admin/benchmarks/runs` | List saved benchmark run manifests |
| `GET /admin/benchmarks/runs/{runId}` | Read one saved benchmark run manifest |

Compatibility and local utility APIs remain in source for old benchmark plans and experiments:

| Route Family | Status |
|---|---|
| `/order/...` | Compatibility routes mapped to modern order behavior |
| `/ticket/...` | Legacy fixture-detail routes and local latency ping |
| `/hello/...` | Local hello, circuit-breaker, and rate-limiter experiments |

Admin benchmark endpoints are local lab controls. They are not app-auth protected and should not be exposed as public buyer-facing APIs.

## Runtime And Observability Status

The default actuator web exposure is intentionally narrow:

| Route | Status |
|---|---|
| `GET /actuator/health` | Exposed with hidden component details |
| `GET /actuator/prometheus` | Exposed for Prometheus scraping |

Other actuator endpoints are not exposed by default.

`environment/docker-compose-dev.yml` provides MySQL and Redis for the normal local lab. Optional profiles add observability or ELK components:

| Profile | Services |
|---|---|
| default | MySQL, Redis |
| `observability` | Prometheus, Grafana, node exporter, MySQL exporter, Redis exporter |
| `elk` | Elasticsearch, Logstash, Kibana |

## Frontend Source Status

The frontend is an optional operator dashboard under `app/frontend`.

| Surface | Current Source |
|---|---|
| Framework | Next.js 16.2.4, React 19.2.4 |
| Local backend origin | `BACKEND_BASE_URL`, default `http://localhost:1122` |
| API client | `app/frontend/src/lib/api.ts` |
| Backend proxy route | `app/frontend/src/app/api/backend/[...path]/route.ts` |
| Main routes | `/`, `/events`, `/events/{ticketItemId}`, `/booking`, `/my-orders`, `/orders/{orderNumber}`, `/admin/control-desk`, `/admin/benchmark`, `/admin/consistency` |

The proxy forwards only these dashboard backend paths:

- `GET /actuator/health`
- `GET /tickets/{ticketItemId}`
- `POST /admin/benchmarks/reset`
- `POST /admin/tickets/{ticketItemId}/stock/warmup`
- `GET /admin/benchmarks/consistency`
- `GET /admin/benchmarks/runs`
- `GET /admin/benchmarks/runs/{runId}`
- `POST /orders`
- `GET /orders`
- `GET /orders/{orderNumber}`

`POST /admin/benchmarks/reconcile` exists in the backend API but is not currently exposed through the dashboard proxy because no dashboard flow calls it.

## Benchmark Source Status

The benchmark workflow is source-controlled under `benchmark/`.

| File | Role |
|---|---|
| `experiment-spec.json` | Machine-readable fixture, workload, strategy expectations, and artifact shape |
| `smoke-local.ps1` | Reset, warmup, one order, and consistency smoke flow |
| `run-jmeter.ps1` | Full JMeter run wrapper with reset, warmup, consistency check, and saved summary |
| `flash-sale-order.jmx` | JMeter plan |

Default benchmark parameters are `ticketItemId=4`, stock `1000`, total requests `5000`, concurrency `100`, and strategy `REDIS_LUA_WITH_COMPENSATION` unless overridden.

Each benchmark run writes to `benchmark/results/{strategy}-{yyyyMMdd-HHmmss}` with:

- `reset.json`
- `warmup.json`
- `results.jtl`
- `html/index.html`
- `consistency.json`
- `run.json`
- `summary-row.md`

## Verification Status

Use these commands as the current local verification surface.

Backend:

```bash
mvn -pl app/backend/xxxx-start -am test
```

Docker-gated integration tests:

```bash
mvn -pl app/backend/xxxx-start -am "-Dflashsale.integration=true" test
```

Frontend:

```bash
cd app/frontend
npm run lint
npm run typecheck
npm run build
```

Docs do not currently provide a checked-in link-check command. After documentation edits, run a local Markdown/image link scan or add a repo script before treating docs link validation as repeatable automation. External URLs and runtime URLs still require the corresponding service to be running.

## Known Boundaries

- The project is a backend reliability lab, not a complete ticket sales, marketplace, or e-commerce product.
- Admin benchmark endpoints are local controls and do not implement product-grade authentication or authorization.
- Payment, account lifecycle, buyer checkout, fulfillment, notifications, and post-order workflows are outside the current source scope.
- The dashboard is an operator surface. It should stay focused on reset, warmup, order probes, benchmark results, health, and consistency.
- Generated HTML visualizers are not canonical documentation unless they are explicitly regenerated and linked from the docs hub.
