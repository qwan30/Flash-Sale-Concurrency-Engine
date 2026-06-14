# Concurrency And Consistency

The lab compares four stock deduction strategies under the same flash-sale fixture. The important question is not "can the API create an order"; it is "can the system reject excess demand without overselling and explain Redis/MySQL drift after the run."

## Core Invariants

Safe strategies should satisfy these conditions after a controlled run:

```text
dbStockAfter >= 0
dbOrderCount <= initialStock
oversoldCount = 0
```

For `REDIS_LUA_WITH_COMPENSATION`, a healthy completed run should also satisfy:

```text
redisStockAfter = dbStockAfter
redisDbInconsistencyCount = 0
driftAmount = 0
```

`UNSAFE_DB` is intentionally allowed to violate these invariants so reviewers can see why the safer strategies exist.

## Strategy Summary

| Strategy | Implementation | Oversell safety | Redis drift behavior |
|---|---|---:|---|
| `UNSAFE_DB` | DB decrement without stock predicate | no | not applicable |
| `CONDITIONAL_DB` | DB conditional update | yes | Redis may differ if warmed but unused |
| `REDIS_LUA` | Redis Lua gate plus DB conditional update | yes on normal path | can drift when Redis succeeds and later DB/order work fails |
| `REDIS_LUA_WITH_COMPENSATION` | Redis Lua gate plus DB conditional update plus restore | yes | restores Redis on known failure paths |

## UNSAFE_DB

`UnsafeDbStockDeductionStrategy` calls the DB update without a stock predicate:

```sql
UPDATE ticket_item
SET stock_available = stock_available - :quantity
WHERE id = :ticketItemId
```

This is the race-condition baseline. Under concurrent load, too many requests can decrement the same row and push stock below zero. A positive `oversoldCount` is expected evidence for this strategy, not a product feature.

## CONDITIONAL_DB

`ConditionalDbStockDeductionStrategy` uses a single conditional update:

```sql
UPDATE ticket_item
SET stock_available = stock_available - :quantity
WHERE id = :ticketItemId
  AND stock_available >= :quantity
```

The stock check and decrement happen in one DB statement. When no row is affected, the request is rejected with `DB_STOCK_DECREMENT_FAILED`.

Expected interpretation:

- `oversoldCount` should be `0`.
- Accepted orders should not exceed initial stock.
- Throughput can be lower because the DB row is the contention point.
- Redis may not match DB if Redis was warmed but this DB-only strategy did not use Redis as the gate.

## REDIS_LUA

`RedisLuaStockDeductionStrategy` uses Redis first:

```text
1. Atomically decrement Redis stock with Lua.
2. Reject when Redis reports missing or insufficient stock.
3. Run the same DB conditional update as CONDITIONAL_DB.
4. Return failure when DB rejects.
5. Do not restore Redis in this strategy.
```

Expected interpretation:

- Normal completed runs should not oversell.
- Redis can drift lower than DB if Redis decremented stock and later DB/order work fails.
- Scheduled or manual reconciliation repairs drift by setting Redis back to DB truth.

## REDIS_LUA_WITH_COMPENSATION

`RedisLuaCompensatingStockDeductionStrategy` adds immediate compensation:

```text
1. Atomically decrement Redis stock with Lua.
2. Run DB conditional update.
3. Restore Redis immediately if DB rejects.
4. Return a compensation flag so OrderCreationService can restore Redis if the later order insert fails.
5. Let scheduled reconciliation repair rare double-fault drift.
```

This is the preferred strategy for the lab story because it combines fast Redis rejection with a clear consistency repair rule.

## Redis Key And Source Of Truth

Redis stock is stored under:

```text
TICKET:{ticketItemId}:STOCK
```

MySQL remains the source of truth. Redis is only the fast stock gate/cache. The consistency and reconciliation services always compare Redis back to DB state.

## Consistency Snapshot

`GET /admin/benchmarks/consistency` reports:

```text
initialStock = dbStockAfter + dbOrderCount
expectedRedisStock = initialStock - dbOrderCount
driftAmount = redisStockAfter - expectedRedisStock
redisDbInconsistencyCount = driftAmount != 0 ? 1 : 0
oversoldCount = max(0, -dbStockAfter)
```

Because `expectedRedisStock` equals the DB stock after the run, a non-zero drift means Redis no longer matches durable truth.

## Reconciliation

`OrderReconciliationService` runs every 30 seconds after a 10 second initial delay for default ticket item `4`.

Manual reconciliation:

```bash
curl -X POST "http://localhost:1122/admin/benchmarks/reconcile?ticketItemId=4&yearMonth=202604"
```

Reconciliation steps:

```text
1. Read Redis stock.
2. Read DB stock.
3. Compute drift = redisStock - dbStock.
4. If drift != 0, set Redis stock to DB stock.
5. Emit flashsale.reconciliation metric when repair happens.
6. Publish RECONCILIATION event through the transactional outbox to Kafka.
```

The `RECONCILIATION` outbox event carries the drift amount, direction, and affected ticket item so downstream consumers can react to consistency repairs.

## Failure Semantics

| Result code | Meaning | Typical recovery |
|---|---|---|
| `INVALID_REQUEST` | Missing or invalid request body field | Fix the request |
| `REDIS_STOCK_UNAVAILABLE` | Redis key missing or stock is insufficient | Run reset and warmup |
| `DB_STOCK_DECREMENT_FAILED` | DB conditional update rejected | Check stock, selected strategy, and consistency |
| `ORDER_CREATE_FAILED` | Stock phase passed but order insert failed | Check consistency and reconcile if needed |
| `WARMUP_FAILED` | Warmup could not read DB stock or ticket ID was invalid | Verify fixture data and ticket item ID |

## Troubleshooting Commands

Health:

```bash
curl http://localhost:1122/actuator/health
```

Consistency:

```bash
curl "http://localhost:1122/admin/benchmarks/consistency?ticketItemId=4"
```

Redis stock:

```bash
docker exec pre-event-redis redis-cli GET TICKET:4:STOCK
```

MySQL stock:

```bash
docker exec pre-event-mysql mysql -uroot -proot1234 -e "SELECT id, stock_available FROM vetautet.ticket_item WHERE id=4;"
```

Reset and warmup:

```bash
curl -X POST http://localhost:1122/admin/benchmarks/reset ^
  -H "Content-Type: application/json" ^
  -d "{\"ticketItemId\":4,\"stock\":1000,\"yearMonth\":\"202604\"}"

curl -X POST http://localhost:1122/admin/tickets/4/stock/warmup
```

## Reviewer Interpretation

- `UNSAFE_DB` overselling proves the race condition.
- `CONDITIONAL_DB` proves the simplest DB-safe baseline.
- `REDIS_LUA` proves fast Redis gating and makes drift visible.
- `REDIS_LUA_WITH_COMPENSATION` is the best demo strategy for fast rejection plus practical repair.
- Benchmark throughput numbers are local-machine evidence; rerun them on the target machine before publishing claims.
