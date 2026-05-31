# Dashboard Guide

The Next.js frontend is an optional operator dashboard for the backend reliability lab. It is not a consumer ticket-sales product. Its job is to make reset, warmup, order probes, benchmark evidence, and consistency state visible without reading logs.

## Product Role

Default users:

- reviewers validating backend behavior
- operators running local smoke or benchmark cycles
- developers checking stock and drift state during changes
- agents verifying the lab end to end

The UI should stay focused on lab controls, strategy comparison, stock snapshots, benchmark results, and reproducibility.

## Routes

| Route | Purpose |
|---|---|
| `/` | Public lab entry with links to event fixtures and admin lab |
| `/events` | Fixture catalog backed by ticket APIs |
| `/events/{ticketItemId}` | Fixture detail |
| `/booking` | Controlled order probe |
| `/my-orders` | Monthly order-table browser |
| `/orders/{orderNumber}` | One order trace |
| `/admin/control-desk` | Reset, warmup, health, order probe, and consistency actions |
| `/admin/benchmark` | Saved benchmark-run review |
| `/admin/consistency` | Redis/DB consistency view |

## API Proxy

Dashboard calls use:

```text
browser -> /api/backend/* -> BACKEND_BASE_URL -> Spring Boot backend
```

`BACKEND_BASE_URL` defaults to `http://localhost:1122`. The central frontend API client is `app/frontend/src/lib/api.ts`.

## Screen Priorities

1. Control desk: reset stock, warm Redis, refresh health, submit controlled order probes, and run consistency checks.
2. Benchmark report: compare throughput, latency, accepted/rejected orders, oversold count, and Redis/DB drift.
3. Consistency view: show Redis stock, DB stock, order count, oversold rows, expected Redis stock, and drift amount.
4. Order probe: submit one controlled order request with a selected strategy and idempotency key.
5. Order traces: inspect stored monthly order rows by demo user and month.

## Screenshots

| Screenshot | What it shows |
|---|---|
| [home.png](./screenshots/home.png) | Lab entry |
| [events.png](./screenshots/events.png) | Fixture catalog |
| [order-traces.png](./screenshots/order-traces.png) | Stored order trace view |
| [admin-control-desk.png](./screenshots/admin-control-desk.png) | Operator control desk |
| [admin-benchmark.png](./screenshots/admin-benchmark.png) | Benchmark run review |
| [admin-consistency.png](./screenshots/admin-consistency.png) | Consistency dashboard |
| [admin-redirect.png](./screenshots/admin-redirect.png) | Admin navigation/redirect state |

## Copy Rules

Use lab language:

- strategy
- stock
- Redis
- DB
- consistency
- drift
- benchmark
- warmup
- reset
- order probe
- trace

Avoid consumer-sales language. Do not frame the UI as a buyer checkout, a marketplace, or a full ticketing product.

## Verification Expectations

- Operator actions should expose enough state to verify backend results without reading logs.
- Benchmark pages should keep correctness columns visible beside latency and throughput.
- Empty states should tell the user which backend operation did not return data.
- Error states should name the failing operation.
- Default IDs and months should match the benchmark fixture in [BENCHMARKING.md](./BENCHMARKING.md).
- API links should point to Swagger/OpenAPI surfaces when the backend is running locally.

## Run And Verify

```bash
cd app/frontend
npm install
copy .env.local.example .env.local
npm run dev
```

Verification:

```bash
npm run lint
npm run typecheck
npm run build
```
