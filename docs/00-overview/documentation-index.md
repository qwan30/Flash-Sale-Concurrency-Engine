# Documentation Index — Flash-Sale Concurrency Engine

> **Version:** 1.0.0 | **Generated:** 2026-06-14 | **Source of Truth:** Java source, Maven, config, scripts, tests

## Complete Document Map

```text
docs/
├── index.html                           ★ Single-page consolidated documentation
├── README.md                            Documentation hub (reading paths, runtime surface)
│
├── 00-overview/                         ← PROJECT FOUNDATION & CONTROLS
│   ├── project-foundation.md            ★ Source of Truth — all technical standards
│   └── documentation-index.md           ★ This file — complete document map & traceability
│
├── 01-business/                         ← BUSINESS & DOMAIN
│   └── glossary.md                      Standardized domain terminology
│
├── 04-architecture/                     ← ARCHITECTURE & DESIGN
│   ├── domain-driven-design.md          DDD bounded contexts, aggregates, domain events
│   ├── coding-standards.md              Java coding conventions (derived from project-foundation)
│   ├── resilience-patterns.md           Resilience4j timeout/retry/CB/distributed-lock policies
│   └── adr/                             Architecture Decision Records
│       └── adr-template.md              ADR template
│
├── 06-database/                         ← DATABASE
│   └── db-schema.md                     Table definitions, constraints, indexes, seed data
│
├── 07-flows/                            ← BUSINESS FLOWS
│   └── end-to-end-business-flow.md      Complete order → outbox → reconciliation flow
│
├── 10-deployment/                       ← DEPLOYMENT & INFRA
│   ├── ci-cd.md                         CI/CD pipeline reference
│   ├── docker.md                        Docker Compose profiles, images, production deployment
│   └── env-variables.md                 Complete environment variable reference
│
├── reference/                           ← CANONICAL REFERENCE (authoritative)
│   ├── SOURCE_STATUS.md                 Current source-backed project status
│   ├── API_REFERENCE.md                 HTTP API contract, envelope, examples
│   └── REVIEWER_GUIDE.md                CV-safe project story & proof points
│
├── architecture/                        ← ARCHITECTURE (primary docs)
│   ├── ARCHITECTURE.md                  System overview, runtime stack, order flow
│   ├── BUSINESS_FLOW.md                 Business-level event/order flow
│   ├── DESIGN.md                        Design notes and decisions
│   ├── SEQUENCE_DIAGRAMS.md             Key interaction sequences
│   └── REQUEST_RESPONSE_TRACING.md      Trace-level request/response flow
│
├── performance/                         ← CONCURRENCY & BENCHMARKING
│   ├── CONCURRENCY_AND_CONSISTENCY.md   Strategy behavior, drift, compensation, reconciliation
│   ├── BENCHMARKING.md                  Smoke test, JMeter workflow, artifacts, troubleshooting
│   ├── STOCK_STRATEGIES.md              Strategy implementation details
│   ├── ERRORS_AND_EDGE_CASES.md         Failure modes and edge cases
│   └── BENCHMARK_RESULTS_ANALYSIS.md    Latest benchmark evidence & interpretation
│
├── operations/                          ← OPERATIONS
│   ├── LAB_OPERATIONS.md                Local lab operations guide
│   ├── DASHBOARD_GUIDE.md               Operator dashboard routes, proxy, verification
│   ├── RELEASE_CHECKLIST.md             Pre-release verification checklist
│   └── OBSERVABILITY.md                 Monitoring, metrics, tracing setup
│
└── process-learn/                       ← LEARNING PROGRAM
    └── (Structured phase guides & learning path)
```

---

## Traceability Matrix

| ID | Type | Description | Primary Doc | Source |
|---|---|---|---|---|
| BO-001 | Objective | Prove stock deduction correctness under concurrent load | `project-foundation.md` | Code |
| BO-002 | Objective | Compare 4 stock strategies with measurable benchmark evidence | `performance/BENCHMARK_RESULTS_ANALYSIS.md` | JMeter |
| F-001 | Feature | Stock Deduction — 4 strategies with Strategy Registry | `architecture/ARCHITECTURE.md` | `StockDeductionStrategy.java` |
| F-002 | Feature | Order Creation with idempotency | `reference/API_REFERENCE.md` | `OrderCreationService.java` |
| F-003 | Feature | Consistency Check — Redis vs DB drift snapshot | `performance/CONCURRENCY_AND_CONSISTENCY.md` | `ConsistencyCheckService.java` |
| F-004 | Feature | Reconciliation — scheduled & manual drift repair | `performance/CONCURRENCY_AND_CONSISTENCY.md` | `OrderReconciliationService.java` |
| F-005 | Feature | Benchmark Runner — JMeter integration | `performance/BENCHMARKING.md` | `benchmark/run-jmeter.ps1` |
| F-006 | Feature | Transactional Outbox → Kafka — at-least-once events | `architecture/ARCHITECTURE.md` | `OutboxService.java` |
| F-007 | Feature | Operator Dashboard | `operations/DASHBOARD_GUIDE.md` | `app/frontend/` |
| NFR-001 | NFR | `oversoldCount = 0` for safe strategies | `performance/CONCURRENCY_AND_CONSISTENCY.md` | `ConsistencyCheckService.java` |
| NFR-002 | NFR | `redisDbInconsistencyCount = 0` for REDIS_LUA_WITH_COMPENSATION | `performance/CONCURRENCY_AND_CONSISTENCY.md` | `ConsistencyCheckService.java` |
| NFR-003 | NFR | At-least-once event delivery (outbox retry) | `project-foundation.md` §9 | `OutboxService.java` |

---

## Reading Paths

| Role | Recommended Order |
|---|---|
| **New Developer** | `project-foundation.md` → `documentation-index.md` → `architecture/ARCHITECTURE.md` → `reference/SOURCE_STATUS.md` → `reference/API_REFERENCE.md` |
| **Code Reviewer** | `reference/REVIEWER_GUIDE.md` → `reference/SOURCE_STATUS.md` → `performance/BENCHMARK_RESULTS_ANALYSIS.md` → `architecture/ARCHITECTURE.md` |
| **Operator / SRE** | `operations/LAB_OPERATIONS.md` → `operations/DASHBOARD_GUIDE.md` → `performance/BENCHMARKING.md` → `operations/RELEASE_CHECKLIST.md` |
| **Architect** | `project-foundation.md` → `architecture/ARCHITECTURE.md` → `04-architecture/domain-driven-design.md` → `04-architecture/resilience-patterns.md` → `performance/CONCURRENCY_AND_CONSISTENCY.md` |
| **Self-Study** | `process-learn/00-Index-Guide.md` → phase guides in order |

---

## Document Control

| Version | Date | Author | Changes |
|---|---|---|---|
| 1.0.0 | 2026-06-14 | AI-assisted (CodeGraph + universal-docs-generator template) | Initial structured index; traceability matrix; reading paths |
