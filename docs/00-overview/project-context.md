# Project Context

> Goals, constraints, high-level decisions, and assumptions.

## 1. Elevator Pitch

A **Spring Boot backend reliability lab** proving stock-deduction correctness under concurrent flash-sale load. Four strategies — from intentionally unsafe to fully compensated — compared with reproducible JMeter benchmarks. The ticket domain is the fixture; **not** a full product.

## 2. Core Goals

| ID | Goal | Metric |
|---|---|---|
| BO-001 | Prove overselling prevented for safe strategies | `oversoldCount = 0` |
| BO-002 | Prove Redis-DB consistency achievable with compensation | `driftAmount = 0` for REDIS_LUA_WITH_COMPENSATION |
| BO-003 | Demonstrate 4 strategies with measurable trade-offs | Throughput, latency, oversold, drift per strategy |
| BO-004 | Reproducible benchmark evidence | `experiment-spec.json` + JMeter + saved manifests |
| BO-005 | Reliable event publishing | At-least-once via transactional outbox → Kafka |

## 3. Non-Goals

- Payment, billing, checkout, fulfillment, notifications
- Buyer accounts, registration, login
- Consumer-facing UI
- Multi-tenancy, production auth (admin endpoints are local controls)

## 4. Key Architecture Decisions

| Decision | Rationale | ADR |
|---|---|---|
| Transactional Outbox → Kafka | At-least-once without 2PC | ADR-001 |
| Strategy Pattern for Stock Deduction | 4 interchangeable algorithms, auto-registered | ADR-002 |
| Redis as Fast Gate (not source of truth) | MySQL durable truth; Redis low-latency gating | ADR-003 |
| Virtual Threads (Java 21) | High-throughput without reactive complexity | — |
| DDD-Layered Modular Monolith | Clear domain/application/infrastructure boundaries | — |
| Monthly Order Tables (`ticket_order_YYYYMM`) | Avoids single-table hot spots | — |

## 5. Constraints

| Constraint | Detail |
|---|---|
| Language | Java 21 |
| Framework | Spring Boot 3.3.5 |
| DB | MySQL 8.0 (source of truth) |
| Cache | Redis 7 (fast gate) |
| Messaging | Apache Kafka 3.9.0 (KRaft) |
| Build | Maven multi-module |
| Testing | JUnit 5, Testcontainers, JMeter |
| Frontend | Next.js 16.2.4 (operator dashboard) |
| CI/CD | GitHub Actions, GHCR |
| Ports | Backend 1122, Frontend 3000, MySQL 3316, Redis 6319, Kafka 9094 |

## 6. Assumptions

| ID | Assumption |
|---|---|
| A-001 | Local Docker Desktop available for MySQL, Redis, Kafka |
| A-002 | Benchmarks run on single machine — throughput is directional |
| A-003 | Fixture `ticketItemId=4`, `stock=1000` sufficient for comparison |
| A-004 | Kafka optional for core order flow (outbox degrades gracefully) |
| A-005 | Admin endpoints not exposed to public networks |
| A-006 | Single-instance deployment |

## 7. Open Questions

| ID | Question | Status |
|---|---|---|
| OPEN-001 | Full payload vs. ID only in outbox events? | Resolved: full `CreateOrderResponse` |
| OPEN-002 | Auto-reconcile or manual only? | Resolved: both (30s scheduled + manual endpoint) |
| OPEN-003 | Hot-swappable strategies at runtime? | Resolved: per-request strategy via enum |
