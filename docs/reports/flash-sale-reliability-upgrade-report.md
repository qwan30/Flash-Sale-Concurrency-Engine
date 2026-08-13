# Flash-sale reservation reliability upgrade

## Certification status

`NO-GO — implementation and CI are green, but the plan's final effectiveness certification is incomplete`

The current checkout contains the reservation lifecycle, recovery, admission, observability, UI,
benchmark, and optional OTel evidence lanes. The implementation PR is mergeable and its required
CI checks pass, but the plan still requires same-SHA baseline comparison, deterministic fault
effectiveness evidence, and a complete manual browser control audit. Those gates are not silently
treated as complete.

## Current verification overlay — 2026-08-13

- Candidate SHA: `b5d98de374a0d5f3729b1e3cdbe54af7b730a23c` on PR branch
  `pr/phase-17-release`. The exact SHA is pushed to the PR head and the PR is reported by GitHub
  as `MERGEABLE` and `OPEN`. This report does not authorize or perform the merge.
- Local verification is green: frontend client tests 26/26, controller reactor Maven tests 58/58,
  full Docker-backed `flashsale-integration` reactor tests passed, and current Playwright control
  desk/smoke/screenshot suites passed 17/17 (5 operator-control plus 12 smoke/screenshot tests).
- GitHub checks for this exact SHA are all successful: backend unit, integration, observability,
  package, frontend lint/typecheck/build, infrastructure validation, and Vercel deployment checks.
- A current-SHA healthy JMeter run (`benchmark/results/reservation-healthy-20260813-093337Z`)
  executed 5,000 samples at 100 threads and recorded p95 74 ms, p99 1,412 ms, 46 successful
  creates, zero final drift, zero pending journal/outbox entries, and convergence in 8.178 s.
  HTTP 429/503 admission responses were expected by the harness. The run is **NO-GO** because
  `HealthyP95BaselineMs` was intentionally not supplied; no same-machine pre-change baseline is
  available in this candidate, so no latency-regression claim is made.
- The current JMeter run does not complete Phase 14: no exact-SHA before/after effectiveness
  report exists for all five deterministic fault points, no k6 runtime measurement is available,
  and the manual connected-Chrome/in-app-browser control matrix is still outstanding. Automated
  Playwright evidence is not a substitute for that manual audit.
- The only remaining working-tree change is the pre-existing local screenshot
  `app/frontend/e2e/screenshots/local-booking-demo.png`; it was not modified or committed by this
  review.

## Current verification overlay — 2026-08-11

- HEAD remains `789f61fc4fafb4faefa3285f5250d13d7f7458dd` on `feat/reservation-foundation`; no commit
  or push was made in this continuation.
- The plan checklist is now `98/144` items checked (`68.1%`). This counts completed source/test/
  documentation steps; commit, Docker runtime, browser, effectiveness and final certification gates
  remain intentionally unchecked.
- The latest non-Docker module suite passed 157 tests: domain 8, application 110, infrastructure 2,
  and controller 37. The default start suite passed 68 test cases with 57 Docker-backed cases
  skipped by the default `flashsale.integration=false` gate; its 11 non-Docker cases passed.
- The root Maven integration profile is now explicitly wired to Surefire: the default property
  evaluates to `false`, while `-Pflashsale-integration` evaluates to `true` and the effective POM
  passes `flashsale.integration=true` into the test JVM. Docker-backed runtime execution remains
  unverified.
- The strengthened `ReservationEndToEndIntegrationTest` (MySQL, Redis and Kafka containers;
  duplicate replay plus confirm/release/expire and outbox assertions) compiles with
  `mvn ... -DskipTests test-compile`. Its Docker runtime was not certified: the latest 500-attempt
  execution timed out while Docker was unresponsive, and `com.docker.service` is currently
  `Stopped`.
- Frontend client tests pass 6/6 through `npm run test:client`; lint has zero errors and four
  existing warnings; typecheck and production build pass. No connected-browser or in-app-browser
  control audit was performed.
- The customer journey now visibly states `Demo session only; no login or real authentication is
  used.` and the reservation E2E spec asserts that text; the E2E browser runtime was not executed.
- The latest non-Docker JaCoCo report was generated with the 110-test application suite. The
  application reservation package now reaches 80.21% line (`1094/1364`) / 57.01% branch
  (`427/749`); the infrastructure reservation slice remains 0% line / 0% branch (`0/802` lines;
  the exercised paths are Testcontainers-backed), and the controller slice is 55.0% line
  (`126/229`) / 49.6% branch (`62/125`). The overall
  plan's 80% line across all newly added reservation packages and 90% correctness-branch
  expectations are therefore still open.
