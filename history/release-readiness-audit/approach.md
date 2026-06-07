# Release Readiness Audit - Approach

**Feature slug:** release-readiness-audit
**Date:** 2026-06-06
**Approved user intent:** implement the provided repair plan in a fresh context.

## Work Shape

Use one standard repair phase with two slices:

1. Restore documentation integrity and compatibility entrypoints.
2. Harden lab edges without changing business behavior.

This is not a product expansion, a benchmark refresh, or an order-flow change.

## Planned Repairs

- Add redirect stubs at root documentation entrypoints for moved release docs:
  - `docs/API_REFERENCE.md`
  - `docs/REVIEWER_GUIDE.md`
  - `docs/ARCHITECTURE.md`
  - `docs/BENCHMARKING.md`
  - `docs/DASHBOARD_GUIDE.md`
  - `docs/RELEASE_CHECKLIST.md`
- Keep categorized docs as canonical:
  - `docs/reference/*`
  - `docs/architecture/*`
  - `docs/performance/*`
  - `docs/operations/*`
- Add process-learning redirect stubs for deleted legacy names while keeping the new `00-*` and phase-named files canonical.
- Update docs navigation so root README and docs hub resolve without dead internal links.
- Standardize learning image references on the existing moved folder, `docs/learn-image/`.
- Remove or quarantine the generated standalone HTML visualizer unless it can be validated as link-clean and reproducibly generated.
- Add a backend proxy allowlist for the dashboard's known Spring Boot paths.
- Tighten actuator exposure to `health,prometheus` and hide sensitive health details by default.

## Risk Map

| Area | Risk | Mitigation |
|---|---|---|
| Docs redirects | Low | Validate Markdown links after edits. |
| Process-learning redirects | Low | Keep redirects short and point to current canonical files. |
| Generated visualizer | Medium | Do not treat generated HTML as source of truth; leave it out if stale. |
| Dashboard proxy | High | Run GitNexus impact for `proxy` and frontend lint/typecheck/build. |
| `requestJson` callers | High | Avoid editing `requestJson` unless needed; run impact if edited. |
| Actuator config | Medium | Preserve `/actuator/health` and `/actuator/prometheus`; run backend tests. |

## Verification

- Markdown and image link validation: zero broken internal links, excluding HTTP URLs.
- GitNexus `detect_changes(scope="all")` confirms expected docs/proxy/config scope.
- Backend tests: `mvn -pl app/backend/xxxx-start -am test`.
- Frontend checks from `app/frontend`: `npm run lint`, `npm run typecheck`, `npm run build`.
- Khuym status re-run at closeout.
