# Reference Extraction Order

The following order is locked for any later reuse or logic port. The target implementation must be written in Java/Spring and Redis terms after the behavior has been understood; Python or reference-framework structure is not copied mechanically.

1. Saleor `saleor/warehouse/models.py`: reservation fields, expiry and indexes.
2. Saleor `saleor/warehouse/reservations.py`: availability calculation, stable stock locking and all-or-nothing semantics.
3. Saleor reservation/task tests: race and expiry scenarios.
4. Target repo: rewrite the lifecycle around single-ticket Redis-first behavior.
5. OpenTelemetry Demo `src/flagd/demo.flagd.json` and `src/ad/src/main/java/oteldemo/AdService.java`: finite fault catalog and trace-correlated injection.
6. OpenTelemetry telemetry schema and Collector sanitizer: bounded naming/cardinality.
7. OpenTelemetry load generator `src/load-generator/script.js`: workload manifest and trace propagation; keep JMeter as target v1 runner.

## Mapping ledger

Every copied or ported unit must add a row to the ledger below before the corresponding target commit. A row is not evidence that the target behavior is complete; the target test and the observed RED result are the evidence.

| Reference repository and pinned SHA | Source file and function/class | Reuse mode | Target file and symbol | Behavior retained | Behavior intentionally changed | Test written before target implementation |
|---|---|---|---|---|---|---|
| Saleor `d0b4811ae4d8c75a9a93e8905c784c89688e49ff` | `saleor/warehouse/models.py:Stock`, `Reservation`, `ReservationQuerySet` | `DESIGN_REFERENCE` | Phase 1/2 reservation schema and domain records | Explicit reserved quantity, expiry, and stock-account invariants | Java records, MySQL tables, one ticket item and one location | `PENDING` until the named target RED test is observed |
| Saleor `d0b4811ae4d8c75a9a93e8905c784c89688e49ff` | `saleor/warehouse/reservations.py:reserve_stocks`, `_create_stock_reservations`, `reserve_stocks_and_preorders` | `LOGIC_PORT` | Persistence and terminal reservation services | Stable locking, availability calculation, all-or-nothing behavior | Redis admission precedes durable MySQL commit; recovery journal is added | `PENDING` until the named target RED test is observed |
| Saleor `d0b4811ae4d8c75a9a93e8905c784c89688e49ff` | `saleor/warehouse/tests/test_stock_reservations_management.py` reservation tests; `test_tasks.py` expiry tasks | `DESIGN_REFERENCE` | Reservation persistence, lifecycle, and recovery tests | Race, expiry, and release safety scenarios | Testcontainers MySQL/Redis and Java test fixtures | `REQUIRED` for each target port |
| OpenTelemetry Demo `c983708e6e308f8395a6c9ce8ddb89705f910c1f` | `src/flagd/demo.flagd.json` finite feature/fault values | `DESIGN_REFERENCE` | Chaos profile fault catalog | Default-off, finite scenario values | Local `@Profile("chaos")` injector and no production endpoint | `PENDING` until the chaos isolation RED test is observed |
| OpenTelemetry Demo `c983708e6e308f8395a6c9ce8ddb89705f910c1f` | `src/ad/src/main/java/oteldemo/AdService.java:AdService.getAds`, `getRandomAds` | `DESIGN_REFERENCE` | Reservation telemetry and fault points | Stable span/metric emission at bounded operation points | Brave/Micrometer remains the default; no simultaneous bridge migration | `PENDING` until the telemetry RED test is observed |
| OpenTelemetry Demo `c983708e6e308f8395a6c9ce8ddb89705f910c1f` | `telemetry-schema/manifest.yaml`, `services/ad.yaml`, `attributes/feature_flag.yaml`, `otel-config.yml` | `DESIGN_REFERENCE` | `docs/observability/` schema and sanitizer rules | Schema-first names and bounded attributes | Reservation-specific names and no raw identifiers | `PENDING` until the telemetry RED test is observed |
| OpenTelemetry Demo `c983708e6e308f8395a6c9ce8ddb89705f910c1f` | `src/load-generator/script.js:otelHeaders`, `httpScenario`, `browserScenario` | `DESIGN_REFERENCE` | `benchmark/` reservation JMeter/k6 harness | Synthetic marker, traceparent propagation, scenario metadata | JMeter is target v1; k6 is a later reversible extension | `PENDING` until the harness RED test is observed |

Direct copies must preserve necessary copyright/license notices. A Python-to-Java port retains behavioral semantics and test cases, not Python framework structure. The target repository may reuse source under the user-confirmed permissions; the reference clones themselves remain unmodified at their pinned SHAs.
