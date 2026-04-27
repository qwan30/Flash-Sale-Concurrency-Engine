<!-- gitnexus:start -->
# GitNexus - Code Intelligence

This project is indexed by GitNexus as **xxxx.com-27-04-25** (1112 symbols, 2704 relationships, 86 execution flows). Use the GitNexus MCP tools to understand code, assess impact, and navigate safely.

> If any GitNexus tool warns the index is stale, run `npx gitnexus analyze` in terminal first.

## Always Do

- **MUST run impact analysis before editing any symbol.** Before modifying a function, class, or method, run `gitnexus_impact({target: "symbolName", direction: "upstream"})` and report the blast radius: direct callers, affected processes, and risk level.
- **MUST run `gitnexus_detect_changes()` before committing** to verify your changes only affect expected symbols and execution flows.
- **MUST warn the user** if impact analysis returns HIGH or CRITICAL risk before proceeding with edits.
- When exploring unfamiliar code, use `gitnexus_query({query: "concept"})` to find execution flows instead of grepping. It returns process-grouped results ranked by relevance.
- When you need full context on a specific symbol, use `gitnexus_context({name: "symbolName"})` to inspect callers, callees, and process participation.

## Stable Project Commands

| Task | Command | Expected result |
|------|---------|-----------------|
| Backend tests | `mvn test` | Reactor build succeeds across root and all backend modules |
| Backend app | `mvn -pl app/backend/xxxx-start -am spring-boot:run` | App listens on `http://localhost:1122` |
| Frontend typecheck | `npm run typecheck` from `app/frontend` | TypeScript exits cleanly |
| Frontend lint | `npm run lint` from `app/frontend` | ESLint exits cleanly |
| Frontend build | `npm run build` from `app/frontend` | Next.js production build succeeds |
| Local smoke | `powershell -ExecutionPolicy Bypass -File benchmark/smoke-local.ps1` | Reset, warmup, one order, and consistency calls succeed |
| Docker integration test | `mvn -pl app/backend/xxxx-start -am "-Dflashsale.integration=true" test` | Testcontainers runs MySQL and Redis stock invariant test |
| Benchmark run | `powershell -ExecutionPolicy Bypass -File benchmark/run-jmeter.ps1 -Strategy REDIS_LUA_WITH_COMPENSATION` | Creates `benchmark/results/{strategy}-{timestamp}/run.json` |

## Architecture Map

| Area | Main files |
|------|------------|
| Order creation | `app/backend/xxxx-application/src/main/java/com/xxxx/ddd/application/service/order/OrderCreationService.java` |
| Stock strategy selection | `app/backend/xxxx-application/src/main/java/com/xxxx/ddd/application/service/order/strategy/` |
| Reset and warmup fixtures | `app/backend/xxxx-application/src/main/java/com/xxxx/ddd/application/service/order/BenchmarkFixtureService.java` |
| Consistency checks | `app/backend/xxxx-application/src/main/java/com/xxxx/ddd/application/service/order/ConsistencyCheckService.java` |
| Benchmark run history | `app/backend/xxxx-application/src/main/java/com/xxxx/ddd/application/service/benchmark/BenchmarkRunService.java` |
| Operator dashboard | `app/frontend/src/components/` and `app/frontend/src/app/admin/` |
| Experiment contract | `benchmark/experiment-spec.json` |

## Where To Change Things

| Change | Start here |
|--------|------------|
| Add or modify a stock strategy | Add a `StockDeductionStrategy` implementation in `app/backend/xxxx-application/src/main/java/com/xxxx/ddd/application/service/order/strategy/` |
| Change order creation behavior | Inspect `OrderCreationService` and run impact on `createOrder` first |
| Change reset/warmup behavior | Inspect `BenchmarkFixtureService` and admin benchmark controller endpoints |
| Change consistency calculations | Inspect `ConsistencyCheckService` |
| Change benchmark dashboard data | Inspect `BenchmarkRunService`, `benchmark/run-jmeter.ps1`, and `app/frontend/src/components/benchmark-dashboard.tsx` |
| Change dashboard styling | Inspect `docs/DESIGN.md`, `app/frontend/src/app/globals.css`, and component files under `app/frontend/src/components/` |

