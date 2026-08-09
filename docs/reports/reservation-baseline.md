# Reservation Upgrade Baseline

## Execution identity

| Field | Value |
|---|---|
| Baseline command SHA | `f07e88593b7039ae1adef050ca3676cd38d6cfde` |
| Baseline command window | 2026-08-09 10:06–10:10 Asia/Ho_Chi_Minh |
| Baseline branch | `docs/reservation-design` |
| Planned source base | `6d041980f0b97e3104f3db9cc095e596e89e651e` |
| Backend | Java 21 / Maven multi-module build |
| Frontend | Next.js 16.2.4, React 19.2.4, TypeScript 5 |
| Local JMeter harness | `benchmark/jmeter/bin/jmeter.bat` exists |

The report records the exact SHA at which the commands ran. The later documentation-only contract-fix commit `a95d364ff3cead7f01a9b127eae18c3828a074a3` was not part of the command execution window.

## Backend baseline

Command:

```powershell
mvn.cmd test
```

Result: `BUILD SUCCESS` in 1:21. Maven reported 39 executed tests, 0 failures, 0 errors, and 2 skipped tests. Exactly 0 integration tests executed in this run: the skipped cases are the two Testcontainers cases in `FlashSaleConcurrencyIntegrationTest`; the default test invocation does not enable the planned mandatory integration profile.

This is a unit/controller/infrastructure baseline only. It is not evidence that MySQL, Redis, Kafka, Flyway, or the future reservation integration suite is healthy.

## Frontend static baseline

Commands run from `app/frontend`:

```powershell
npm.cmd run lint
npm.cmd run typecheck
npm.cmd run build
```

| Gate | Result | Notes |
|---|---|---|
| ESLint | PASS | 0 errors, 4 existing unused-variable warnings |
| TypeScript | PASS | `tsc --noEmit` exited 0 |
| Production build | PASS | Next.js generated all 12 static pages and completed successfully |

The lint warnings are retained as baseline observations and are not silently attributed to the reservation work.

## Runtime smoke and JMeter baseline

Commands:

```powershell
./benchmark/smoke-local.ps1
./benchmark/run-jmeter.ps1
```

Both stopped at their first `POST /admin/benchmarks/reset` request with `No connection could be made because the target machine actively refused it` for `localhost:1122`. The JMeter script therefore never invoked the local JMeter binary and produced no valid `run.json`, `results.jtl`, consistency result, or HTML report. The exact attempted output directory was `benchmark/results/REDIS_LUA_WITH_COMPENSATION-20260809-101033/`; it contained only an empty `reset.json`, which was removed. That attempted directory is not a valid evidence artifact.

Environment observations captured during the same baseline window:

- Docker CLI 28.5.1 and Compose v2.40.3 were installed, but `docker version` could not connect to the `desktop-linux` engine because the Docker Desktop Linux pipe was unavailable.
- The global `jmeter --version` command was not on PATH; the repository-local `benchmark/jmeter/bin/jmeter.bat` is present and is the script's default executable.
- No backend/runtime health check passed, so no local `/orders` correctness or throughput number is claimed here.

## Existing contract and scope boundary

- The existing `/orders` business rejection contract remains HTTP 200 with envelope code 409; this baseline does not change that behavior.
- Historical benchmark directories under `benchmark/results/` are not current baseline measurements and are not reused as current output.
- Pre-existing dirty paths `.env`, `app/frontend/benchmark/`, `app/frontend/e2e/reports/index.html`, `docs/PORTFOLIO_DEMO_VIDEO_SCRIPT_VI.md`, and `reference/` were preserved and were not staged for this report.
- The missing runtime is an environment limitation for smoke/JMeter evidence, not a passing result. Phase 12/13/14/17 must rerun the runtime-dependent gates on an exact SHA before any performance or convergence claim is made.
