# Release Readiness Audit - Phase Plan

**Phase:** 1
**Name:** Repair docs integrity and harden lab edges
**Status:** approved by user prompt

## Scope

- Documentation navigation, compatibility redirects, learning-path redirects, image-folder consistency, and generated visualizer handling.
- Frontend proxy allowlist for the known dashboard backend paths.
- Actuator exposure defaults limited to health and Prometheus with non-sensitive health details.

## Out Of Scope

- Changing order creation, stock deduction, benchmark scripts, strategy behavior, data model, or dashboard UX.
- Adding authentication or profile-gated admin endpoints.
- Running JMeter or Docker-gated benchmark scenarios.

## Exit Criteria

- Root README and docs hub open without dead internal Markdown/image links.
- Legacy doc paths resolve to current canonical docs via redirect stubs.
- Generated HTML does not remain as an unvalidated source-of-truth artifact.
- Proxy rejects unknown backend paths before forwarding.
- Actuator exposes only `health` and `prometheus` by default.
- GitNexus impact and change detection are recorded.
- Backend and frontend verification commands pass or failures are reported with concrete output.
