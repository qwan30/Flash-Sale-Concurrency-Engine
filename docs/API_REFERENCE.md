# API Reference

Base URL: `http://localhost:1122`

Interactive documentation:

| Surface | URL |
|---|---|
| Swagger UI | `http://localhost:1122/swagger-ui.html` |
| OpenAPI JSON | `http://localhost:1122/v3/api-docs` |
| Grouped lab API JSON | `http://localhost:1122/v3/api-docs/lab-api` |

The grouped OpenAPI surface scans `com.xxxx.ddd.controller.http`.

## Response Envelope

Most APIs return `ResultMessage<T>`:

```json
{
  "success": true,
  "message": "success",
  "code": 200,
  "timestamp": 1777280000000,
  "result": {}
}
```

`POST /orders` returns `ResponseEntity<ResultMessage<CreateOrderResponse>>`.

| Case | HTTP status | Envelope code | Notes |
|---|---:|---:|---|
| Valid request and order created | `200` | `200` | `result.success=true` |
| Invalid request body or field | `400` | `400` | `result.code=INVALID_REQUEST` |
| Business rejection or persistence failure | `200` | `409` | `result.success=false` |

Business rejections keep HTTP `200` so benchmark clients can count application-level sell-out or stock failures separately from HTTP transport failures.

## Supported Strategies

`CreateOrderRequest.strategy` accepts:

- `UNSAFE_DB`
- `CONDITIONAL_DB`
- `REDIS_LUA`
- `REDIS_LUA_WITH_COMPENSATION`

## Order APIs

### Create Order

`POST /orders`

Request:

```json
{
  "ticketItemId": 4,
  "userId": 42,
  "quantity": 1,
  "strategy": "REDIS_LUA_WITH_COMPENSATION",
  "idempotencyKey": "user-42-run-1"
}
```

Validation:

| Field | Required | Rule |
|---|---:|---|
| `ticketItemId` | yes | positive number |
| `userId` | yes | positive number |
| `quantity` | yes | positive number |
| `strategy` | yes | supported `OrderStrategy` |
| `idempotencyKey` | yes | non-empty string; combined with `userId` |

Success:

```json
{
  "success": true,
  "code": 200,
  "message": "Order created",
  "result": {
    "success": true,
    "code": "SUCCESS",
    "message": "Order created",
    "orderNumber": "OKX-SGN-42-1777280000000",
    "strategy": "REDIS_LUA_WITH_COMPENSATION",
    "ticketItemId": 4,
    "userId": 42,
    "quantity": 1,
    "redisStockAfter": 999,
    "dbStockAfter": 999
  }
}
```

Business rejection:

```json
{
  "success": false,
  "code": 409,
  "message": "Redis stock is missing or not enough",
  "result": {
    "success": false,
    "code": "REDIS_STOCK_UNAVAILABLE",
    "message": "Redis stock is missing or not enough",
    "strategy": "REDIS_LUA_WITH_COMPENSATION",
    "ticketItemId": 4,
    "userId": 42,
    "quantity": 1,
    "redisStockAfter": 0,
    "dbStockAfter": 0
  }
}
```

Example:

```bash
curl -X POST http://localhost:1122/orders ^
  -H "Content-Type: application/json" ^
  -d "{\"ticketItemId\":4,\"userId\":42,\"quantity\":1,\"strategy\":\"REDIS_LUA_WITH_COMPENSATION\",\"idempotencyKey\":\"smoke-1\"}"
```

### List Orders

`GET /orders?userId={userId}&yearMonth={yyyyMM}`

Reads orders for one user from `ticket_order_{yyyyMM}`.

```bash
curl "http://localhost:1122/orders?userId=42&yearMonth=202604"
```

### Get Order

`GET /orders/{orderNumber}`

Looks up one order number in the normalized/current monthly order table used by the query service.

```bash
curl "http://localhost:1122/orders/OKX-SGN-42-1777280000000"
```

## Ticket APIs

### Get Ticket Item

`GET /tickets/{ticketItemId}`

Returns `TicketDetailDTO` for a fixture ticket item.

```bash
curl "http://localhost:1122/tickets/4"
```

### Legacy Ticket Detail

