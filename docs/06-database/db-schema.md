# Database Schema

> MySQL 8.0, database `vetautet`. Source: JPA entities, `environment/mysql/init/` SQL scripts.

## Core Tables

### ticket — Event fixture
| Column | Type | Key | Description |
|---|---|---|---|
| `id` | `BIGINT` | PK | Event ID |
| `name` | `VARCHAR` | — | Event name |
| `description` | `VARCHAR` | — | Event description |
| `start_time` | `DATETIME` | — | Event start |
| `end_time` | `DATETIME` | — | Event end |
| `status` | `INT` | — | Event status |
| `updated_at` | `DATETIME` | — | Last update |
| `created_at` | `DATETIME` | — | Created |

### ticket_item — Stock fixture
| Column | Type | Key | Description |
|---|---|---|---|
| `id` | `BIGINT` | PK | Ticket item ID (fixture default: `4`) |
| `stock_available` | `INT` | — | Current available stock (mutated by strategies) |
| `stock_initial` | `INT` | — | Initial stock value |

### ticket_order_YYYYMM — Monthly orders (created on demand)
| Column | Type | Key | Description |
|---|---|---|---|
| `id` | `BIGINT` | PK | Auto-generated |
| `order_number` | `VARCHAR` | UK | `OKX-SGN-{userId}-{timestamp}` |
| `user_id` | `BIGINT` | — | Buyer user ID |
| `ticket_item_id` | `BIGINT` | FK→`ticket_item.id` | Purchased item |
| `quantity` | `INT` | — | Quantity ordered |
| `strategy` | `VARCHAR` | — | Strategy enum used |
| `created_at` | `DATETIME` | — | Order timestamp |

### outbox_event — Transactional outbox
| Column | Type | Key | Description |
|---|---|---|---|
| `id` | `VARCHAR` | PK | UUID |
| `aggregate_type` | `VARCHAR` | — | e.g., "Order", "Reconciliation" |
| `aggregate_id` | `VARCHAR` | — | e.g., order number |
| `event_type` | `VARCHAR` | — | `ORDER_CREATED`, `RECONCILIATION` |
| `event_version` | `VARCHAR` | — | "1.0" |
| `payload` | `TEXT` | — | JSON serialized event body |
| `status` | `VARCHAR` | IDX | `PENDING`, `PUBLISHED`, `FAILED` |
| `attempts` | `INT` | — | Publish attempt count |
| `last_error` | `TEXT` | — | Last failure message |
| `next_attempt_at` | `DATETIME` | IDX | Scheduled retry time |
| `created_at` | `DATETIME` | IDX | Event creation |
| `published_at` | `DATETIME` | — | When published |

## Indexes

| Table | Index | Type |
|---|---|---|
| `ticket_order_YYYYMM` | `uk_order_number` | UNIQUE |
| `outbox_event` | `idx_status_created` | BTREE (status, created_at) |
| `outbox_event` | `idx_status_next_attempt` | BTREE (status, next_attempt_at, created_at) |

## Key Queries

```sql
-- Safe deduction (CONDITIONAL_DB, REDIS_LUA, REDIS_LUA_WITH_COMPENSATION)
UPDATE ticket_item SET stock_available = stock_available - :qty
WHERE id = :id AND stock_available >= :qty;

-- Unsafe deduction (UNSAFE_DB)
UPDATE ticket_item SET stock_available = stock_available - :qty WHERE id = :id;

-- Consistency check
SELECT stock_available FROM ticket_item WHERE id = :id;
SELECT COUNT(*) FROM ticket_order_YYYYMM;

-- Outbox drain
SELECT * FROM outbox_event WHERE status = 'PENDING' ORDER BY created_at LIMIT 50;
```

## Concurrency Controls

| Mechanism | Used For |
|---|---|
| Conditional UPDATE (`WHERE stock_available >= :qty`) | Atomic stock check-and-decrement |
| InnoDB row-level locking | Implicit in UPDATE |
| Idempotency (in-memory) | Duplicate order prevention |
| Outbox transactional write | Atomicity of business + event persistence |

## Seed Data & Migrations

- No Flyway/Liquibase — manual SQL init in `environment/mysql/init/`
- Monthly tables created on demand: `CREATE TABLE IF NOT EXISTS ticket_order_YYYYMM`