## When Debugging

1. `gitnexus_query({query: "<error or symptom>"})` - find execution flows related to the issue.
2. `gitnexus_context({name: "<suspect function>"})` - see callers, callees, and process participation.
3. Read `gitnexus://repo/xxxx.com-27-04-25/process/{processName}` - trace the full execution flow step by step.
4. For regressions: `gitnexus_detect_changes({scope: "compare", base_ref: "main"})` - see what your branch changed.

## When Refactoring

- **Renaming**: MUST use `gitnexus_rename({symbol_name: "old", new_name: "new", dry_run: true})` first. Review the preview; graph edits are safe, text_search edits need manual review. Then run with `dry_run: false`.
- **Extracting/Splitting**: MUST run `gitnexus_context({name: "target"})` to see all incoming/outgoing refs, then `gitnexus_impact({target: "target", direction: "upstream"})` to find all external callers before moving code.
- After any refactor: run `gitnexus_detect_changes({scope: "all"})` to verify only expected files changed.

## Never Do

- NEVER edit a function, class, or method without first running `gitnexus_impact` on it.
- NEVER ignore HIGH or CRITICAL risk warnings from impact analysis.
- NEVER rename symbols with find-and-replace; use `gitnexus_rename` which understands the call graph.
- NEVER commit changes without running `gitnexus_detect_changes()` to check affected scope.

## Tools Quick Reference

| Tool | When to use | Command |
|------|-------------|---------|
| `query` | Find code by concept | `gitnexus_query({query: "auth validation"})` |
| `context` | 360-degree view of one symbol | `gitnexus_context({name: "validateUser"})` |
| `impact` | Blast radius before editing | `gitnexus_impact({target: "X", direction: "upstream"})` |
| `detect_changes` | Pre-commit scope check | `gitnexus_detect_changes({scope: "staged"})` |
| `rename` | Safe multi-file rename | `gitnexus_rename({symbol_name: "old", new_name: "new", dry_run: true})` |
| `cypher` | Custom graph queries | `gitnexus_cypher({query: "MATCH ..."})` |

## Impact Risk Levels

| Depth | Meaning | Action |
|-------|---------|--------|
| d=1 | WILL BREAK - direct callers/importers | MUST update these |
| d=2 | LIKELY AFFECTED - indirect deps | Should test |
| d=3 | MAY NEED TESTING - transitive | Test if critical path |

## Resources

| Resource | Use for |
|----------|---------|
| `gitnexus://repo/xxxx.com-27-04-25/context` | Codebase overview, check index freshness |
| `gitnexus://repo/xxxx.com-27-04-25/clusters` | All functional areas |
| `gitnexus://repo/xxxx.com-27-04-25/processes` | All execution flows |
| `gitnexus://repo/xxxx.com-27-04-25/process/{name}` | Step-by-step execution trace |

## Self-Check Before Finishing

Before completing any code modification task, verify:

1. `gitnexus_impact` was run for all modified symbols.
2. No HIGH/CRITICAL risk warnings were ignored.
3. `gitnexus_detect_changes()` confirms changes match expected scope.
4. All d=1 (WILL BREAK) dependents were updated.

## Keeping the Index Fresh

After committing code changes, the GitNexus index becomes stale. Re-run analyze to update it:

```bash
npx gitnexus analyze
```

If the index previously included embeddings, preserve them by adding `--embeddings`:

```bash
npx gitnexus analyze --embeddings
```

To check whether embeddings exist, inspect `.gitnexus/meta.json`; the `stats.embeddings` field shows the count. Zero means no embeddings. Running analyze without `--embeddings` will delete any previously generated embeddings.

<!-- gitnexus:end -->
