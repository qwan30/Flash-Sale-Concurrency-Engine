# Documentation Hub

Flash-Sale-Concurrency-Engine is a Spring Boot backend reliability lab for stock deduction under concurrent flash-sale load. The ticket domain is the fixture; the project is not a full ticket-sales product.

Use this hub to reach the current source-backed docs. Java source, Maven files, runtime config, frontend manifests, benchmark scripts, and test files are canonical when docs drift.

## Reading Paths

| Role | Start Here | Then Read |
|---|---|---|
| Reviewer or interviewer | [Reviewer Guide](reference/REVIEWER_GUIDE.md) | [Source Status](reference/SOURCE_STATUS.md), [Benchmark Results](performance/BENCHMARK_RESULTS_ANALYSIS.md), [Architecture](architecture/ARCHITECTURE.md) |
| Backend developer | [Architecture](architecture/ARCHITECTURE.md) | [API Reference](reference/API_REFERENCE.md), [Concurrency And Consistency](performance/CONCURRENCY_AND_CONSISTENCY.md) |
| Operator or SRE | [Lab Operations](operations/LAB_OPERATIONS.md) | [Dashboard Guide](operations/DASHBOARD_GUIDE.md), [Release Checklist](operations/RELEASE_CHECKLIST.md) |
| Self-study | [Learning Path Index](process-learn/00-Index-Guide.md) | [Program Overview](process-learn/00-Program-Overview.md), [Progress Tracker](process-learn/00-Progress-Tracker.md) |

## Current Source Status

| Surface | Current State |
|---|---|
| Backend runtime | Java 21, Spring Boot 3.3.5, five Maven modules, app port `1122` |
| API docs | Springdoc 2.6.0, Swagger UI at `/swagger-ui.html`, OpenAPI at `/v3/api-docs`, grouped lab API at `/v3/api-docs/lab-api` |
| Actuator | Exposes `health` and `prometheus`; health details are hidden by default |
| Frontend dashboard | Next.js 16.2.4 and React 19.2.4 in `app/frontend`, optional operator dashboard only |
| Frontend proxy | `/api/backend/*` forwards only allowlisted dashboard backend paths |
| Benchmark contract | `benchmark/experiment-spec.json` drives reset, warmup, JMeter, and consistency checks |
| Verification commands | Backend Maven tests plus frontend lint, typecheck, and build are the standard local gates |

Full source-status detail lives in [reference/SOURCE_STATUS.md](reference/SOURCE_STATUS.md).

## Primary Documentation

| Document | Purpose |
|---|---|
| [reference/SOURCE_STATUS.md](reference/SOURCE_STATUS.md) | Current source, config, dashboard, benchmark, and verification status |
| [reference/REVIEWER_GUIDE.md](reference/REVIEWER_GUIDE.md) | CV-safe project story and proof points |
| [reference/API_REFERENCE.md](reference/API_REFERENCE.md) | HTTP API contract, Swagger/OpenAPI surfaces, response envelope, and examples |
| [architecture/ARCHITECTURE.md](architecture/ARCHITECTURE.md) | Backend modules, request flow, storage/cache boundaries, reconciliation, and dashboard integration |
| [performance/CONCURRENCY_AND_CONSISTENCY.md](performance/CONCURRENCY_AND_CONSISTENCY.md) | Strategy behavior, oversell prevention, Redis drift, compensation, and reconciliation |
| [performance/BENCHMARKING.md](performance/BENCHMARKING.md) | Smoke test, JMeter workflow, benchmark artifacts, interpretation, and troubleshooting |
| [operations/DASHBOARD_GUIDE.md](operations/DASHBOARD_GUIDE.md) | Optional operator dashboard routes, screenshots, and API proxy behavior |
| [operations/RELEASE_CHECKLIST.md](operations/RELEASE_CHECKLIST.md) | Verification checklist before presentation, publication, or commit |

## Supplemental Notes

| Folder | Content |
|---|---|
| [architecture/](architecture/) | Business flow, sequence diagrams, request tracing, and design notes |
| [performance/](performance/) | Benchmark results, stock strategies, and failure/edge-case notes |
| [operations/](operations/) | Local lab operations and dashboard guidance |
| [reference/](reference/) | Source status, reviewer guide, and API reference |
| [process-learn/](process-learn/) | Structured learning program and phase guides |
| [screenshots/](screenshots/) | Dashboard screenshots |
| [learn-image/](learn-image/) | Learning images and visual notes |

Root-level compatibility pages such as [API_REFERENCE.md](API_REFERENCE.md), [BENCHMARKING.md](BENCHMARKING.md), and [PHASE_INDEX.md](PHASE_INDEX.md) exist to keep older links working. The categorized pages above remain canonical.

## Runtime Surface

When running locally:

| Service | URL |
|---|---|
| Backend API | `http://localhost:1122` |
| Swagger UI | `http://localhost:1122/swagger-ui.html` |
| OpenAPI JSON | `http://localhost:1122/v3/api-docs` |
| Grouped lab API JSON | `http://localhost:1122/v3/api-docs/lab-api` |
| Health check | `http://localhost:1122/actuator/health` |
| Prometheus metrics | `http://localhost:1122/actuator/prometheus` |
| Frontend dashboard | `http://localhost:3000` |
| MySQL | `localhost:3316`, database `vetautet` |
| Redis | `localhost:6319` |

## Verification Commands

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

Benchmark smoke:

```powershell
powershell -ExecutionPolicy Bypass -File benchmark/smoke-local.ps1
```

JMeter benchmark:

```powershell
powershell -ExecutionPolicy Bypass -File benchmark/run-jmeter.ps1 -Strategy REDIS_LUA_WITH_COMPENSATION
```

## Documentation Standards

- Source of truth: Java source, Maven files, frontend manifests, benchmark scripts, tests, and runtime config.
- Scope: backend reliability lab, not a complete ticket-sales product.
- Style: present-state and evergreen. Avoid changelog phrasing.
- Benchmark claims: label local-machine numbers as local evidence and rerun on the target machine before publishing performance claims.
