# Errors And Edge Cases

This guide explains how the lab reports failures and how to recover benchmark state.

## API Failure Semantics

`POST /orders` distinguishes invalid requests from business failures:

| Case | HTTP status | Envelope code | Typical response code |
|---|---:|---:|---|
| invalid body or missing field | `400` | `400` | `INVALID_REQUEST` |
| stock unavailable | `200` | `409` | `REDIS_STOCK_UNAVAILABLE` or `DB_STOCK_DECREMENT_FAILED` |
| order persistence failed | `200` | `409` | `ORDER_CREATE_FAILED` |

Other controller methods generally return `ResultUtil.data(...)` with envelope code `200`.

## Common Failure Modes

### Redis Stock Missing Or Exhausted

Symptoms:

```json
{
  "success": false,
  "code": "REDIS_STOCK_UNAVAILABLE",
  "message": "Redis stock is missing or not enough"
}
```

Recovery:

```bash
curl -X POST http://localhost:1122/admin/benchmarks/reset ^
  -H "Content-Type: application/json" ^
  -d "{\"ticketItemId\":4,\"stock\":1000,\"yearMonth\":\"202604\"}"

curl -X POST http://localhost:1122/admin/tickets/4/stock/warmup
```

### DB Stock Decrement Failed

Symptoms:

```json
{
  "success": false,
  "code": "DB_STOCK_DECREMENT_FAILED",
  "message": "Database stock was not decremented"
}
```

Meaning:

- For `CONDITIONAL_DB`, DB stock was not sufficient.
- For Redis strategies, Redis may have been decremented first. `REDIS_LUA_WITH_COMPENSATION` attempts Redis restore; `REDIS_LUA` does not.

Recovery:

```bash
curl "http://localhost:1122/admin/benchmarks/consistency?ticketItemId=4&yearMonth=202604"
curl -X POST "http://localhost:1122/admin/benchmarks/reconcile?ticketItemId=4&yearMonth=202604"
```

### Overselling Detected

Symptoms:

```json
{
  "oversoldCount": 5,
  "dbStockAfter": -5
}
```

Meaning:

The selected strategy allowed DB stock to go below zero. This is expected when demonstrating `UNSAFE_DB`, and it is a failed safety result for any safe strategy.

Recovery:

```bash
curl -X POST http://localhost:1122/admin/benchmarks/reset ^
  -H "Content-Type: application/json" ^
  -d "{\"ticketItemId\":4,\"stock\":1000,\"yearMonth\":\"202604\"}"
```

### Redis/DB Drift

Symptoms:

```json
{
  "redisStockAfter": 990,
  "dbStockAfter": 1000,
  "expectedRedisStock": 1000,
  "driftAmount": -10,
  "redisDbInconsistencyCount": 1
}
```

Meaning:

Redis does not match the DB source of truth. Causes include Redis-first deduction followed by DB/order failure, application shutdown at a bad time, or manual Redis mutation.

Recovery:

```bash
curl -X POST "http://localhost:1122/admin/benchmarks/reconcile?ticketItemId=4&yearMonth=202604"
```

Scheduled reconciliation also runs every 30 seconds for the default ticket item `4`.

### Docker-Gated Integration Test Skipped

The integration test class is enabled only when this property is present:

```bash
-Dflashsale.integration=true
```

Docker Desktop must be running because the test uses Testcontainers for MySQL and Redis.

## Debug Checklist

1. Check backend health:

   ```bash
   curl http://localhost:1122/actuator/health
   ```

2. Check Swagger/OpenAPI:

   ```bash
   curl http://localhost:1122/v3/api-docs
   ```

3. Check consistency:

   ```bash
   curl "http://localhost:1122/admin/benchmarks/consistency?ticketItemId=4"
   ```

4. Inspect latest saved benchmark:

   ```bash
   curl http://localhost:1122/admin/benchmarks/runs
   ```

5. Check Redis:

   ```bash
   docker exec pre-event-redis redis-cli GET ticket:stock:4
   ```

6. Check MySQL:

   ```bash
   docker exec pre-event-mysql mysql -uroot -proot1234 -e "SELECT id, stock_available FROM vetautet.ticket_item WHERE id=4;"
   ```

## Release-Safe Interpretation

- `UNSAFE_DB` is intentionally unsafe and should not be framed as production behavior.
- `CONDITIONAL_DB` is the simplest safe baseline.
- `REDIS_LUA` shows Redis gate behavior but can leave drift when a later DB/order step fails.
- `REDIS_LUA_WITH_COMPENSATION` is the preferred demo strategy for fast rejection plus restore behavior.
- Benchmark numbers are local evidence; rerun them on the target machine before publishing claims.
