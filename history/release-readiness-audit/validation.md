# Release Readiness Audit - Validation

**Feature slug:** release-readiness-audit
**Date:** 2026-06-06
**Current work:** Repair docs integrity and harden lab edges.

## Reality Gate

- The branch already contains the documentation reorganization and generated/moved asset changes that the repair plan targets.
- The requested implementation is compatible with `CONTEXT.md`: documentation gaps are fixed in `docs/`, and source hardening is limited to a validated later execution plan supplied by the user.
- No order, stock, strategy, benchmark, Maven, or data-model behavior is required for this repair.

## Feasibility Matrix

| Assumption | Evidence | Result |
|---|---|---|
| Categorized docs are canonical. | Existing files under `docs/reference`, `docs/architecture`, `docs/performance`, and `docs/operations`. | Pass |
| Root doc entrypoints are missing. | Root README links to `docs/API_REFERENCE.md`, `docs/ARCHITECTURE.md`, and peers while only categorized files exist. | Pass |
| Process-learning compatibility is needed. | `docs/README.md` and learning files still reference deleted `PHASE*` names. | Pass |
| Image folder consistency is needed. | Files exist under `docs/learn-image/` while docs still mention `learn/`. | Pass |
| Proxy allowlist can be scoped. | All dashboard API calls are centralized in `app/frontend/src/lib/api.ts`. | Pass |
| Actuator can be tightened without removing required checks. | Required docs only need `/actuator/health` and `/actuator/prometheus`. | Pass |

## Required Pre-Edit Checks

- Run `mcp__gitnexus.impact` before editing source symbols:
  - `proxy`
  - `GET`
  - `POST`
  - `requestJson` only if edited
- Warn before proceeding if any HIGH or CRITICAL impact is found.

## Validation Decision

Current work is feasible. The user prompt supplies execution approval for this repair slice. Proceed with implementation, then run link validation, GitNexus change detection, backend tests, frontend lint/typecheck/build, and Khuym status closeout.

## Closeout Evidence

- Markdown/image link validation: `78` Markdown files checked, `0` broken local links.
- GitNexus impact:
  - `proxy`: LOW risk, direct callers `GET` and `POST`.
  - `GET`: LOW risk, no upstream dependents.
  - `POST`: LOW risk, no upstream dependents.
  - `requestJson`: HIGH risk, not edited; retained as the central dashboard client.
- GitNexus `detect_changes(scope="all")`: LOW risk, expected proxy/config/docs scope, no affected processes.
- Backend tests: `mvn -pl app/backend/xxxx-start -am test` passed. Non-integration tests passed; Docker-gated integration tests remained skipped by configuration.
- Frontend checks from `app/frontend` passed:
  - `npm run lint`
  - `npm run typecheck`
  - `npm run build`
- Khuym status: onboarding complete, no handoff, state phase `execution-complete`.
