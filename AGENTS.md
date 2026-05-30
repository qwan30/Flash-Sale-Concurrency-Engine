<!-- gitnexus:start -->
# GitNexus - Code Intelligence

This project is indexed by GitNexus as **Flash-Sale-Concurrency-Engine** (1148 symbols, 2802 relationships, 89 execution flows). Use the GitNexus MCP tools to understand code, assess impact, and navigate safely.

> If any GitNexus tool warns the index is stale, run `npx gitnexus analyze` in terminal first.

## Always Do

- **MUST run impact analysis before editing any symbol.** Before modifying a function, class, or method, run `gitnexus_impact({target: "symbolName", direction: "upstream"})` and report the blast radius (direct callers, affected processes, risk level) to the user.
- **MUST run `gitnexus_detect_changes()` before committing** to verify your changes only affect expected symbols and execution flows.
- **MUST warn the user** if impact analysis returns HIGH or CRITICAL risk before proceeding with edits.
- When exploring unfamiliar code, use `gitnexus_query({query: "concept"})` to find execution flows instead of grepping. It returns process-grouped results ranked by relevance.
- When you need full context on a specific symbol - callers, callees, which execution flows it participates in - use `gitnexus_context({name: "symbolName"})`.

## When Debugging

1. `gitnexus_query({query: "<error or symptom>"})` - find execution flows related to the issue
2. `gitnexus_context({name: "<suspect function>"})` - see all callers, callees, and process participation
3. `READ gitnexus://repo/Flash-Sale-Concurrency-Engine/process/{processName}` - trace the full execution flow step by step
4. For regressions: `gitnexus_detect_changes({scope: "compare", base_ref: "main"})` - see what your branch changed

## When Refactoring

- **Renaming**: MUST use `gitnexus_rename({symbol_name: "old", new_name: "new", dry_run: true})` first. Review the preview - graph edits are safe, text_search edits need manual review. Then run with `dry_run: false`.
- **Extracting/Splitting**: MUST run `gitnexus_context({name: "target"})` to see all incoming/outgoing refs, then `gitnexus_impact({target: "target", direction: "upstream"})` to find all external callers before moving code.
- After any refactor: run `gitnexus_detect_changes({scope: "all"})` to verify only expected files changed.

## Never Do

- NEVER edit a function, class, or method without first running `gitnexus_impact` on it.
- NEVER ignore HIGH or CRITICAL risk warnings from impact analysis.
- NEVER rename symbols with find-and-replace - use `gitnexus_rename` which understands the call graph.
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
| `gitnexus://repo/Flash-Sale-Concurrency-Engine/context` | Codebase overview, check index freshness |
| `gitnexus://repo/Flash-Sale-Concurrency-Engine/clusters` | All functional areas |
| `gitnexus://repo/Flash-Sale-Concurrency-Engine/processes` | All execution flows |
| `gitnexus://repo/Flash-Sale-Concurrency-Engine/process/{name}` | Step-by-step execution trace |

## Self-Check Before Finishing

Before completing any code modification task, verify:
1. `gitnexus_impact` was run for all modified symbols
2. No HIGH/CRITICAL risk warnings were ignored
3. `gitnexus_detect_changes()` confirms changes match expected scope
4. All d=1 (WILL BREAK) dependents were updated

## Keeping the Index Fresh

After committing code changes, the GitNexus index becomes stale. Re-run analyze to update it:

```bash
npx gitnexus analyze
```

If the index previously included embeddings, preserve them by adding `--embeddings`:

```bash
npx gitnexus analyze --embeddings
```

To check whether embeddings exist, inspect `.gitnexus/meta.json` - the `stats.embeddings` field shows the count (0 means no embeddings). **Running analyze without `--embeddings` will delete any previously generated embeddings.**

> Claude Code users: A PostToolUse hook handles this automatically after `git commit` and `git merge`.

## CLI

| Task | Read this skill file |
|------|---------------------|
| Understand architecture / "How does X work?" | `.claude/skills/gitnexus/gitnexus-exploring/SKILL.md` |
| Blast radius / "What breaks if I change X?" | `.claude/skills/gitnexus/gitnexus-impact-analysis/SKILL.md` |
| Trace bugs / "Why is X failing?" | `.claude/skills/gitnexus/gitnexus-debugging/SKILL.md` |
| Rename / extract / split / refactor | `.claude/skills/gitnexus/gitnexus-refactoring/SKILL.md` |
| Tools, resources, schema reference | `.claude/skills/gitnexus/gitnexus-guide/SKILL.md` |
| Index, status, clean, wiki CLI commands | `.claude/skills/gitnexus/gitnexus-cli/SKILL.md` |

