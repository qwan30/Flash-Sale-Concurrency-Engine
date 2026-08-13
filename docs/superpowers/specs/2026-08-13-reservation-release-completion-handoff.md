# Reservation Reliability Release Completion Handoff

> **For the next agentic session:** execute this handoff in order. Do not claim that the plan is
> complete until every gate in the acceptance section has passing, exact-SHA evidence.

**Created:** 2026-08-13 (pre-merge handoff snapshot; always re-check live GitHub state first)  
**Repository:** `qwan30/Flash-Sale-Concurrency-Engine`  
**Default branch:** `master` (there is no `main` branch)  
**Checkout:** `D:\projects\tipjs-project\xxxx.com-section-ddd-24-27042025\Flash-Sale-Concurrency-Engine`

## 1. Current state

The implementation stack is complete enough to merge as an implementation/CI-green change, but
it is not yet a fully certified completion of `plan.md`.

| Item | Current evidence |
|---|---|
| Final PR head | `4ce5649cfb5a24c77c05174950955587c7debe15` |
| Final PR | [PR #6](https://github.com/qwan30/Flash-Sale-Concurrency-Engine/pull/6) |
| PR #6 base | `program/reservation-reliability` |
| PR #6 state | `OPEN`, `MERGEABLE`, all current checks passing |
| Stacked PRs | #2, #3, #4, #5, #6 all contain the phase chain and currently target `program/reservation-reliability` |
| Local branch | `pr/phase-17-release-v2` |
| Local unrelated change | `app/frontend/e2e/screenshots/local-booking-demo.png` is dirty and must be preserved |
| Current JMeter evidence | `benchmark/results/reservation-healthy-20260813-093337Z` |
| JMeter result | 5,000 samples, p95 74 ms, p99 1,412 ms, convergence 8.178 s, zero drift, zero pending journal/outbox |
| JMeter verdict | `NO-GO` because no same-machine pre-change `HealthyP95BaselineMs` was supplied |
| Manual browser audit | Not completed; no connected Chrome/in-app control matrix exists |
| Deterministic effectiveness report | Not completed for all five required fault points |
| k6 runtime | Unavailable in the previous session; no k6 certification claim exists |

The prior source/security fixes are already pushed. Do not redo them unless a new test exposes a
regression. The current reports intentionally say `NO-GO`, and the late Phase 13–17 checkboxes in
`plan.md` are intentionally unchecked.

## 2. Non-negotiable constraints

1. Use the current checkout and sequential branches only. Do not create or use a Git worktree.
2. Preserve unrelated changes. In particular, do not reset, overwrite, restore, stash, or commit
   `app/frontend/e2e/screenshots/local-booking-demo.png` without an explicit user decision.
3. Never commit benchmark output, tokens, `.env.local`, logs, screenshots, or generated Playwright
   reports unless the task explicitly requires that exact evidence file.
4. Keep all benchmark/control tokens in environment variables. Never put a token in a source file,
   command committed to history, report text, screenshot, or PR body.
5. A green Maven/CI run proves tests only. It does not prove the performance baseline, five-fault
   effectiveness, or manual browser gates.
6. A successful merge does not make the project fully certified. The final report must distinguish
   implementation readiness, merge status, and plan certification.
7. If a required dependency or browser surface is unavailable, stop that gate as `NO-GO` and record
   the exact command and error. Do not substitute static inspection for runtime evidence.

## 3. Required merge path: merge the complete stack into `master`

PRs #2–#6 are stacked: PR #6 contains the ancestor commits from PRs #2–#5. The requested
alternative to merging into `program/reservation-reliability` is therefore to merge PR #6 directly
into the repository default branch `master`; do not merge the five PRs independently and do not
merge `program/reservation-reliability` separately afterward.

### Task 3.1 — Preflight and retarget PR #6

Run from the repository root:

```powershell
git status --short --untracked-files=all
git fetch origin master program/reservation-reliability pr/phase-17-release
gh pr view 6 --json baseRefName,headRefName,headRefOid,state,mergeable,mergeStateStatus,url
```

Expected preflight facts:

- PR #6 head is `4ce5649cfb5a24c77c05174950955587c7debe15` or a deliberately documented newer head.
- The unrelated screenshot remains the only expected local dirty file.
- No secret or generated artifact is staged.

Retarget PR #6 to the default branch:

```powershell
gh pr edit 6 --base master
```

After retargeting, verify that the PR diff against `master` contains the complete phase chain and
that no unexpected conflict or unrelated file appears:

```powershell
gh pr view 6 --json baseRefName,headRefName,headRefOid,mergeable,mergeStateStatus,url
gh pr diff 6 --stat
gh pr checks 6
```

### Task 3.2 — Wait for post-retarget CI and merge PR #6

Do not merge while any required check is pending or failed. Wait for the new checks:

```powershell
gh pr checks 6 --watch --interval 20
```

All required checks must be `pass`, including backend unit/integration/observability/package,
frontend lint/typecheck/build, infrastructure validation, and Vercel checks.

Merge with a merge commit so the phase commits remain traceable:

```powershell
gh pr merge 6 --merge --delete-branch=false
```

Verify the result:

```powershell
gh pr view 6 --json state,mergedAt,mergeCommit,baseRefName,headRefName,url
git fetch origin master
gh api repos/qwan30/Flash-Sale-Concurrency-Engine/git/ref/heads/master --jq '.object.sha'
gh pr checks 6
```

The merge is successful only when PR #6 is `MERGED`, `master` contains the complete PR head, and
the post-merge default-branch checks are green. Do not close PRs #2–#5 until this verification is
complete. Then mark them superseded with a comment linking PR #6 and close them without deleting
their historical branches:

```powershell
$body = "Superseded by merged PR #6, which contains this stacked phase and its ancestors and was merged into master."
2,3,4,5 | ForEach-Object { gh pr comment $_ --body $body; gh pr close $_ --comment $body }
```

If retargeting creates conflicts, stop and report the conflict paths. Do not force-push or rewrite
the phase branches as a shortcut.

## 4. Remaining certification work

### Task 4.1 — Resolve the dirty-evidence decision

The benchmark/effectiveness runner requires a clean working tree in its `git.json`. The screenshot
change is unrelated user work. Ask the user to choose one safe outcome before exact-SHA evidence:

- preserve it outside the checkout while running evidence, then restore it unchanged; or
- commit it in a separate explicitly approved commit; or
- explicitly authorize restoring it.

Do not choose among these options silently. The final evidence must show `completedStatus` empty.

### Task 4.2 — Produce a same-machine healthy baseline

Use the same Docker services, fixture ticket `950015`, stock `1000`, Java/JMeter versions, thread
count `100`, attempts `5000`, strategy `REDIS_FIRST`, and quantity distribution `[1,1,1,2,2,3,4]`
for both runs. Use the repository-local JMeter binary; global `jmeter` is not required.

Record the baseline run on the pre-final workload branch `pr/phase-13-workload`, then record the
candidate run on the merged `master` SHA. Each run must be made from a clean evidence checkout and
must include the token-gated fixture reset:

```powershell
# Load these two process variables from local secret storage before this command.
# Do not type their values into a shell command, file, report, or commit.
if ([string]::IsNullOrWhiteSpace($env:BENCHMARK_CONTROL_TOKEN) -or
    [string]::IsNullOrWhiteSpace($env:BENCHMARK_FIXTURE_RESET_TOKEN)) {
  throw 'BENCHMARK_CONTROL_TOKEN and BENCHMARK_FIXTURE_RESET_TOKEN must already be set in the process environment.'
}

& .\benchmark\run-reservation-jmeter.ps1 `
  -BaseUrl 'http://localhost:1122' `
  -TicketItemId 950015 `
  -Threads 100 `
  -Attempts 5000 `
  -Strategy REDIS_FIRST `
  -Scenario healthy `
  -FixtureResetUrl 'http://localhost:1122/admin/reservation-fixtures/reset' `
  -FixtureResetToken $env:BENCHMARK_FIXTURE_RESET_TOKEN `
  -FixtureStock 1000 `
  -JMeterBin '.\benchmark\jmeter\bin\jmeter.bat'
```

After the baseline command prints its concrete result directory, capture it and derive the p95:

```powershell
$baselineRunDirectory = Read-Host 'Paste the concrete baseline run directory printed by JMeter'
if (-not (Test-Path (Join-Path $baselineRunDirectory 'metrics.json'))) {
  throw 'The supplied baseline directory does not contain metrics.json.'
}
$baselineP95 = [int]((Get-Content (Join-Path $baselineRunDirectory 'metrics.json') | ConvertFrom-Json).p95Ms)
if ($baselineP95 -le 0) { throw 'Baseline p95 must be a positive integer.' }
```

Pass `$baselineP95` to the candidate command as `-HealthyP95BaselineMs $baselineP95`.

Acceptance: the candidate run exits `0`; `metrics.json.gates.failures` is empty; statuses are only
the documented bounded set; correctness is zero oversell, zero negative stock, zero duplicate
reservation/order, zero drift; convergence is at most 30 seconds with zero pending journal/outbox.

### Task 4.3 — Execute the five deterministic effectiveness faults

The required fault names are exactly:

```text
AFTER_REDIS_BEFORE_DB
AFTER_DB_COMMIT_BEFORE_RESPONSE
REDIS_MIRROR_TIMEOUT
KAFKA_UNAVAILABLE
CONFIRM_EXPIRE_RACE
```

Run the chaos-profile application and use the finite fault catalog through its guarded control
surface. For every fault, record one and only one timeline entry containing activation time,
restoration time, first healthy request time, convergence seconds, pending journal/outbox counts,
drift, oversell, negative stock, duplicate reservation/order counts, and the exact candidate SHA.

Redis/Kafka dependency faults must use the real protocol/Toxiproxy path, not mocks. Before fault
activation, prove the proxy control endpoint and expected application proxy path are healthy. After
removal, prove proxy health and dependency reachability again. If that cannot be demonstrated, mark
the fault gate `NO-GO`.

The existing `benchmark/run-reservation-jmeter.ps1` scenarios (`healthy`, `duplicate-retry`,
`overload`, `kafka-recovery`) are workload lanes, not a substitute for the five named fault
timeline entries. Do not fabricate missing entries.

### Task 4.4 — Complete the manual browser control audit

Use connected Chrome or the explicitly allowed in-app browser. Automated Playwright is supporting
evidence only. Inventory every visible button, link, tab, form control, drawer action, refresh,
benchmark, reset, warmup, reconcile, consistency, reserve, confirm, release, expire, order, stock,
and scenario control before clicking.

For every row, capture:

- page and accessible control name;
- precondition and expected backend request/status;
- resulting visible state and timeline event;
- action duration and state-update duration;
- zero unexpected console errors and zero unexpected network failures;
- desktop and narrow screenshots.

Write the resulting `ui.json` in the effectiveness run directory and update
`docs/reports/ui-control-matrix.json` and `docs/reports/ui-control-audit.md` with the exact
candidate SHA, browser surface/version, one row per visible control, 100% pass rate, zero console
errors, and zero unexpected network failures. Destructive lab controls must use the disposable
fixture and restore it after the audit.

### Task 4.5 — Generate and verify the effectiveness report

Once the run directory contains all required artifacts, execute:

```powershell
& .\benchmark\measure-reservation-effectiveness.ps1 `
  -RunDirectory $candidateRunDirectory `
  -BaselineDirectory $baselineRunDirectory
```

Set `$candidateRunDirectory` and `$baselineRunDirectory` to the two concrete run directories
printed by the JMeter commands; do not leave either variable empty.

Expected exit code is `0`. The report must contain exact SHA identity, baseline/candidate p50/p95/p99,
throughput, bounded status counts, accepted/rejected units, Hikari/Redis/outbox/journal metrics,
all five fault timelines, convergence, correctness invariants, UI result, threshold verdicts, and
raw artifact paths. Any missing artifact or threshold failure is `NO-GO`, not a documentation task.

## 5. Final verification and documentation

Run the final candidate checks on the merged `master` SHA:

```powershell
mvn.cmd clean verify -Pflashsale-integration
Push-Location app/frontend
npm.cmd run lint
npm.cmd run typecheck
npm.cmd run build
npm.cmd run test:client
npm.cmd run test:e2e
Pop-Location
docker compose -f environment/docker-compose-dev.yml --profile observability config
git -C reference/saleor status --short
git -C reference/opentelemetry-demo status --short
git status --short --untracked-files=all
```

Update only source-backed claims in:

- `docs/reports/flash-sale-reliability-upgrade-report.md`
- `docs/reports/reservation-effectiveness-report.md`
- `docs/reports/ui-control-audit.md`
- `docs/reports/ui-control-matrix.json`
- `README.md` only if final measured results are now available
- `plan.md` only for gates actually observed passing

Do not mark a final acceptance checkbox merely because a source file, test definition, or CI job
exists. Mark it only when the specified runtime evidence exists at the exact candidate SHA.

## 6. Completion definition

The next session may report **fully complete and certified** only when all of the following are true:

- PR #6 (or its documented successor) is merged into default branch `master`.
- PRs #2–#5 are closed as superseded, not independently merged into the old program branch.
- Exact-SHA healthy baseline and candidate JMeter evidence pass the p95 regression gate.
- All five deterministic fault entries exist and pass correctness/convergence thresholds.
- The effectiveness script exits `0` and writes a complete report.
- Manual Chrome/in-app audit inventories and passes 100% of controls with zero unexpected console
  errors and zero unexpected network failures.
- Final Maven, frontend, Docker configuration, and reference-cleanliness checks pass.
- `plan.md`, reports, and README claims agree with the evidence.
- No unrelated dirty file was discarded or silently committed.

Until then, report **implementation merged / certification NO-GO** with the exact missing gates.

## 7. Suggested next-session opening message

```text
Read docs/superpowers/specs/2026-08-13-reservation-release-completion-handoff.md and execute it
task-by-task. First verify PR #6 and the current master/program branch state. Retarget PR #6 to
master and merge the complete stacked chain only after post-retarget CI is green; close PRs #2–#5 as
superseded afterward. Then resolve the unrelated screenshot decision, produce the same-machine
baseline, run all five fault/effectiveness gates, complete the manual browser control matrix, run
the measurement gate, and update only evidence-backed docs. Do not claim full plan completion while
any gate is NO-GO, do not use a worktree, and do not discard unrelated dirty changes.
```
