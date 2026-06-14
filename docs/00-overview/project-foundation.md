# Project Foundation — Technical Standards

> **Source of Truth.** All architecture, coding, testing, CI/CD, and operations docs derive their standards from this file. Update here first, then propagate.

| Field | Value |
|---|---|
| **Project** | Flash-Sale Concurrency Engine |
| **Type** | Backend reliability lab — stock deduction under concurrent load |
| **Version** | 1.0.0 |
| **Created** | 2026-06-14 |
| **Owner** | qwan30 |

---

## 1. Project Scope

This is a **backend concurrency lab**, not a full ticket-sales product. The ticket domain is the fixture used to prove stock-deduction correctness. The optional Next.js frontend is an operator dashboard only.

**In scope**: stock strategies, order creation, idempotency, consistency checks, reconciliation, benchmarking, Kafka outbox.

**Out of scope**: payment, checkout, fulfillment, notifications, buyer accounts, marketplace features.

---

## 2. Architecture Style

| Decision | Choice | Rationale |
|---|---|---|
| Architecture | **DDD-layered modular monolith** | Single deployable, domain+application+infrastructure+controller+start |
| API style | **REST** with Spring Web MVC | Standard for lab tools and benchmark clients |
| Messaging | **Transactional Outbox → Kafka** | At-least-once delivery for domain events |
| Caching | **Redis as fast gate** (not source of truth) | MySQL is durable truth; Redis gates demand before DB |
| Concurrency | **Virtual threads** (Java 21) | High-throughput request handling without reactive complexity |

### Dependency Rules (layered DDD)

```text
Controller → Application → Domain ← Infrastructure
     ↓            ↓             ↓
   (DTOs)    (Ports/Interfaces)  (JPA entities, Redis, Kafka)
```

- **Domain** has zero framework dependencies.
- **Application** depends on Domain interfaces (ports), never on Infrastructure directly.
- **Infrastructure** implements Domain ports and Application ports.
- **Controller** depends only on Application services.

---

## 3. Technology Stack

| Layer | Technology | Version |
|---|---|---|
| Runtime | Java | 21 |
| Framework | Spring Boot | 3.3.5 |
| Build | Maven (multi-module) | — |
| Database | MySQL | 8.0 |
| Cache | Redis | 7 (Alpine) |
| Messaging | Apache Kafka (KRaft) | 3.9.0 |
| Distributed Lock | Redisson | — |
| API Docs | Springdoc OpenAPI | 2.6.0 |
| Resilience | Resilience4j | — |
| Observability | Micrometer Tracing + Brave | — |
| Frontend | Next.js + React | 16.2.4 / 19.2.4 |
| Testing | JUnit 5, Testcontainers, JMeter | — |
| CI/CD | GitHub Actions, GHCR | — |

---

## 4. Module Layout

| Module | Layer | Responsibility |
|---|---|---|
| `xxxx-domain` | Domain | Entities, repository ports, domain services |
| `xxxx-application` | Application | Orchestration, strategies, idempotency, outbox, DTOs |
| `xxxx-infrastructure` | Infrastructure | JPA mappers, Redis/Redisson adapters, outbox persistence |
| `xxxx-controller` | Controller | REST endpoints, `ResultMessage<T>` envelope |
| `xxxx-start` | Bootstrap | Entrypoint, config, scheduling, integration tests |

---

## 5. Coding Standards

### Java

| Rule | Standard |
|---|---|
| Naming | `camelCase` for methods/vars, `PascalCase` for classes |
| Constants | `UPPER_SNAKE_CASE` |
| Packages | Reverse domain: `com.xxxx.ddd.<layer>` |
| Imports | No wildcard imports |
| Null safety | Prefer `Optional`, avoid returning null from public APIs |
| Mutability | Prefer immutable DTOs; use `@Accessors(chain=true)` on entities only |
| Annotations | Constructor injection over `@Autowired` fields in new code |

### Method & File Limits

| Metric | Limit |
|---|---|
| Method length | < 50 lines |
| File length | < 800 lines |
| Nesting depth | < 4 levels |
| Parameters | < 5 per method |

### Error Handling

| Scenario | Strategy |
|---|---|
| Validation errors | Return `INVALID_REQUEST` result code, HTTP 400 |
| Business rejections | Return domain-specific result code, HTTP 200 with envelope code 409 |
| Runtime exceptions | Let global handler wrap; log with trace context |
| Outbox failures | Retry with backoff; never fail the business transaction |

---

## 6. API Standards

### Response Envelope

All endpoints return `ResultMessage<T>`:

```json
{ "success": true, "message": "success", "code": 200, "timestamp": ..., "result": {} }
```

| Case | HTTP Status | Envelope Code |
|---|---|---|
| Success | 200 | 200 |
| Validation error | 400 | 400 (result.code=INVALID_REQUEST) |
| Business rejection | 200 | 409 |

### API Versioning

No formal versioning yet — the lab API is internal/operator-facing. Grouped OpenAPI (lab-api) scans `com.xxxx.ddd.controller.http`.

### Idempotency

`POST /orders` requires `idempotencyKey`. The key is combined with `userId` and checked via in-memory `IdempotencyService`. Duplicate keys return the cached response.

---

## 7. Database Standards

