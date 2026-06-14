# Entity Relationship Diagram

> MySQL 8.0, database `vetautet`. Rendered with Mermaid.

```mermaid
erDiagram
    ticket {
        BIGINT id PK "Event ID"
        VARCHAR name "Event name"
        VARCHAR description
        DATETIME start_time
        DATETIME end_time
        INT status
        DATETIME updated_at
        DATETIME created_at
    }

    ticket_item {
        BIGINT id PK "Ticket item ID (fixture: 4)"
        INT stock_available "Mutated by strategies"
        INT stock_initial "Initial stock"
    }

    ticket_order_YYYYMM {
        BIGINT id PK "Auto-generated"
        VARCHAR order_number UK "OKX-SGN-{userId}-{timestamp}"
        BIGINT user_id
        BIGINT ticket_item_id FK "→ ticket_item.id"
        INT quantity "Always 1"
        VARCHAR strategy "Strategy enum"
        DATETIME created_at
    }

    outbox_event {
        VARCHAR id PK "UUID"
        VARCHAR aggregate_type "Order | Reconciliation"
        VARCHAR aggregate_id "order_number or ticketItemId"
        VARCHAR event_type "ORDER_CREATED | RECONCILIATION"
        VARCHAR event_version "1.0"
        TEXT payload "JSON event body"
        VARCHAR status "PENDING | PUBLISHED | FAILED"
        INT attempts "Publish attempts"
        TEXT last_error
        DATETIME next_attempt_at
        DATETIME created_at
        DATETIME published_at
    }

    ticket ||--o{ ticket_item : "has items"
    ticket_item ||--o{ ticket_order_YYYYMM : "ordered in"
    ticket_order_YYYYMM ||--o{ outbox_event : "produces"
```

## Relationships

| From | To | Type | Note |
|---|---|---|---|
| `ticket` | `ticket_item` | 1:N | Event fixture data |
| `ticket_item` | `ticket_order_YYYYMM` | 1:N | Orders reference item by FK |
| `ticket_order_YYYYMM` | `outbox_event` | 1:N | One `ORDER_CREATED` per order |

## Design Notes

1. **Monthly tables**: `ticket_order_YYYYMM` created on demand — avoids single-table hotspots.
2. **Outbox co-location**: Same DB as business data → atomic `@Transactional` writes.
3. **No FK on outbox**: `aggregate_id` is logical reference, not DB foreign key — keeps outbox decoupled.
4. **Compound indexes**: `(status, created_at)` for pending drain, `(status, next_attempt_at, created_at)` for retry queries.
