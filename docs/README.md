# Documentation Hub

This folder documents the current Flash-Sale Concurrency Engine as a focused backend reliability lab. The ticket domain is a fixture; the core project value is proving stock correctness under concurrent order load, explaining Redis/MySQL consistency, and making benchmark evidence reproducible.

## Reading Paths

| Reader | Start here | Then read |
|---|---|---|
| Reviewer, interviewer, CV evaluator | [REVIEWER_GUIDE.md](./REVIEWER_GUIDE.md) | [CONCURRENCY_AND_CONSISTENCY.md](./CONCURRENCY_AND_CONSISTENCY.md), [BENCHMARKING.md](./BENCHMARKING.md) |
| Backend developer | [ARCHITECTURE.md](./ARCHITECTURE.md) | [API_REFERENCE.md](./API_REFERENCE.md), [CONCURRENCY_AND_CONSISTENCY.md](./CONCURRENCY_AND_CONSISTENCY.md) |
| Lab operator | [BENCHMARKING.md](./BENCHMARKING.md) | [DASHBOARD_GUIDE.md](./DASHBOARD_GUIDE.md), [RELEASE_CHECKLIST.md](./RELEASE_CHECKLIST.md) |

## Primary Documentation

| Document | Use for |
|---|---|
| [REVIEWER_GUIDE.md](./REVIEWER_GUIDE.md) | Concise project story, proof points, reviewer walkthrough, and CV-safe framing |
| [ARCHITECTURE.md](./ARCHITECTURE.md) | Backend modules, request flow, storage/cache boundaries, reconciliation, and dashboard integration |
| [API_REFERENCE.md](./API_REFERENCE.md) | Current HTTP endpoints, envelope semantics, request/response examples, and Swagger/OpenAPI URLs |
| [CONCURRENCY_AND_CONSISTENCY.md](./CONCURRENCY_AND_CONSISTENCY.md) | Stock strategies, oversell prevention, Redis drift, compensation, reconciliation, and failure semantics |
| [BENCHMARKING.md](./BENCHMARKING.md) | Local setup, smoke tests, JMeter runs, benchmark artifacts, result-table interpretation, and publish rules |
| [DASHBOARD_GUIDE.md](./DASHBOARD_GUIDE.md) | Optional Next.js operator dashboard, screens, screenshots, and API proxy behavior |
| [RELEASE_CHECKLIST.md](./RELEASE_CHECKLIST.md) | Final verification checklist before presenting or publishing the project |

## Supplemental Notes

These files are useful for learning or deeper tracing, but they are not the primary release documentation.

| Document | Use for |
|---|---|
| [REQUEST_RESPONSE_TRACING.md](./REQUEST_RESPONSE_TRACING.md) | Detailed request-to-response walkthroughs |
| [SEQUENCE_DIAGRAMS.md](./SEQUENCE_DIAGRAMS.md) | Expanded sequence diagrams |
| [REDIS_COMPREHENSIVE_GUIDE.md](./REDIS_COMPREHENSIVE_GUIDE.md) | Redis learning notes |
| [LEARNING_JOURNEY.md](./LEARNING_JOURNEY.md) | Session-style learning recap |
| [screenshots/](./screenshots/) | Dashboard screenshots linked from the dashboard guide |
| [learn/](./learn/) | Generated learning images and visual notes |

## Compatibility Pages

The old documentation names still exist as short redirect pages so external or older local links keep working:

| Old page | Replacement |
|---|---|
| [BUSINESS_FLOW.md](./BUSINESS_FLOW.md) | [REVIEWER_GUIDE.md](./REVIEWER_GUIDE.md), [BENCHMARKING.md](./BENCHMARKING.md) |
| [STOCK_STRATEGIES.md](./STOCK_STRATEGIES.md) | [CONCURRENCY_AND_CONSISTENCY.md](./CONCURRENCY_AND_CONSISTENCY.md) |
| [LAB_OPERATIONS.md](./LAB_OPERATIONS.md) | [BENCHMARKING.md](./BENCHMARKING.md) |
| [ERRORS_AND_EDGE_CASES.md](./ERRORS_AND_EDGE_CASES.md) | [CONCURRENCY_AND_CONSISTENCY.md](./CONCURRENCY_AND_CONSISTENCY.md) |
| [DESIGN.md](./DESIGN.md) | [DASHBOARD_GUIDE.md](./DASHBOARD_GUIDE.md) |

## Current Runtime Surface

| Surface | Location |
|---|---|
| Backend app | `http://localhost:1122` |
| Swagger UI | `http://localhost:1122/swagger-ui.html` |
| OpenAPI JSON | `http://localhost:1122/v3/api-docs` |
| Grouped lab API JSON | `http://localhost:1122/v3/api-docs/lab-api` |
| Health check | `http://localhost:1122/actuator/health` |
| Prometheus metrics | `http://localhost:1122/actuator/prometheus` |
| Frontend dashboard | `http://localhost:3000` |

## Source Of Truth Rules

- Java source, Maven files, benchmark scripts, frontend API client code, and runtime config are canonical.
- Keep docs present-state and evergreen; avoid changelog wording.
- Keep admin endpoints framed as local lab controls, not public production APIs.
- Keep the project framed as a backend reliability lab, not a complete ticket-sales product.
- Rerun benchmarks on the target machine before publishing performance numbers.