| Rule | Standard |
|---|---|
| Source of truth | MySQL (`vetautet`) |
| Timestamps | `java.util.Date` / `LocalDateTime` |
| Stock values | `INT` (no decimals needed for ticket quantities) |
| Monthly tables | `ticket_order_YYYYMM` — created on demand |
| Migrations | Manual SQL init scripts in `environment/mysql/init/` |

### Key Tables

| Table | Role | Key constraint |
|---|---|---|
| `ticket` | Event fixture | PK `id` |
| `ticket_item` | Stock fixture | PK `id`; strategies mutate `stock_available` |
| `ticket_order_YYYYMM` | Monthly orders | PK `id`; UK on `order_number` |
| `outbox_event` | Outbox persistence | PK `id`; indexed on `status`, `created_at` |

---

## 8. Redis Standards

| Rule | Standard |
|---|---|
| Role | Fast gate/cache, NOT source of truth |
| Key format | `TICKET:{ticketItemId}:STOCK` |
| Atomic ops | Lua scripts for check-and-decrement |
| Compensation | `restoreStockCache()` restores Redis when DB rejects |
| Reconciliation | Scheduled job sets Redis to DB truth on drift |

---

## 9. Messaging Standards

| Rule | Standard |
|---|---|
| Pattern | Transactional Outbox |
| Broker | Apache Kafka 3.9.0, KRaft mode |
| Topic | `flashsale.orders` |
| Delivery | At-least-once |
| Idempotency | Consumers must be idempotent (event ID in payload) |
| Producer config | `acks=all`, String serializer |
| Batch size | 50 events per scheduler tick |
| Retry | 10s delay, max 5 attempts |

---

## 10. Resilience Standards

| Pattern | Implementation | Config |
|---|---|---|
| Rate limiter | Resilience4j `@RateLimiter` | 2 req/10s (`backendA`), 5 req/10s (`backendB`) |
| Circuit breaker | Resilience4j `@CircuitBreaker` | 50% failure threshold, 5s open, 10 sliding window |
| Distributed lock | Redisson | Single or Sentinel mode |
| Outbox retry | `OutboxService.retryFailedEvents()` | 10s delay, max 5 attempts |

### Retry Policy

- **Do retry**: outbox publish failures, transient DB deadlocks
- **Do NOT retry**: validation errors, authentication failures, business rule violations

---

## 11. Observability Standards

| Surface | Endpoint |
|---|---|
| Health | `GET /actuator/health` (details hidden) |
| Metrics | `GET /actuator/prometheus` |
| Metric registry | `GET /actuator/metrics` |

### Key Metrics

```
flashsale.orders{strategy,result}
flashsale.reconciliation{action,direction}
outbox.publish.{success,failure,latency}
outbox.backlog.{pending,failed}
```

### Tracing

Micrometer Tracing with Brave bridge. `@Observed` spans on: `order.create`, `stock.warmup`, `benchmark.reset`, `consistency.check`. Trace context (traceId/spanId) propagated via MDC.

---

## 12. Git Workflow

| Rule | Standard |
|---|---|
| Branch model | Trunk-based (`master` is main) |
| Commit convention | Conventional Commits: `type: description` |
| Types | `feat`, `fix`, `refactor`, `docs`, `test`, `chore`, `perf`, `ci` |
| PR target | `master` |
| Pre-commit | Run `gitnexus_detect_changes()`, verify affected scope |

---

## 13. CI/CD

| Pipeline | Trigger | Key Jobs |
|---|---|---|
| **CI** (ci.yml) | Push/PR to master | Unit tests, integration tests (Docker), JAR build, observability smoke, frontend checks, infra validation |
| **CD** (cd.yml) | Push to master | Build & push backend + frontend Docker images to GHCR |

### Docker Images

| Image | Registry |
|---|---|
| Backend | `ghcr.io/qwan30/flashsale-backend:latest` |
| Frontend | `ghcr.io/qwan30/flashsale-frontend:latest` |

---

## 14. Definition of Done

A feature/change is **done** when:

- [ ] Code passes all unit and integration tests
- [ ] `gitnexus_detect_changes()` confirms expected scope
- [ ] Impact analysis run for all modified symbols; no CRITICAL/HIGH warnings ignored
- [ ] Docs updated to match current code state
- [ ] Commit follows Conventional Commits format
- [ ] For strategy changes: benchmark smoke passes
- [ ] For API changes: Swagger/OpenAPI surfaces verified

---

## 15. AI Usage Rules

- AI assistants may generate code, tests, and docs.
- All AI-generated code must pass the same test gates as human-written code.
- AI-generated docs must be verified against source truth (Java, Maven, config, scripts).
- Git commits may be Co-Authored-By AI.
- AI must run `gitnexus_impact()` before editing any symbol.

---

## Cross-Reference

| Standard defined here | Referenced by |
|---|---|
| Architecture style | `04-architecture/architecture.md` |
| Coding standards | `04-architecture/coding-standards.md` |
| API standards | `reference/API_REFERENCE.md` |
| Database standards | `06-database/db-schema.md` |
| Redis standards | `performance/CONCURRENCY_AND_CONSISTENCY.md` |
| Messaging standards | `architecture/ARCHITECTURE.md` (Messaging section) |
| Resilience standards | `04-architecture/resilience-patterns.md` |
| Observability standards | `operations/OBSERVABILITY.md` |
| CI/CD | `10-deployment/ci-cd.md` |
