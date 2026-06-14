# Glossary — Standardized Terminology

> Ubiquitous Language for the Flash-Sale Concurrency Engine. Use these terms consistently across all docs.

## Core Domain

| Term | Definition |
|---|---|
| **Stock** | Available quantity of a ticket item. Core resource under contention. |
| **Stock Deduction** | Atomic operation reducing available stock. 4 strategy variants. |
| **Stock Available** | Current remaining stock (`stock_available` in `ticket_item`). |
| **Initial Stock** | Stock set at benchmark reset. Reconstructed: `dbStockAfter + dbOrderCount`. |
| **Oversell** | Selling beyond stock — `dbStockAfter < 0`. Expected only for UNSAFE_DB. |

## Strategies

| Term | Definition |
|---|---|
| **UNSAFE_DB** | Stock decrement without availability check. Race-condition baseline. |
| **CONDITIONAL_DB** | Atomic DB check-and-decrement. Safe baseline. |
| **REDIS_LUA** | Redis Lua atomic gate, then DB update. No compensation on failure. |
| **REDIS_LUA_WITH_COMPENSATION** | Redis Lua + DB + immediate Redis restore. Preferred strategy. |
| **Strategy Registry** | `EnumMap<OrderStrategy, StockDeductionStrategy>` — auto-wired by `@Component`. |

## Consistency & Reconciliation

| Term | Definition |
|---|---|
| **Source of Truth** | MySQL — authoritative stock value. |
| **Redis Gate** | Redis caches stock for fast atomic decrements. NOT source of truth. |
| **Drift** | `redisStock - dbStock`. Non-zero = Redis doesn't match durable truth. |
| **Compensation** | Restoring Redis when DB rejects. Done by REDIS_LUA_WITH_COMPENSATION. |
| **Reconciliation** | Scheduled job detecting drift, resetting Redis to DB. Safety net. |
| **Consistency Snapshot** | Point-in-time: Redis stock, DB stock, order count, drift, oversold. |

## Benchmarking

| Term | Definition |
|---|---|
| **Reset** | Restore DB stock, clear orders, sync Redis, clear idempotency cache. |
| **Warmup** | Copy current DB stock into Redis. Required before Redis-backed benchmarks. |
| **Smoke Test** | Single-order: reset → warmup → one order → consistency check. |
| **JMeter Run** | Full benchmark: reset → warmup → concurrent requests → consistency → save. |
| **Run Manifest** | `run.json` with strategy, timestamps, throughput, latency, consistency. |
| **Experiment Spec** | `experiment-spec.json` — machine-readable benchmark contract. |

## Messaging

| Term | Definition |
|---|---|
| **Transactional Outbox** | Write event row in same DB tx as business change, async publish to Kafka. |
| **Outbox Event** | Row in `outbox_event` representing a domain event to publish. |
| **Outbox Scheduler** | `OutboxPublishScheduler` — fixed-delay job draining pending events to Kafka. |
| **At-Least-Once** | Events never lost, but consumers may receive duplicates. |
| **KRaft** | Kafka Raft — ZooKeeper-less Kafka (Apache Kafka 3.9.0). |

## Infrastructure

| Term | Definition |
|---|---|
| **Virtual Threads** | Java 21 lightweight threads (Project Loom). Enabled for Tomcat pool. |
| **Redisson** | Redis Java client with distributed lock support. |
| **Lettuce** | Redis driver for Spring Data Redis. |
| **HikariCP** | JDBC connection pool for MySQL. |

## API

| Term | Definition |
|---|---|
| **ResultMessage<T>** | Standard response envelope: `{ success, message, code, timestamp, result }`. |
| **Idempotency Key** | `userId:idempotencyKey` preventing duplicate orders. |
| **Business Rejection** | Application failure — HTTP 200, envelope code 409. |

## Abbreviations

| Abbr | Full |
|---|---|
| DDD | Domain-Driven Design |
| GHCR | GitHub Container Registry |
| KRaft | Kafka Raft |
| JMeter | Apache JMeter (load testing) |
| ADR | Architecture Decision Record |