`GET /ticket/{ticketId}/detail/{detailId}?version={version}`

This legacy fixture endpoint reads ticket detail data. `version` is optional.

### Ping

`GET /ticket/ping/java`

Returns:

```json
{
  "status": "OK"
}
```

The endpoint intentionally waits one second for local latency experiments.

## Admin Benchmark APIs

Admin endpoints are local lab controls. Do not expose them as public buyer-facing APIs.

### Warm Redis Stock

`POST /admin/tickets/{ticketItemId}/stock/warmup`

Copies current DB stock for the ticket item into Redis.

```bash
curl -X POST http://localhost:1122/admin/tickets/4/stock/warmup
```

### Reset Benchmark

`POST /admin/benchmarks/reset`

Request:

```json
{
  "ticketItemId": 4,
  "stock": 1000,
  "yearMonth": "202604"
}
```

Behavior:

- Sets DB `stock_initial` and `stock_available` to `stock`.
- Creates `ticket_order_{yearMonth}` if needed.
- Clears rows from the monthly order table.
- Sets Redis stock to the same value.
- Clears the in-memory idempotency cache.
- Normalizes missing or blank `yearMonth` to the current month.

Example:

```bash
curl -X POST http://localhost:1122/admin/benchmarks/reset ^
  -H "Content-Type: application/json" ^
  -d "{\"ticketItemId\":4,\"stock\":1000,\"yearMonth\":\"202604\"}"
```

### Check Consistency

`GET /admin/benchmarks/consistency?ticketItemId={id}&yearMonth={yyyyMM}`

Result fields:

| Field | Meaning |
|---|---|
| `ticketItemId` | fixture ticket item |
| `yearMonth` | normalized monthly order table suffix |
| `redisStockAfter` | current Redis stock, or `-1` when missing |
| `dbStockAfter` | current DB stock |
| `dbOrderCount` | row count in `ticket_order_{yearMonth}` |
| `oversoldCount` | `max(0, -dbStockAfter)` |
| `initialStock` | reconstructed as `dbStockAfter + dbOrderCount` |
| `expectedRedisStock` | expected Redis stock after the run |
| `driftAmount` | `redisStockAfter - expectedRedisStock` |
| `redisDbInconsistencyCount` | `1` when drift exists, otherwise `0` |

```bash
curl "http://localhost:1122/admin/benchmarks/consistency?ticketItemId=4&yearMonth=202604"
```

### Force Reconciliation

`POST /admin/benchmarks/reconcile?ticketItemId={id}&yearMonth={yyyyMM}`

Reads Redis stock and DB stock, then sets Redis to DB stock when drift exists.

```bash
curl -X POST "http://localhost:1122/admin/benchmarks/reconcile?ticketItemId=4&yearMonth=202604"
```

### List Saved Benchmark Runs

`GET /admin/benchmarks/runs`

Reads saved `run.json` manifests from `${BENCHMARK_RESULTS_DIR:benchmark/results}` and returns newest run IDs first.

```bash
curl http://localhost:1122/admin/benchmarks/runs
```

### Get Saved Benchmark Run

`GET /admin/benchmarks/runs/{runId}`

`runId` must match `[A-Za-z0-9_.-]+`; path traversal is rejected.

```bash
curl http://localhost:1122/admin/benchmarks/runs/REDIS_LUA_WITH_COMPENSATION-20260427-120000
```

## Deprecated Compatibility APIs

These routes remain for old benchmark plans and demo screens:

| Route | Replacement |
|---|---|
| `GET /order/{ticketId}/{quantity}/order` | `POST /orders` with `UNSAFE_DB` |
| `GET /order/{ticketId}/{quantity}/cas` | `POST /orders` with a current strategy |
| `GET /order/{userId}/list?ntable={yyyyMM}` | `GET /orders?userId=&yearMonth=` |
| `GET /order/{userId}/{orderNumber}` | `GET /orders/{orderNumber}` |
| `GET /ticket/{ticketId}/detail/{detailId}/order` | `POST /orders` |

Keep new docs, tests, and dashboards on the modern `/orders`, `/tickets`, and `/admin/benchmarks/*` routes.