<!-- gitnexus:end -->

<!-- project-docs:start -->
# Project Documentation Surface

This repo is a flash-sale concurrency backend lab, not a full ticket-sales product. Keep docs, examples, and release language centered on stock correctness, Redis/MySQL consistency, benchmark reproducibility, and the optional operator dashboard.

Current local API documentation:

| Surface | URL |
|---|---|
| Swagger UI | `http://localhost:1122/swagger-ui.html` |
| OpenAPI JSON | `http://localhost:1122/v3/api-docs` |
| Lab API OpenAPI JSON | `http://localhost:1122/v3/api-docs/lab-api` |

Release docs live in `docs/`:

| File | Purpose |
|---|---|
| `docs/README.md` | documentation index |
| `docs/API_REFERENCE.md` | HTTP API and Swagger/OpenAPI contract |
| `docs/LAB_OPERATIONS.md` | run, smoke, benchmark, and troubleshooting commands |
| `docs/STOCK_STRATEGIES.md` | strategy behavior and benchmark interpretation |
| `docs/RELEASE_CHECKLIST.md` | release verification checklist |

When refreshing docs, treat Java source, Maven files, scripts, and runtime config as canonical. Use evergreen current-state wording; avoid changelog phrasing such as "newly added" or "recently updated".
<!-- project-docs:end -->

<!-- KHUYM:START -->
# Khuym Workflow

Use `khuym:using-khuym` first in this repo unless you are resuming an already approved Khuym handoff.

## Startup

1. Read this file at session start and again after any context compaction.
2. If `.khuym/onboarding.json` is missing or outdated, stop and run `khuym:using-khuym` before continuing.
3. If `.codex/khuym_status.mjs` exists, run `node .codex/khuym_status.mjs --json` as the first quick scout step.
4. If `.khuym/HANDOFF.json` exists, do not auto-resume. Surface the saved state and wait for user confirmation.
5. If `history/learnings/critical-patterns.md` exists, read it before planning or execution work.

## Chain

```
khuym:using-khuym
  -> khuym:exploring
  -> khuym:planning
  -> khuym:validating
  -> khuym:swarming
  -> khuym:executing
  -> khuym:reviewing
  -> khuym:compounding
```

## Critical Rules

1. Never execute without validating.
2. `CONTEXT.md` is the source of truth for locked decisions.
3. If context usage passes roughly 65%, write `.khuym/HANDOFF.json` and pause cleanly.
4. Treat `.khuym/state.json` as the single runtime state file for routing, current focus, and operator notes.
5. After compaction, re-read `AGENTS.md`, run `node .codex/khuym_status.mjs --json` if present, then re-open `.khuym/HANDOFF.json`, `.khuym/state.json`, and the active feature context before more work.
6. P1 review findings block merge.

## Working Files

```
.khuym/
  onboarding.json     <- onboarding state for the Khuym plugin
  state.json          <- single runtime state file for agents, tools, and humans
  HANDOFF.json        <- pause/resume artifact
  reservations.json   <- local file reservations for same-session Codex swarms

history/<feature>/
  CONTEXT.md          <- locked decisions
  discovery.md        <- research findings
  approach.md         <- approach + risk map

history/learnings/
  critical-patterns.md

.beads/               <- bead/task files when beads are in use
.spikes/              <- spike outputs when validation requires them
```

.codex/
  khuym_status.mjs    <- read-only scout command for onboarding, state, and handoff
  khuym_state.mjs     <- shared state helpers used by the scout command
  khuym_reservations.mjs <- local reservation helper used by swarming, executing, and hooks

## Codex Guardrails

- Repo-local `.codex/` files installed by Khuym are workflow guardrails, not optional decoration.
- Use `node .codex/khuym_status.mjs --json` as the preferred quick scout step when it is available.
- Treat `compact_prompt` recovery instructions as mandatory.
- Use `bv` only with `--robot-*` flags. Bare `bv` launches the TUI and should be avoided in agent sessions.
- If the repo is only partially onboarded, stay in bootstrap/planning mode and surface what is missing before implementation.

## Session Finish

Before ending a substantial Khuym work chunk:

1. Update or close the active bead/task if one exists.
2. Leave `.khuym/state.json` and `.khuym/HANDOFF.json` consistent with the current pause/resume state.
3. Mention any remaining blockers, open questions, or next actions in the final response.
<!-- KHUYM:END -->