- The first bounded follow-up review found three P1 release-gate issues covering an ungated
  Testcontainers class, missing retry scheduling when repair context is absent, and unsafe retry
  behavior when Redis operation state is absent or expired. The second review found three further
  P1s and four P2s. The third review returned `FAIL` with four P1s and one P2: SOLD_OUT
  disposition after fence/ticket changes, non-atomic terminal-mirror evidence, exhausted repair
  claims, shared UI polling halt, and evidence-report overclaiming. The fourth review returned
  `FAIL` with two P1s and one P2: retry-attempt off-by-one behavior, incomplete Redis operation
  identity evidence, and replay error handling. RED-to-GREEN source/test remediation is now
  present for those findings, but a fifth independent review is still required; no review `PASS`
  or release claim is made.

## Verified in this continuation

- Backend unit/application tests remained green after the lifecycle and recovery work; the focused
  recovery suite passes 34/34 and the latest complete non-Docker module suite passes 157/157.
  The new scheduler, command-validation and stock-port contract tests pass 11/11; they raise the
  application reservation slice above 80% line coverage without changing the Docker/runtime gate.
- The full integration profile also compiles through all six reactor modules with
  `mvn.cmd -pl app/backend/xxxx-start -am -Pflashsale-integration -DskipTests test-compile`.
  This confirms source/test compilation only; the Docker-backed tests remain unexecuted.
- A durable `REDIS_APPLYING` journal marker now separates a known retryable `RECEIVED` recovery
  window from the ambiguous “Redis apply may already have happened” window. The marker is covered
  in application tests, persistence-state mappings, controller 202 processing responses, telemetry,
  fixture queries, and a Flyway follow-up migration. This is source/test evidence only; the migration
  and marker behavior have not run against the unavailable Docker MySQL/Redis stack.
- `ReservationChaosIntegrationTest` now contains six deterministic Docker-gated scenarios for the
  five named fault points plus the finite catalog contract. The class compiles and the default suite
  records all six as skipped because `flashsale.integration=false`; its convergence helper enforces a
  30-second deadline when the Docker lane is enabled. `ReservationToxiproxyIntegrationTest` now
  covers both Redis and Kafka protocol paths, but its two cases are also skipped by the default gate;
  no Docker runtime, Kafka partition recovery, or live convergence is claimed.
- Historical MySQL/Testcontainers outbox claim race evidence showed two concurrent workers claim
  one pending event only once before lease expiry. It is not a current Docker runtime result.
- The MySQL/Redis/Kafka end-to-end invariant test source covers 500 concurrent attempts against 100
  units, terminal confirm/release/expire actions, duplicate replay, outbox publication, non-negative
  stock, Redis/MySQL available parity, one order per confirmed reservation, and zero pending journal
  states. It is a compile-verified test definition only in the current overlay; the latest Docker
  runtime attempt timed out and is not a pass. The fixture explicitly gates recovery/expiry
  schedulers so a future runtime measurement is not raced by background workers.
- Historical Redis/Toxiproxy protocol evidence showed a downstream timeout injected through the
  proxy, Redis client failure, toxic removal, and PONG recovery. It was not rerun in the current
  Docker-unavailable overlay and does not prove full chaos convergence.
- Frontend typecheck, lint, and production build pass; existing lint warnings are pre-existing.
- Frontend client polling/error-contract tests pass 6/6. The component now shares an explicit
  `pollingHalted` gate across PROCESSING and RESERVED timers for terminal errors; component timer
  execution and browser control remain unverified.
- Telemetry gauge reads now fail closed with `NaN` plus the bounded
  `flashsale.telemetry.read.failure` counter when DB/Redis evidence is unavailable. Outbox retry
  documentation and its max-attempt behavior test now state the explicit operator-action boundary.
- Observability/chaos contract tests pass; the dashboard and OTel Compose overlays render.
- The pinned OTel Demo source was re-audited at
  `c983708e6e308f8395a6c9ce8ddb89705f910c1f`; the clean reference confirms the current k6/
  `xk6-otel`/traceparent/synthetic-traffic shape. The adaptation boundary is documented in
  `docs/observability/otel-migration.md`; no OTel/k6 runtime or parity certification is claimed.
- The historical strategy contract suite passed 6 tests: Redis-first and MySQL-conditional
  duplicate, sold-out, closed-admission, stale-fence, concurrent-invariant, and terminal-race
  cases. The Testcontainers-backed strategy runtime is not current evidence.
- The latest default full start reactor passed with every Testcontainers class, including
  `ReservationStrategyContractTest`, skipped unless `-Pflashsale-integration` is explicitly selected.
  This verifies the default no-Docker gate only; it is not evidence that the MySQL/Redis/Kafka or
  Toxiproxy runtime lane passed. The controller slice previously passed 10/10, including explicit
  `X-Reservation-Strategy: MYSQL_CONDITIONAL` routing, and the real Testcontainers HTTP reset
  contract previously passed 1/1; those runtime results remain separately bounded to their earlier
  environment and are not a current same-SHA certification.
