# Release Checklist

Use this checklist before presenting the project, publishing a portfolio summary, or committing a documentation refresh.

## Documentation

- [ ] [docs/README.md](./README.md) points to the current primary documentation map.
- [ ] [docs/REVIEWER_GUIDE.md](./REVIEWER_GUIDE.md) frames the project as a backend concurrency lab, not a full ticket-sales product.
- [ ] [docs/API_REFERENCE.md](./API_REFERENCE.md) matches current controller routes, DTO fields, and response-envelope semantics.
- [ ] [docs/CONCURRENCY_AND_CONSISTENCY.md](./CONCURRENCY_AND_CONSISTENCY.md) matches the four `OrderStrategy` implementations and reconciliation behavior.
- [ ] [docs/BENCHMARKING.md](./BENCHMARKING.md) matches `benchmark/smoke-local.ps1`, `benchmark/run-jmeter.ps1`, and `benchmark/experiment-spec.json`.
- [ ] [docs/DASHBOARD_GUIDE.md](./DASHBOARD_GUIDE.md) links to current screenshots and describes the dashboard as an operator surface.
- [ ] Compatibility pages point to their replacements and do not contain stale behavior claims.
- [ ] Root `README.md` and `PROJECT_CONTEXT.md` do not contradict the docs map.

## API Visibility

- [ ] Backend starts on `http://localhost:1122`.
- [ ] Health check is available at `http://localhost:1122/actuator/health`.
- [ ] Swagger UI opens at `http://localhost:1122/swagger-ui.html`.
- [ ] OpenAPI JSON is available at `http://localhost:1122/v3/api-docs`.
- [ ] Grouped lab API JSON is available at `http://localhost:1122/v3/api-docs/lab-api`.
- [ ] Prometheus metrics are available at `http://localhost:1122/actuator/prometheus`.

## Build And Tests

Backend test command:

```bash
mvn -pl app/backend/xxxx-start -am test
```

Docker-gated integration command:

```bash
mvn -pl app/backend/xxxx-start -am "-Dflashsale.integration=true" test
```

Frontend checks:

```bash
cd app/frontend
npm run lint
npm run typecheck
npm run build
```

## Benchmark Evidence

Smoke test:

```powershell
powershell -ExecutionPolicy Bypass -File benchmark/smoke-local.ps1
```

JMeter benchmark:

```powershell
powershell -ExecutionPolicy Bypass -File benchmark/run-jmeter.ps1 -Strategy REDIS_LUA_WITH_COMPENSATION
```

After each run:

- [ ] `benchmark/results/{runId}/run.json` exists.
- [ ] `benchmark/results/{runId}/summary-row.md` exists.
- [ ] `oversoldCount` is `0` for safe strategies.
- [ ] `redisDbInconsistencyCount` is interpreted with the selected strategy.
- [ ] Saved performance numbers are labeled as local-machine benchmark evidence.

## GitNexus

- [ ] Run `npx.cmd gitnexus status`.
- [ ] If stale, run `npx.cmd gitnexus analyze --embeddings` because this repo stores embeddings.
- [ ] Before committing, run GitNexus change detection and confirm the affected scope is expected.
- [ ] If code changed, verify affected execution flows and direct dependents before closing the task.
