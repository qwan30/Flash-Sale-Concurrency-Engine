# Release Checklist

Use this checklist before presenting or publishing the project.

## Documentation

- [ ] `README.md` links to the current docs index.
- [ ] `docs/API_REFERENCE.md` matches controller routes and request models.
- [ ] `docs/LAB_OPERATIONS.md` matches the current PowerShell script parameters.
- [ ] `PROJECT_CONTEXT.md` lists current strategies and API surfaces.
- [ ] `AGENTS.md` and `CLAUDE.md` mention Swagger/OpenAPI and release docs expectations.
- [ ] Docs use present-state language, not changelog wording.
- [ ] Admin endpoints are described as local lab controls.

## API Visibility

- [ ] Backend starts on `http://localhost:1122`.
- [ ] Swagger UI opens at `http://localhost:1122/swagger-ui.html`.
- [ ] OpenAPI JSON is available at `http://localhost:1122/v3/api-docs`.
- [ ] Grouped lab API JSON is available at `http://localhost:1122/v3/api-docs/lab-api`.
- [ ] Actuator health is available at `http://localhost:1122/actuator/health`.

## Build And Tests

```bash
mvn -pl app/backend/xxxx-start -am test
```

Docker-gated integration:

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

```powershell
powershell -ExecutionPolicy Bypass -File benchmark/smoke-local.ps1
powershell -ExecutionPolicy Bypass -File benchmark/run-jmeter.ps1 -Strategy REDIS_LUA_WITH_COMPENSATION
```

After each benchmark run:

- [ ] `benchmark/results/{runId}/run.json` exists.
- [ ] `benchmark/results/{runId}/summary-row.md` exists.
- [ ] `oversoldCount` is `0` for safe strategies.
- [ ] `redisDbInconsistencyCount` is interpreted with the selected strategy.
- [ ] Saved numbers are labeled as local benchmark results.

## GitNexus

Before committing:

- [ ] Run GitNexus change detection.
- [ ] Confirm affected symbols and flows are expected.
- [ ] If code changed, re-run `npx.cmd gitnexus analyze --embeddings` when the index should stay fresh with embeddings.
