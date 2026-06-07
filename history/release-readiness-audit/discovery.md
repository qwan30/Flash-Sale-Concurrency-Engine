# Release Readiness Audit - Discovery

**Feature slug:** release-readiness-audit
**Date:** 2026-06-06
**Mode:** approved repair slice

## Current Evidence

- Khuym onboarding is complete and `.codex/khuym_status.mjs --json` reports no handoff file.
- GitNexus has multiple indexed repos, so all GitNexus calls must use `repo: "Flash-Sale-Concurrency-Engine"`.
- `.gitnexus/meta.json` and `mcp__gitnexus.list_repos` show the index at commit `e64e4bb6cc9f683d14fd3041a839f40d40a3ac2b` with embeddings.
- The working tree already contains documentation reorganization changes: canonical release docs moved under `docs/reference`, `docs/architecture`, `docs/performance`, and `docs/operations`.
- Root `README.md` still links to root documentation files such as `docs/API_REFERENCE.md`, `docs/REVIEWER_GUIDE.md`, `docs/ARCHITECTURE.md`, `docs/BENCHMARKING.md`, `docs/DASHBOARD_GUIDE.md`, and `docs/RELEASE_CHECKLIST.md`.
- `docs/README.md` still links to deleted legacy learning filenames such as `process-learn/PHASE_INDEX.md`, `PHASE1_STARTUP.md`, `PHASE5_LEARNING_GUIDE.md`, and related phase files.
- Learning images were moved from `docs/learn/` to `docs/learn-image/`; docs still mention `learn/`.
- `docs/flash_sale_knowledge_visualizer_bmw_m_theme_latest_enhanced.html` is a large generated artifact and should not become the source of truth unless regenerated and link-clean.
- The frontend backend proxy at `app/frontend/src/app/api/backend/[...path]/route.ts` currently forwards any path under `/api/backend/*`.
- `app/backend/xxxx-start/src/main/resources/application.yml` currently exposes all actuator web endpoints and always shows health details.

## Confirmed Gaps

1. Documentation entrypoints are broken after the category reorganization.
2. Legacy process-learning filenames no longer exist but are still referenced.
3. Image folder naming is inconsistent between file layout and documentation.
4. The generated HTML visualizer risks preserving stale embedded links.
5. The dashboard proxy lacks a route allowlist even though all dashboard calls are known.
6. Actuator defaults are too broad for a lab that may be run outside a local machine.

## Constraints

- Keep the project framed as a flash-sale backend concurrency lab.
- Do not change order, stock, benchmark, or strategy behavior.
- Treat Java source, Maven files, scripts, and runtime config as canonical.
- Source edits require GitNexus impact checks before modifying symbols.
- `requestJson` and proxy behavior are high-blast-radius dashboard paths and require frontend validation.
