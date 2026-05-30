# Business Flow

This project is a backend reliability lab for flash-sale stock deduction. The business value is not a ticketing product; the value is proving which stock strategy prevents overselling under concurrent load and how Redis/MySQL consistency is measured.

## Core Problem

Many clients attempt to buy the same limited-stock item at the same time. A naive stock update can accept too many orders. The lab compares safe and unsafe strategies with the same fixture so correctness and performance are visible.

Default fixture:

| Field | Value |
|---|---|
| ticket item | `4` |
| order quantity | `1` |
| unit price | `5000` |
| terminal id | `OKX-SGN` |
| order number | `OKX-SGN-{userId}-{timestamp}` |
| order table | `ticket_order_{yyyyMM}` |

## Order Creation

`POST /orders` accepts:

```json
{
  "ticketItemId": 4,
  "userId": 42,
  "quantity": 1,
  "strategy": "REDIS_LUA_WITH_COMPENSATION",
  "idempotencyKey": "user-42-run-1"
}
```

Flow:

```text
1. Validate request.
2. Build idempotency key as userId:idempotencyKey.
3. Return cached response when the same key is retried.
4. Select the configured stock deduction strategy.
5. Deduct stock or reject the request.
6. Create `ticket_order_{yyyyMM}` if needed.
7. Insert the order row.
8. Return stock state and order number.
9. Restore Redis stock when a compensating strategy has already decremented Redis and the later DB/order step fails.
```

Validation failures return envelope code `400`. Stock and persistence failures return envelope code `409` with `success=false`.

## Benchmark Flow

The lab workflow is intentionally repeatable:

```text
Reset -> Warm Redis -> Run requests -> Check consistency -> Save evidence
```

1. `POST /admin/benchmarks/reset`
   - Sets `ticket_item.stock_available` and `stock_initial` to the requested `stock`.
   - Clears `ticket_order_{yearMonth}`.
   - Sets Redis stock to the same value.
   - Clears the in-process idempotency cache.

2. `POST /admin/tickets/{ticketItemId}/stock/warmup`
   - Reads DB stock and writes it to Redis.

3. `POST /orders`
   - Sends benchmark traffic with one selected strategy.

4. `GET /admin/benchmarks/consistency`
   - Compares Redis stock, DB stock, order count, oversold count, and drift.

5. `benchmark/run-jmeter.ps1`
   - Runs the reset/warmup/JMeter/consistency sequence and writes `run.json`, `results.jtl`, an HTML report, and a markdown summary row.

## Strategy Roles

| Strategy | Role in the lab |
|---|---|
| `UNSAFE_DB` | Demonstrates the race condition by decrementing DB stock without a stock predicate |
| `CONDITIONAL_DB` | Safe baseline using `WHERE stock_available >= quantity` |
| `REDIS_LUA` | Fast Redis gate plus DB conditional update, without Redis compensation |
| `REDIS_LUA_WITH_COMPENSATION` | Redis gate plus DB conditional update, with Redis restore on DB/order failure |

`REDIS_LUA_WITH_COMPENSATION` is the main recommended lab strategy because it gives fast rejection behavior and repairs Redis when later steps fail.

## Consistency Model

MySQL is the source of truth. Redis is a fast cache/gate.

The consistency snapshot reports:

```text
expectedRedisStock = dbStockAfter
driftAmount = redisStockAfter - expectedRedisStock
```

Healthy safe-strategy runs end with:

```text
oversoldCount = 0
redisDbInconsistencyCount = 0
```

`CONDITIONAL_DB` can show Redis/DB mismatch when Redis was warmed but the strategy does not use Redis as the deduction gate. This is expected for that benchmark mode and should be interpreted with the strategy context.

## Lab Boundaries

- Admin endpoints are for local benchmark control, not public production exposure.
- The dashboard is an operator tool, not a consumer sales UI.
- The seeded ticket data is a fixture, not a product catalog.
- Saved benchmark numbers are local-machine evidence and should be rerun on the target machine before using them as performance claims.
