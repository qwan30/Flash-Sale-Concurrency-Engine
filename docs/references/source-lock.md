# Reservation Reliability Source Lock

This ledger freezes the external source material used by the reservation reliability upgrade. The reference repositories are read-only inputs; no change is made or committed in either reference checkout.

| Repository | Local path | Commit | License | Policy |
|---|---|---|---|---|
| Saleor | `reference/saleor` | `d0b4811ae4d8c75a9a93e8905c784c89688e49ff` | BSD-3-Clause | Direct reuse or Python-to-Java logic port is authorized; target repo only |
| OpenTelemetry Demo | `reference/opentelemetry-demo` | `c983708e6e308f8395a6c9ce8ddb89705f910c1f` | Apache-2.0 | Direct reuse or cross-language logic port is authorized; target repo only |

## Verification at lock time

- Target checkout: `6d041980f0b97e3104f3db9cc095e596e89e651e`
- Saleor checkout: `d0b4811ae4d8c75a9a93e8905c784c89688e49ff`
- OpenTelemetry Demo checkout: `c983708e6e308f8395a6c9ce8ddb89705f910c1f`
- Both reference working trees returned no entries from `git status --short`.
- The target working tree contains pre-existing unrelated changes in `.env`, `app/frontend/benchmark/`, `app/frontend/e2e/reports/index.html`, `docs/PORTFOLIO_DEMO_VIDEO_SCRIPT_VI.md`, and `reference/`; these paths remain outside this documentation checkpoint's staging scope.

The plan's conceptual paths are normalized to the pinned checkouts' actual layouts: Saleor's reservation sources are under `saleor/warehouse/`, and the OpenTelemetry Demo's flag, Java service, load-generator, and telemetry registry sources are under `src/flagd/`, `src/ad/`, `src/load-generator/`, and `telemetry-schema/` respectively.
