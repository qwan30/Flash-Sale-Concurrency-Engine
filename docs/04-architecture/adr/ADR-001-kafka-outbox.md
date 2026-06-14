# ADR-001: Transactional Outbox → Kafka for Domain Events

> **Status**: Accepted | **Date**: 2026-05-31 | **Deciders**: qwan30

## Context

The engine needs to publish domain events (`ORDER_CREATED`, `RECONCILIATION`) reliably. Events must reach Kafka if the DB transaction commits. Constraints: single MySQL, Kafka 3.9.0 KRaft, events must survive Kafka downtime.

## Decision

**Transactional Outbox pattern**: write event rows to `outbox_event` in the same DB transaction as the business change, then async publish via scheduled worker.

```text
Business TX:  INSERT order + INSERT outbox_event → COMMIT (atomic)
Async (1s):   SELECT pending → KafkaTemplate.send() → mark PUBLISHED/FAILED
```

## Alternatives

| Alternative | Why Rejected |
|---|---|
| Direct Kafka send in TX | Dual-write: DB may commit, Kafka may fail |
| Kafka primary + DB projection | Over-engineered for lab scope |
| Debezium CDC | Requires Kafka Connect infra |

## Consequences

**Positive**: Atomic with DB, retryable, observable (per-event metrics, backlog gauges)
**Negative**: Up to 1s delay, at-least-once (consumers must be idempotent), outbox table grows

## References
- `app/backend/xxxx-application/.../MQ/OutboxService.java`
- `application.yml` → `app.outbox.*`, `app.kafka.*`
- Pattern: [Transactional Outbox](https://microservices.io/patterns/data/transactional-outbox.html)
