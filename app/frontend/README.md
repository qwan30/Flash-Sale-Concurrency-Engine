# Flash-sale Frontend Dashboard

Next.js dashboard for the Spring Boot flash-sale backend reliability lab. The dashboard is an operator surface for reset, warmup, order probes, benchmark review, and stock consistency checks.

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
