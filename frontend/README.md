# Flash-sale Frontend Dashboard

Next.js dashboard for the Spring Boot flash-sale ticketing backend lab.

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

## Verify

```bash
npm run lint
npm run typecheck
npm run build
```
