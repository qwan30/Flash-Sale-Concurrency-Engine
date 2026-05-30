# API Reference

Base URL: `http://localhost:1122`

Interactive documentation:

| Surface | URL |
|---|---|
| Swagger UI | `http://localhost:1122/swagger-ui.html` |
| OpenAPI JSON | `http://localhost:1122/v3/api-docs` |
| Grouped lab API JSON | `http://localhost:1122/v3/api-docs/lab-api` |

The generated OpenAPI group scans `com.xxxx.ddd.controller.http`.

## Response Envelope

Most lab APIs return `ResultMessage<T>`:

```json
{
  "success": true,
  "message": "success",
  "code": 200,
  "timestamp": 1777280000000,
  "result": {}
}
```

`POST /orders` returns the same envelope through `ResponseEntity`. Invalid request bodies return HTTP `400`; stock and persistence business failures are represented by `success=false` and envelope code `409`.

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
| `strategy` | yes | one supported strategy |
| `idempotencyKey` | yes | non-empty string; combined with `userId` |

Success result:

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

Business failure result:

```json
{
  "success": false,
  "code": 409,
  "message": "Redis stock is missing or not enough",
  "result": {
    "success": false,
    "code": "REDIS_STOCK_UNAVAILABLE",
    "message": "Redis stock is missing or not enough",
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

Returns orders for one user in the monthly table `ticket_order_{yyyyMM}`.

```bash
curl "http://localhost:1122/orders?userId=42&yearMonth=202604"
```

### Get Order

`GET /orders/{orderNumber}`

Looks up an order number in the normalized/current monthly order table used by the query service.

```bash
curl "http://localhost:1122/orders/OKX-SGN-42-1777280000000"
```

## Ticket APIs

### Get Ticket Item

`GET /tickets/{ticketItemId}`

Returns `TicketDetailDTO` with fields such as `id`, `name`, `stockInitial`, `stockAvailable`, `priceOriginal`, `priceFlash`, sale windows, status, activity id, and version.

```bash
curl "http://localhost:1122/tickets/4"
```

### Ticket Detail By Parent Ticket

`GET /ticket/{ticketId}/detail/{detailId}?version={version}`

This controller path is part of the legacy ticket-detail surface. `version` is optional.

### Ping

`GET /ticket/ping/java`

Returns `{ "status": "OK" }` after the intentional one-second delay.

## Admin Benchmark APIs

Admin endpoints are local lab controls. They reset data, warm cache, reconcile state, and read benchmark artifacts.

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

`yearMonth` is normalized by the backend; missing or blank values use the current month. `stock` must be non-negative.

Result:

```json
{
  "success": true,
  "message": "Benchmark data reset",
  "ticketItemId": 4,
  "stock": 1000,
  "yearMonth": "202604",
  "redisStockAfter": 1000,
  "dbStockAfter": 1000,
  "dbOrderCount": 0
}
```

Example:

```bash
curl -X POST http://localhost:1122/admin/benchmarks/reset ^
  -H "Content-Type: application/json" ^
  -d "{\"ticketItemId\":4,\"stock\":1000,\"yearMonth\":\"202604\"}"
```

### Warm Redis Stock

`POST /admin/tickets/{ticketItemId}/stock/warmup`

Copies current DB stock for the ticket item into Redis.

```bash
curl -X POST http://localhost:1122/admin/tickets/4/stock/warmup
```

### Check Consistency

`GET /admin/benchmarks/consistency?ticketItemId={id}&yearMonth={yyyyMM}`

Result fields:

| Field | Meaning |
|---|---|
| `redisStockAfter` | Redis stock value |
| `dbStockAfter` | MySQL `ticket_item.stock_available` |
| `dbOrderCount` | rows in `ticket_order_{yyyyMM}` |
| `initialStock` | calculated as DB stock plus order count |
| `expectedRedisStock` | stock Redis should have |
| `driftAmount` | `redisStockAfter - expectedRedisStock` |
| `oversoldCount` | positive value when DB stock is below zero |
| `redisDbInconsistencyCount` | `1` when Redis and DB are inconsistent, otherwise `0` |

```bash
curl "http://localhost:1122/admin/benchmarks/consistency?ticketItemId=4&yearMonth=202604"
```

### Reconcile Stock

`POST /admin/benchmarks/reconcile?ticketItemId={id}&yearMonth={yyyyMM}`

Sets Redis stock to the DB source-of-truth value when drift exists.

```bash
curl -X POST "http://localhost:1122/admin/benchmarks/reconcile?ticketItemId=4&yearMonth=202604"
```

### List Benchmark Runs

`GET /admin/benchmarks/runs`

Reads saved `benchmark/results/*/run.json` files from `benchmark.results-dir`.

```bash
curl "http://localhost:1122/admin/benchmarks/runs"
```

### Get Benchmark Run Detail

`GET /admin/benchmarks/runs/{runId}`

`runId` must match `[A-Za-z0-9_.-]+`.

```bash
curl "http://localhost:1122/admin/benchmarks/runs/REDIS_LUA_WITH_COMPENSATION-20260427-095614"
```

## Demo And Legacy Endpoints

| Endpoint | Notes |
|---|---|
| `GET /hello/hi` | Resilience4j rate-limited demo endpoint |
| `GET /hello/hi/v1` | Resilience4j rate-limited demo endpoint |
| `GET /hello/circuit/breaker` | Circuit breaker demo calling `fakestoreapi.com` |
| `GET /order/{ticketId}/{quantity}/order` | Deprecated legacy order endpoint mapped to `CONDITIONAL_DB` |
| `GET /order/{ticketId}/{quantity}/cas` | Deprecated legacy order endpoint mapped to `REDIS_LUA_WITH_COMPENSATION` |
| `GET /order/{userId}/list?ntable={yyyyMM}` | Deprecated order list endpoint |
| `GET /order/{userId}/{orderNumber}` | Deprecated order lookup endpoint |

## Actuator And Observability

| Endpoint | Purpose |
|---|---|
| `GET /actuator/health` | runtime health |
| `GET /actuator/prometheus` | Prometheus scrape output |

The app exposes all actuator web endpoints in local configuration.
