# Operator Dashboard Design Notes

This frontend is a secondary operator surface for the backend reliability lab. It should help a reviewer run experiments, inspect benchmark results, and explain stock consistency. It is not a consumer ticket sales product.

## Product Role

- Default users are operators, reviewers, and AI coding agents validating backend behavior.
- The UI should prioritize lab controls, health, stock snapshots, strategy comparison, and reproducibility.
- Event and order screens are fixtures for exercising `/orders`, `/tickets`, and monthly order-table reads.
- Keep public-product language out of the interface.

## Screen Priorities

1. Control desk: reset stock, warm Redis, refresh health, and run consistency checks.
2. Benchmark report: compare throughput, latency, accepted/rejected orders, oversold count, and drift.
3. Consistency view: show Redis stock, DB stock, order count, oversold rows, and Redis-DB mismatch count.
4. Order probe: submit a single controlled order request with a selected strategy and idempotency key.
5. Order traces: inspect stored order rows by demo user and month.

## Visual Direction

- Use a restrained grayscale palette so metrics and status badges carry the signal.
- Prefer dense, scan-friendly dashboards over marketing layouts.
- Cards are for individual metric blocks or tool panels only; do not turn whole page sections into decorative cards.
- Keep headings compact inside dashboards and reserve large display type for page titles.
- Use icons for actions and status where they improve scanning.

## Copy Rules

- Use lab terms: strategy, stock, Redis, DB, consistency, drift, benchmark, warmup, reset, order probe.
- Avoid consumer-sales language; describe backend actions as probes, traces, fixtures, and lab controls.
- Make conclusions concrete: say what was measured and whether oversell/drift occurred.
- Keep any future UI work subordinate to backend verification.

## Verification Expectations

- Operator actions should expose enough state to verify the backend result without reading logs.
- Benchmark views should keep correctness columns visible beside throughput and latency.
- Empty/error states should name the backend operation that failed.
- All default IDs and months should match the benchmark fixtures in `PROJECT_CONTEXT.md`.
