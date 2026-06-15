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
| Backend runtime | Java 21, Spring Boot 3.3.5, five Maven modules, app port `1122`, virtual threads enabled |
| API docs | Springdoc 2.6.0, Swagger UI at `/swagger-ui.html`, OpenAPI at `/v3/api-docs`, grouped lab API at `/v3/api-docs/lab-api` |
| Actuator | Exposes `health`, `prometheus`, and `metrics`; health details are hidden by default |
| Messaging | Apache Kafka 3.9.0 (KRaft mode) with transactional outbox pattern for at-least-once event publishing |
| CI/CD | CI runs unit tests, integration tests, observability smoke, frontend checks, and infra validation; CD builds and pushes Docker images to GHCR |
| Frontend dashboard | Next.js 16.2.4 and React 19.2.4 in `app/frontend`, optional operator dashboard with E2E Playwright test suite |
| Frontend proxy | `/api/backend/*` forwards only allowlisted dashboard backend paths |
| Benchmark contract | `benchmark/experiment-spec.json` drives reset, warmup, JMeter, and consistency checks |
| Verification commands | Backend Maven tests plus frontend lint, typecheck, and build are the standard local gates |

Full source-status detail lives in [reference/SOURCE_STATUS.md](reference/SOURCE_STATUS.md).

## Primary Documentation

### ★ Foundation & Controls
| Document | Purpose |
|---|---|
| [00-overview/project-foundation.md](00-overview/project-foundation.md) | ★ **Source of Truth** — all technical standards in one file |
| [00-overview/documentation-index.md](00-overview/documentation-index.md) | Complete document map with traceability matrix |
| [00-overview/project-context.md](00-overview/project-context.md) | Project goals, constraints, assumptions, open questions |

### Architecture & Design
| Document | Purpose |
|---|---|
| [architecture/ARCHITECTURE.md](architecture/ARCHITECTURE.md) | System overview, runtime stack, order flow, storage model |
| [04-architecture/domain-driven-design.md](04-architecture/domain-driven-design.md) | Bounded contexts, aggregates, domain events, ubiquitous language |
| [04-architecture/coding-standards.md](04-architecture/coding-standards.md) | Java conventions, naming, DI, error handling, Lombok usage |
| [04-architecture/resilience-patterns.md](04-architecture/resilience-patterns.md) | Rate limiter, circuit breaker, distributed lock, outbox retry |
| [04-architecture/adr/](04-architecture/adr/) | 3 Architecture Decision Records (Kafka Outbox, Strategy Pattern, Redis Gate) |

### API & Database
| Document | Purpose |
|---|---|
| [reference/API_REFERENCE.md](reference/API_REFERENCE.md) | HTTP API contract, envelope, order/ticket/admin endpoints, examples |
| [06-database/db-schema.md](06-database/db-schema.md) | Table definitions, indexes, key queries, concurrency controls |
| [06-database/erd.md](06-database/erd.md) | Mermaid entity relationship diagram |

### Concurrency & Benchmarking
| Document | Purpose |
|---|---|
| [performance/CONCURRENCY_AND_CONSISTENCY.md](performance/CONCURRENCY_AND_CONSISTENCY.md) | Strategy behavior, oversell prevention, drift, compensation, reconciliation |
| [performance/BENCHMARKING.md](performance/BENCHMARKING.md) | Smoke test, JMeter workflow, artifacts, interpretation, troubleshooting |
| [performance/STOCK_STRATEGIES.md](performance/STOCK_STRATEGIES.md) | Strategy implementation details |
| [performance/BENCHMARK_RESULTS_ANALYSIS.md](performance/BENCHMARK_RESULTS_ANALYSIS.md) | Latest benchmark evidence with interpretation |

### Flows & State Machines
| Document | Purpose |
|---|---|
| [07-flows/end-to-end-business-flow.md](07-flows/end-to-end-business-flow.md) | Complete order → outbox → reconciliation flow across all layers |
| [07-flows/state-machine.md](07-flows/state-machine.md) | OutboxEvent, Stock, Benchmark, Reconciliation state machines |
| [architecture/BUSINESS_FLOW.md](architecture/BUSINESS_FLOW.md) | Business-level event/order flow |
| [architecture/SEQUENCE_DIAGRAMS.md](architecture/SEQUENCE_DIAGRAMS.md) | Key interaction sequences |

### Deployment & Operations
| Document | Purpose |
|---|---|
| [10-deployment/ci-cd.md](10-deployment/ci-cd.md) | CI/CD pipeline reference (6 CI jobs + 2 CD jobs) |
| [10-deployment/docker.md](10-deployment/docker.md) | Docker Compose profiles (dev, observability, ELK, production) |
| [10-deployment/env-variables.md](10-deployment/env-variables.md) | Complete environment variable reference with port map |
| [operations/LAB_OPERATIONS.md](operations/LAB_OPERATIONS.md) | Local lab operations guide |
| [operations/DASHBOARD_GUIDE.md](operations/DASHBOARD_GUIDE.md) | Operator dashboard routes, proxy behavior, verification |
| [operations/OBSERVABILITY.md](operations/OBSERVABILITY.md) | Monitoring, metrics, tracing setup |
| [operations/RELEASE_CHECKLIST.md](operations/RELEASE_CHECKLIST.md) | Pre-release verification checklist |

### Reference
| Document | Purpose |
|---|---|
| [reference/SOURCE_STATUS.md](reference/SOURCE_STATUS.md) | Current source-backed project status |
| [reference/REVIEWER_GUIDE.md](reference/REVIEWER_GUIDE.md) | CV-safe project story and proof points |
| [01-business/glossary.md](01-business/glossary.md) | 60+ standardized domain terms (ubiquitous language) |

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
| Actuator metrics | `http://localhost:1122/actuator/metrics` |
| Frontend dashboard | `http://localhost:3000` |
| MySQL | `localhost:3316`, database `vetautet` |
| Redis | `localhost:6319` |
| Kafka | `localhost:9094` |

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

Frontend E2E browser tests (requires backend + frontend running):

```bash
cd app/frontend
npm run test:e2e
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
