# Flash-sale Frontend Dashboard

Next.js dashboard for the Spring Boot flash-sale backend reliability lab. It provides order probes, benchmark review, stock consistency checks, and a local operator control desk.

## Run

```bash
npm install
cp .env.local.example .env.local
npm run dev
```

Defaults:

| Service | URL |
|---|---|
| Frontend | `http://localhost:3000` |
| Backend | `http://localhost:1122` |

The Next.js API proxy forwards `/api/backend/*` to `BACKEND_BASE_URL`, so the browser does not need Spring CORS changes.

### Local benchmark controls

The reset, Redis-warmup, and reconciliation paths are intentionally disabled unless all of the following are configured for a local benchmark session:

```bash
# Spring Boot process
BENCHMARK_CONTROL_ENABLED=true
BENCHMARK_CONTROL_TOKEN=<backend-only-secret>

# Next.js process (do not use NEXT_PUBLIC_ names)
BENCHMARK_CONTROL_TOKEN=<same-backend-only-secret>
BENCHMARK_OPERATOR_TOKEN=<separate-operator-password>
BENCHMARK_OPERATOR_ORIGIN=http://localhost:3000
```

An operator must enter the separate operator password in the Control Desk. The Next.js server then creates a 15-minute, HttpOnly, same-site session and checks the exact request origin before it injects the backend-only token. Workload and E2E setup call Spring Boot directly with `BENCHMARK_CONTROL_TOKEN`; never expose either secret in browser code or commit it to this repository.

Backend API documentation is available when the backend is running:

| Surface | URL |
|---|---|
| Swagger UI | `http://localhost:1122/swagger-ui.html` |
| OpenAPI JSON | `http://localhost:1122/v3/api-docs` |
| Lab API OpenAPI JSON | `http://localhost:1122/v3/api-docs/lab-api` |

The benchmark report page reads recorded runs from `GET /admin/benchmarks/runs`. If no backend runs are available, it falls back to sample rows from `src/lib/benchmark-data.ts`.

## Verify

```bash
npm run lint
npm run typecheck
npm run build
```