- The effectiveness gate now fails closed on missing identity-bound run/baseline, metrics,
  convergence, fault-timeline, and UI artifacts. The current invocation correctly returned
  `NO-GO` because the browser/UI and controlled-fault evidence are still absent and both
  working-tree identities are dirty.
- The reservation fixture reset contract is now implemented at
  `POST /admin/reservation-fixtures/reset`. It requires `X-Flashsale-Synthetic: true` plus the
  configured `X-Flashsale-Fixture-Token`, clears reservation orders/orphan-aware outbox rows/
  journals/reservations, reseeds MySQL and Redis, and returns separate durable/Redis stock proof.
  The controller contract passed 10/10, the application service contract passed 3/3, and the
  Testcontainers HTTP contract previously passed 1/1. The runner now unwraps the standard `result`
  response envelope and records the complete reset response; the HTTP result is historical runtime
  evidence, not a current Docker pass.
- Earlier frontend verification passed typecheck and production build; lint had four non-blocking
  existing warnings. The mocked reservation journey passed 1/1 in historical Chromium evidence;
  no current browser control audit was performed.
- The historical fixture-reset integration attempt was first blocked by a stopped Docker Desktop Linux
  engine, but after the local daemon was started the real MySQL/Redis HTTP contract passed.
  Connected/in-app browser setup still failed before navigation because the browser connector
  could not create its kernel assets. No live workload, browser control pass, or same-SHA
  effectiveness claim is inferred from that unavailable browser runtime.
- The evidence contract is now identity-bound. `GET /admin/reservation-fixtures/evidence` is
  synthetic-header/token gated and reports durable invariant state, Redis mirror parity,
  pending journal/outbox counts, duplicate counts, oversell/negative-stock observations and
  final drift. The endpoint is exercised after reset by the real Testcontainers HTTP test;
  the application gate/evidence slice passed 5/5, the controller slice passed 19/19, and the
  reset/evidence integration passed 2/2 in that historical Docker environment. The latest package
  build only proves source compilation when integration tests are skipped.
- Fresh local JMeter evidence is present, but remains non-certifying because the checkout is
  dirty and the required UI/fault artifacts are not present. Healthy REDIS_FIRST
  (`benchmark/results/reservation-healthy-20260811-034324Z`) produced 5,000 samples,
  49 accepted units, p95 36 ms, zero oversell/negative stock/duplicates, and convergence in
  1.086 s with invariant/parity/pending gates passing. Healthy MYSQL_CONDITIONAL
  (`benchmark/results/reservation-healthy-20260811-034345Z`) produced 5,000 samples,
  32 accepted units, p95 35 ms, zero correctness violations, and convergence in 1.085 s.
- The duplicate-retry lane
  (`benchmark/results/reservation-duplicate-retry-20260811-034454Z`) produced 10,000
  samples, including 4 HTTP 200 duplicate-retry responses; durable evidence reported zero
  duplicate reservations/orders, zero drift, and convergence in 0.037 s. The overload terminal
  lane (`benchmark/results/reservation-overload-20260811-034406Z`) produced 5,021 samples,
  21 terminal releases with 100% create-to-terminal coverage, 100% terminal success, terminal
  p95 354 ms, 4,800 HTTP 429 and 179 HTTP 503 create rejections, and convergence in 1.079 s.
- The paired healthy effectiveness invocation using the latest MYSQL run and REDIS baseline
  (`034345Z` versus `034324Z`)
  returned `NO-GO` as intended. The generated report records the remaining missing `ui.json`,
  dirty run/baseline identities, and absent controlled-fault timestamps for
  `AFTER_REDIS_BEFORE_DB`, `AFTER_DB_COMMIT_BEFORE_RESPONSE`, `REDIS_MIRROR_TIMEOUT`,
  `KAFKA_UNAVAILABLE`, and `CONFIRM_EXPIRE_RACE`. The artifacts carry SHA
  `789f61fc4fafb4faefa3285f5250d13d7f7458dd`, completed SHA/status snapshots, and digests for
  metrics/consistency/convergence, but that SHA is not a clean release identity.
- A prior adversarial review found no P0 issues and closed the two evidence weaknesses: terminal
  metrics now require 100% coverage of successful creates, and the gate cross-checks convergence
  components against consistency and metrics artifacts. The latest fourth review still returned
  `FAIL`; its source/test remediation is recorded above, but the fifth review, Docker-backed
  runtime, browser/effectiveness evidence, and exact-SHA evidence remain open. No multi-replica
  certification claim is made.

## Still required before certification

Kafka protocol fault coverage, a live 30-second convergence run, a measured JMeter or k6 baseline
comparison, Hikari pending-connection telemetry for terminal-priority claims, the complete browser
control audit, coverage thresholds, and reference-cleanliness checks remain open. The Redis and
MySQL lanes were measured locally, but the certifying strategy matrix remains `NOT_RUN` because
the MySQL ordering implementation is documented as JVM-local until a distributed ordering contract
is measured. Final exact-SHA documentation remains open.
