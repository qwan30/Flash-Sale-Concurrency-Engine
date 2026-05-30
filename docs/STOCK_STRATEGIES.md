# Stock Strategies

The engine exposes four `OrderStrategy` values. Each strategy implements `StockDeductionStrategy` and is selected by `StockDeductionStrategyRegistry`.

## Strategy Summary

| Strategy | Implementation | Oversell safety | Redis compensation |
|---|---|---:|---:|
| `UNSAFE_DB` | DB decrement without stock predicate | no | no |
| `CONDITIONAL_DB` | DB conditional update | yes | no |
| `REDIS_LUA` | Redis Lua gate plus DB conditional update | yes on normal path | no |
| `REDIS_LUA_WITH_COMPENSATION` | Redis Lua gate plus DB conditional update plus restore | yes | yes |

## UNSAFE_DB

Implementation: `UnsafeDbStockDeductionStrategy`

Behavior:

```sql
UPDATE ticket_item
SET stock_available = stock_available - :quantity
WHERE id = :ticketItemId
```

This is intentionally unsafe. It exists to show why a naive update can oversell under concurrent load. It is a lab baseline only.

Expected interpretation:

- `oversoldCount` may be positive.
- Throughput can look good because the strategy does less safety work.
- A failed safety check here is the point of the demonstration.

## CONDITIONAL_DB

Implementation: `ConditionalDbStockDeductionStrategy`

Behavior:

```sql
UPDATE ticket_item
SET stock_available = stock_available - :quantity
WHERE id = :ticketItemId
  AND stock_available >= :quantity
```

The DB predicate makes the check and update atomic from the application's point of view. If the affected row count is zero, the request is rejected with `DB_STOCK_DECREMENT_FAILED`.

Expected interpretation:

- `oversoldCount` should be `0`.
- Redis may not match DB after the run if Redis was warmed but the strategy did not use Redis.
- Throughput is usually lower because the DB stock row is the contention point.

## REDIS_LUA

Implementation: `RedisLuaStockDeductionStrategy`

Behavior:

1. Decrement Redis stock atomically through Lua.
2. If Redis reports insufficient stock, reject the request.
3. Run the same DB conditional update used by `CONDITIONAL_DB`.
4. If the DB update fails after Redis was decremented, return failure without restoring Redis.

Expected interpretation:

- `oversoldCount` should be `0` on normal runs.
- Redis can drift lower than DB when Redis decrement succeeds and a later DB/order step fails.
- Scheduled reconciliation repairs drift by setting Redis to DB stock.

## REDIS_LUA_WITH_COMPENSATION

Implementation: `RedisLuaCompensatingStockDeductionStrategy`

Behavior:

1. Decrement Redis stock atomically through Lua.
2. Run DB conditional update.
3. Restore Redis immediately if the DB stock update fails.
4. Mark the result as compensatable so `OrderCreationService` restores Redis if the later order insert fails.
5. Let scheduled reconciliation repair any residual double-fault drift.

Expected interpretation:

- `oversoldCount` should be `0`.
- `redisDbInconsistencyCount` should be `0` on healthy completed runs.
- This is the best strategy for the lab's correctness/performance story.

## Benchmark Expectations

The current benchmark contract is `benchmark/experiment-spec.json`:

| Field | Value |
|---|---|
| total requests | `5000` |
| concurrency | `100` |
| stock | `1000` |
| quantity per order | `1` |

Expected safe-strategy outcome:

```text
accepted orders = 1000
rejected orders = 4000
oversold count = 0
```

Saved sample rows in the README and dashboard are local evidence. Rerun `benchmark/run-jmeter.ps1` on the target machine for current performance numbers.

## Choosing A Strategy

| Goal | Strategy |
|---|---|
| demonstrate oversell risk | `UNSAFE_DB` |
| prove a simple safe baseline | `CONDITIONAL_DB` |
| measure Redis gate behavior without compensation | `REDIS_LUA` |
| show fast gate plus self-healing behavior | `REDIS_LUA_WITH_COMPENSATION` |

For release demos and portfolio explanation, lead with `REDIS_LUA_WITH_COMPENSATION` and compare it against `CONDITIONAL_DB` and `UNSAFE_DB`.
