# Flash-Sale Reservation Reliability Upgrade Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILLS: Use `ecc:using-superpowers` to select the applicable workflow skills, `ecc:executing-plans` to execute this plan task-by-task, `ecc:test-driven-development` for every product change, `ecc:requesting-code-review` at each task gate, `khuym:smart-commits` at each phase commit gate, `ecc:strategic-compact` at the named context boundaries, and `chrome:control-chrome` or the in-app browser for browser QA. Steps use checkbox (`- [ ]`) syntax for tracking. Do not create Git worktrees for this plan.

**Goal:** Nâng cấp Flash-Sale Concurrency Engine thành một hệ thống reservation có lifecycle, durable idempotency, overload protection, crash recovery, deterministic chaos testing, observability và UI demo dễ hiểu; giữ nguyên `/orders` làm baseline tương thích.

**Architecture:** MySQL là nguồn sự thật bền vững; Redis là fast admission/mirror. Luồng reserve dùng durable operation journal trước khi chạy Lua apply-once, sau đó commit stock account, reservation và outbox trong MySQL; mọi cửa sổ crash được recovery hoặc compensate idempotently. Các phase chạy tuần tự trong cùng checkout để mọi contract, migration, implementation, test và evidence luôn dựa trên một HEAD tích hợp duy nhất.

**Tech Stack:** Java 21, Spring Boot 3.3.5, Maven, MySQL 8.0, Redis/Lua, Kafka, Resilience4j 2.1.0, Flyway, Micrometer, Brave, Prometheus, Grafana, Testcontainers, Toxiproxy, JMeter, Next.js 16.2.4, React 19.2.4, TypeScript 5, Playwright 1.60.

## Global Constraints

- Không tạo hoặc sử dụng Git worktree; thực hiện tuần tự trong checkout hiện tại.
- Chia branch tuần tự theo phase; chỉ có một branch implementation active tại một thời điểm và branch sau luôn bắt đầu từ integration branch đã chứa phase trước.
- Không stash, reset, overwrite hoặc commit các thay đổi không liên quan đang có trong `.env`, `app/frontend/benchmark/`, `app/frontend/e2e/reports/index.html`, `docs/PORTFOLIO_DEMO_VIDEO_SCRIPT_VI.md` và `reference/`.
- Được phép copy trực tiếp code hoặc port logic từ hai repository dưới `reference/` theo quyền sử dụng user đã xác nhận. Python/Django logic phải được hiểu, viết test đặc tả trước, rồi map sang Java/Spring; không dịch cú pháp máy móc.
- Quyền reuse từ hai repo reference không phải blocker pháp lý của kế hoạch này. License/source metadata chỉ phục vụ technical traceability, review và bảo trì về sau.
- Không sửa hoặc commit ngược vào hai repository reference; luôn kiểm tra chúng vẫn clean và đúng pinned SHA. Source mapping được giữ để truy vết kỹ thuật, không phải để hạn chế quyền reuse.
- Không triển khai real authentication; `X-Demo-Actor-Id` chỉ là định danh phiên demo, không phải security boundary.
- Giữ nguyên behavior và contract của `/orders`; API reservation mới nằm dưới `/api/v1/reservations`.
- MySQL là durable source of truth; không dùng Redis `SET` để ghi đè stock khi admission đang mở.
- Reservation v1 chỉ có một `ticketItemId`, một inventory location, quantity từ 1 đến 4 và TTL mặc định 120 giây.
- TDD bắt buộc: RED → GREEN → REFACTOR; coverage của code mới tối thiểu 80%.
- Không viết implementation trước test. Nếu phát hiện code đã được viết trước test trong một task, xóa/revert phần implementation trong phạm vi task, viết failing test, quan sát RED rồi mới viết lại minimal code.
- Metric labels chỉ dùng enum hữu hạn; không label raw actor, ticket, reservation, order hoặc idempotency key.
- Critical integration tests phải chạy mặc định trong CI; không được tiếp tục bị skip bởi flag tùy chọn.
- Mỗi commit chỉ stage exact files của task hiện tại; không dùng `git add .`.
- Tài liệu architecture, source mapping, API/state/invariant và verification contract phải được cập nhật trước khi Phase 1 bắt đầu. `README.md` được cập nhật sau khi các số liệu cuối đã được đo trên exact SHA.

---

## Sequential Branch, Skill and Context Strategy

### Branch chain — no worktrees, no parallel branches

| Order | Branch | Phases | Merge target |
|---|---|---|---|
| 1 | `program/reservation-reliability` | Integration spine | Current repository base |
| 2 | `docs/reservation-design` | Phase 0 | `program/reservation-reliability` |
| 3 | `feat/reservation-foundation` | Phases 1–2 | `program/reservation-reliability` |
| 4 | `feat/reservation-core` | Phases 3–7 | `program/reservation-reliability` |
| 5 | `feat/reservation-api-observability` | Phases 8–10 | `program/reservation-reliability` |
| 6 | `feat/reservation-demo-ui` | Phase 11 | `program/reservation-reliability` |
| 7 | `test/reservation-certification` | Phases 12–14 | `program/reservation-reliability` |
| 8 | `perf/reservation-strategy-comparison` | Phase 15 | `program/reservation-reliability` |
| 9 | `feat/reservation-otel-evidence` | Phase 16 | `program/reservation-reliability` |
| 10 | `docs/reservation-release` | Phase 17 | `program/reservation-reliability` |

Branch procedure at every boundary:

```powershell
git status --porcelain=v1
git branch --show-current
git diff --stat
git diff --cached --stat
```

The phase branch may be created/switched only when all in-scope work from the prior branch is committed. Existing out-of-scope dirty paths remain untouched. After review and merge into `program/reservation-reliability`, create the next branch from that updated integration HEAD. Do not keep two unmerged implementation branches.

Initial and recurring commands:

```powershell
# Once, after inspecting the dirty tree and preserving all out-of-scope paths
git switch -c program/reservation-reliability

# Example phase start
git switch -c docs/reservation-design program/reservation-reliability

# Example phase completion after smart-commit and reviews
git switch program/reservation-reliability
git merge --no-ff docs/reservation-design
git branch --delete docs/reservation-design

# Create the next branch only after the merge
git switch -c feat/reservation-foundation program/reservation-reliability
```

Repeat the same `--no-ff` merge/delete/start sequence using the ordered branch names in the table. If an out-of-scope dirty path prevents a switch, stop and report the exact path; never stash, reset or overwrite it.

### Mandatory TDD and review loop for every product task

```text
1. Write one behavior-focused failing test.
2. Run the narrowest test command and capture the expected failure.
3. Write the minimum implementation needed for that test.
4. Run the narrow test and observe GREEN.
5. Refactor without changing behavior.
6. Run the module suite and coverage.
7. Run spec-compliance review.
8. Run code-quality/security review.
9. Resolve all critical/high findings.
10. Use smart-commits to inspect and commit the coherent change group.
```

### Smart commit gate

At every commit checkpoint:

```powershell
git status --porcelain=v1
git branch --show-current
git remote -v
git diff --stat
git diff --cached --stat
```

- Group source with the directly coupled tests that prove the same behavior.
- Keep independently meaningful reference/design/report docs in `docs:` commits.
- Keep benchmark harness changes in `perf:` commits and browser/test evidence in `test:` commits.
- Stage explicit files or hunks only; generated artifacts are committed only when the plan names them as evidence deliverables.
- Do not push automatically merely because a phase commit succeeds; push only when the execution request explicitly authorizes Git delivery.

### Strategic compact checkpoints

- Compact after Phase 0 is saved and merged, before schema implementation.
- Compact after Phase 2 foundation is merged, before persistence/Redis work.
- Compact after Phase 7 core/recovery is merged, before API/observability.
- Compact after Phase 11 UI is merged, before certification and browser measurement.
- Compact after Phase 14 evidence is saved, before comparison/OTel extensions.
- Compact before Phase 17 final certification if benchmark/debug output has made the context noisy.
- Never compact in the middle of a RED–GREEN–REFACTOR task, an unresolved failure or a browser control audit.

Use these summaries at the checkpoints so the next context reloads only durable decisions:

```text
/compact Design/source locks are in plan.md and docs/design; start Flyway foundation.
/compact Foundation contracts and migrations are merged; start persistence and Redis protocol.
/compact Reservation core/recovery is merged; start API, chaos and observability.
/compact UI implementation is merged; start same-SHA certification and browser control audit.
/compact Effectiveness and UI evidence are saved; start strategy comparison or OTel extension.
/compact Final exact-SHA evidence is saved; update README and release documentation only.
```

---

## Phase 0 — Safety, research lock và baseline

### Task 0.1: Lock repository and reference evidence

**Files:**
- Modify: `plan.md`
- Create: `docs/references/source-lock.md`
- Create: `docs/references/reference-extraction.md`
- Create: `docs/design/flash-sale-reservation-reliability-design.md`
- Create: `docs/design/reservation-api-state-invariant.md`

**Interfaces:**
- Consumes: target `origin/master@6d041980f0b97e3104f3db9cc095e596e89e651e` và hai local reference clones.
- Produces: immutable provenance ledger được mọi phase sau trích dẫn.

- [x] **Step 1: Verify current state without changing it**

```powershell
git status --short --branch
git rev-parse HEAD
git -C reference/saleor status --short
git -C reference/saleor rev-parse HEAD
git -C reference/opentelemetry-demo status --short
git -C reference/opentelemetry-demo rev-parse HEAD
```

Expected:

```text
Target HEAD: 6d041980f0b97e3104f3db9cc095e596e89e651e
Saleor HEAD: d0b4811ae4d8c75a9a93e8905c784c89688e49ff
OpenTelemetry Demo HEAD: c983708e6e308f8395a6c9ce8ddb89705f910c1f
Reference working trees: clean
```

- [x] **Step 2: Write the source lock**

`docs/references/source-lock.md` must contain this table verbatim:

```markdown
| Repository | Local path | Commit | License | Policy |
|---|---|---|---|---|
| Saleor | `reference/saleor` | `d0b4811ae4d8c75a9a93e8905c784c89688e49ff` | BSD-3-Clause | Direct reuse or Python-to-Java logic port is authorized; target repo only |
| OpenTelemetry Demo | `reference/opentelemetry-demo` | `c983708e6e308f8395a6c9ce8ddb89705f910c1f` | Apache-2.0 | Direct reuse or cross-language logic port is authorized; target repo only |
```

- [x] **Step 3: Record the extraction order**

`docs/references/reference-extraction.md` must document this exact sequence:

1. Saleor `warehouse/models.py`: reservation fields, expiry and indexes.
2. Saleor `warehouse/reservations.py`: availability calculation, stable stock locking and all-or-nothing semantics.
3. Saleor reservation/task tests: race and expiry scenarios.
4. Target repo: rewrite the lifecycle around single-ticket Redis-first behavior.
5. OTel Demo `demo.flagd.json` and `AdService.java`: finite fault catalog and trace-correlated injection.
6. OTel telemetry schema and Collector sanitizer: bounded naming/cardinality.
7. OTel k6 load generator: workload manifest and trace propagation; keep JMeter as target v1 runner.

For every copied or ported unit, record:

```text
reference repository and pinned SHA
source file and function/class
reuse mode: DIRECT_COPY | LOGIC_PORT | DESIGN_REFERENCE
target file and symbol
behavior retained
behavior intentionally changed for Java/Spring/Redis-first architecture
tests written before target implementation
```

Direct copy must preserve necessary copyright/license notices. A Python-to-Java port keeps behavioral semantics and test cases, not Python framework structure.

- [x] **Step 4: Write design documents before implementation**

`docs/design/flash-sale-reservation-reliability-design.md` must lock the Redis-first journal sequence, terminal lifecycle, recovery, admission, chaos and observability design. `docs/design/reservation-api-state-invariant.md` must lock the endpoint/status table, state matrix, idempotency fingerprint and invariant equation. These are the authoritative contracts for later reviews.

- [x] **Step 5: Update agent guidance only where stable workflow changed**

Update root `CLAUDE.md` and `AGENTS.md` only with stable, repository-specific facts introduced by this plan:

```text
reservation integration verification command
mandatory TDD order
no-worktree sequential branch chain
reference reuse/source-mapping rule
browser QA evidence command/location
```

Do not duplicate this entire plan into either instruction file.

- [x] **Step 6: Commit documentation checkpoint**

```powershell
git add -- plan.md docs/references/source-lock.md docs/references/reference-extraction.md docs/design/flash-sale-reservation-reliability-design.md docs/design/reservation-api-state-invariant.md AGENTS.md CLAUDE.md
git commit -m "docs: lock reservation reliability source references"
```

### Task 0.2: Capture an authoritative baseline

**Files:**
- Create: `docs/reports/reservation-baseline.md`
- Read: `benchmark/run-jmeter.ps1`
- Read: `benchmark/experiment-spec.json`

**Interfaces:**
- Consumes: current `/orders` behavior and existing test/benchmark tooling.
- Produces: baseline SHA, test counts, skipped tests and evidence artifact paths.

- [x] **Step 1: Run backend baseline**

```powershell
mvn.cmd test
```

Expected: Maven reports `BUILD SUCCESS`; record exact test/pass/skip counts and command duration. A wrapper timeout is not a pass unless Maven output itself reached `BUILD SUCCESS`.

- [x] **Step 2: Run frontend static baseline**

```powershell
Push-Location app/frontend
npm.cmd run lint
npm.cmd run typecheck
npm.cmd run build
Pop-Location
```

Expected: all three commands exit `0`.

- [x] **Step 3: Run local smoke and existing JMeter harness**

```powershell
./benchmark/smoke-local.ps1
./benchmark/run-jmeter.ps1
```

Expected: new timestamped artifact bundle with raw results, consistency result and run metadata. Do not treat historical figures as current output.

- [x] **Step 4: Write baseline report**

The report must distinguish:

```text
Source SHA
Unit test result
Integration tests executed versus skipped
Frontend gates
Compose/runtime state
JMeter artifact directory
Correctness outcome
Local throughput/latency, explicitly labeled environment-specific
Known contract: /orders business reject uses envelope code 409 over HTTP 200
```

- [x] **Step 5: Commit baseline checkpoint**

```powershell
git add -- docs/reports/reservation-baseline.md
git commit -m "docs: capture reservation upgrade baseline"
```

---

## Phase 1 — Database migration foundation

### Task 1.1: Adopt Flyway without breaking existing volumes

**Files:**
- Modify: `app/backend/xxxx-start/pom.xml`
- Modify: `app/backend/xxxx-start/src/main/resources/application.yml`
- Modify: `app/backend/xxxx-start/README.md`
- Modify: `.github/workflows/ci.yml`
- Modify: `environment/docker-compose.prod.yml`
- Create: `app/backend/xxxx-start/src/main/resources/db/migration/V1__legacy_schema.sql`
- Create: `app/backend/xxxx-start/src/main/resources/db/migration/V2__reservation_reliability.sql`
- Test: `app/backend/xxxx-start/src/test/java/com/xxxx/ddd/integration/FlywayMigrationIntegrationTest.java`
- Test: `app/backend/xxxx-application/src/test/java/com/xxxx/ddd/application/MQ/OutboxEventTest.java`

**Interfaces:**
- Consumes: existing `environment/mysql/init/ticket_init.sql` and `outbox_init.sql` schemas.
- Produces: repeatable fresh-install and existing-volume migration contract.

- [x] **Step 1: Write the failing migration integration test**

```java
@Testcontainers
class FlywayMigrationIntegrationTest {
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0");

    @Test
    void migratesFreshDatabaseToReservationSchema() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())) {
            assertThat(tableExists(connection, "inventory_stock_account")).isTrue();
            assertThat(tableExists(connection, "inventory_reservation")).isTrue();
            assertThat(tableExists(connection, "inventory_operation_journal")).isTrue();
            assertThat(tableExists(connection, "reservation_order")).isTrue();
        }
    }
}
```

- [x] **Step 2: Run the test and confirm RED**

```powershell
mvn.cmd -pl app/backend/xxxx-start -am -Dtest=FlywayMigrationIntegrationTest "-Dsurefire.failIfNoSpecifiedTests=false" test
```

Expected: FAIL because Flyway and reservation tables do not exist.

Observed RED on 2026-08-09: Maven test compilation failed because `org.flywaydb.core` is not yet on the `xxxx-start` test classpath.

- [x] **Step 3: Add Flyway dependencies**

```xml
<dependency>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-core</artifactId>
</dependency>
<dependency>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-mysql</artifactId>
</dependency>
```

- [x] **Step 4: Configure safe migration adoption**

```yaml
spring:
  flyway:
    enabled: true
    locations: classpath:db/migration
    baseline-on-migrate: false
    validate-on-migrate: true
    clean-disabled: true
```

Existing non-empty volumes are baselined once with explicit environment override `SPRING_FLYWAY_BASELINE_ON_MIGRATE=true`; the application and production Compose defaults remain false.

The production Compose stack mounts the legacy init schema but does not silently opt into baseline adoption. Set `SPRING_FLYWAY_BASELINE_ON_MIGRATE=true` only after the target volume has been verified as that legacy database; leave it false for unknown or partially initialized volumes.

- [x] **Step 5: Create reservation schema**

`V2__reservation_reliability.sql` must define:

```sql
CREATE TABLE inventory_stock_account (
    ticket_item_id BIGINT PRIMARY KEY,
    initial_quantity INT NOT NULL,
    available_quantity INT NOT NULL,
    admission_state VARCHAR(16) NOT NULL DEFAULT 'OPEN',
    fence_version BIGINT NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT chk_inventory_admission_state CHECK (admission_state IN ('OPEN', 'DRAINING', 'CLOSED')),
    CONSTRAINT chk_inventory_versions CHECK (fence_version >= 0 AND version >= 0),
    CONSTRAINT chk_inventory_quantities CHECK (
        initial_quantity >= 0 AND available_quantity >= 0 AND available_quantity <= initial_quantity
    ),
    CONSTRAINT fk_stock_ticket_item FOREIGN KEY (ticket_item_id)
        REFERENCES ticket_item(id)
);

CREATE TABLE inventory_reservation (
    id BINARY(16) PRIMARY KEY,
    ticket_item_id BIGINT NOT NULL,
    demo_actor_id CHAR(36) NOT NULL,
    quantity INT NOT NULL,
    status VARCHAR(16) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    terminal_at DATETIME(6) NULL,
    idempotency_key_hash BINARY(32) NOT NULL,
    request_fingerprint BINARY(32) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_reservation_actor_key (demo_actor_id, idempotency_key_hash),
    KEY idx_reservation_expiry (ticket_item_id, status, expires_at),
    CONSTRAINT chk_reservation_quantity CHECK (quantity BETWEEN 1 AND 4),
    CONSTRAINT chk_reservation_status CHECK (status IN ('RESERVED', 'CONFIRMED', 'RELEASED', 'EXPIRED')),
    CONSTRAINT fk_reservation_stock FOREIGN KEY (ticket_item_id)
        REFERENCES inventory_stock_account(ticket_item_id)
);

CREATE TABLE inventory_operation_journal (
    operation_id BINARY(16) PRIMARY KEY,
    reservation_id BINARY(16) NOT NULL,
    operation_type VARCHAR(24) NOT NULL,
    state VARCHAR(32) NOT NULL,
    ticket_item_id BIGINT NOT NULL,
    quantity INT NOT NULL,
    demo_actor_id CHAR(36) NULL,
    idempotency_key_hash BINARY(32) NULL,
    request_fingerprint BINARY(32) NOT NULL,
    fence_version BIGINT NOT NULL,
    lease_owner VARCHAR(64) NULL,
    lease_until DATETIME(6) NULL,
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(6) NULL,
    last_error_code VARCHAR(64) NULL,
    result_code VARCHAR(64) NULL,
    result_stock_after INT NULL,
    repair_id BINARY(16) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_journal_create_claim (demo_actor_id, idempotency_key_hash),
    KEY idx_journal_recovery (state, next_attempt_at, lease_until),
    CONSTRAINT chk_journal_operation_type CHECK (
        operation_type IN ('CREATE', 'CONFIRM', 'RELEASE', 'EXPIRE', 'COMPENSATE', 'MIRROR', 'REPAIR')
    ),
    CONSTRAINT chk_journal_state CHECK (
        state IN (
            'RECEIVED', 'REJECTED', 'REDIS_APPLIED', 'COMMITTED', 'COMPENSATED',
            'COMPENSATION_PENDING', 'MIRROR_PENDING', 'REPAIR_REQUIRED'
        )
    ),
    CONSTRAINT chk_journal_numbers CHECK (
        quantity BETWEEN 1 AND 4 AND fence_version >= 0 AND attempts >= 0
    ),
    CONSTRAINT chk_journal_create_claim CHECK (
        (operation_type = 'CREATE' AND demo_actor_id IS NOT NULL AND idempotency_key_hash IS NOT NULL)
        OR (operation_type <> 'CREATE' AND demo_actor_id IS NULL AND idempotency_key_hash IS NULL)
    )
);

CREATE TABLE inventory_repair_journal (
    repair_id BINARY(16) PRIMARY KEY,
    ticket_item_id BIGINT NOT NULL,
    previous_fence_version BIGINT NOT NULL,
    new_fence_version BIGINT NOT NULL,
    state VARCHAR(16) NOT NULL,
    disposition VARCHAR(64) NULL,
    mysql_available_snapshot INT NULL,
    started_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    completed_at DATETIME(6) NULL,
    UNIQUE KEY uk_repair_ticket_fence (ticket_item_id, new_fence_version),
    CONSTRAINT fk_repair_stock FOREIGN KEY (ticket_item_id)
        REFERENCES inventory_stock_account(ticket_item_id),
    CONSTRAINT chk_repair_state CHECK (state IN ('STARTED', 'VERIFIED', 'COMPLETED', 'FAILED')),
    CONSTRAINT chk_repair_fence CHECK (previous_fence_version >= 0 AND new_fence_version > previous_fence_version)
);

CREATE TABLE reservation_order (
    id BINARY(16) PRIMARY KEY,
    reservation_id BINARY(16) NOT NULL,
    ticket_item_id BIGINT NOT NULL,
    demo_actor_id CHAR(36) NOT NULL,
    quantity INT NOT NULL,
    confirmed_at DATETIME(6) NOT NULL,
    UNIQUE KEY uk_order_reservation (reservation_id),
    CONSTRAINT chk_order_quantity CHECK (quantity BETWEEN 1 AND 4),
    CONSTRAINT fk_order_stock FOREIGN KEY (ticket_item_id)
        REFERENCES inventory_stock_account(ticket_item_id),
    CONSTRAINT fk_order_reservation FOREIGN KEY (reservation_id)
        REFERENCES inventory_reservation(id)
);
```

Also add `lease_owner`, `lease_until`, `next_attempt_at` and stable `event_id` support to the existing outbox schema. `event_id` is a stored generated alias of the existing `id` primary key, so `id` remains the only identity and uniqueness boundary for legacy, JPA and direct SQL writers. `OutboxEvent` exposes that alias read-only while its constructor keeps the same UUID available to new application writes.

The CI observability smoke and the documented legacy-init development launch explicitly set `SPRING_FLYWAY_BASELINE_ON_MIGRATE=true`; the application default remains false so an unverified non-empty database is never silently baselined.

Before creating `inventory_stock_account`, V2 validates every legacy `ticket_item` quantity in a temporary constrained table. Negative, oversold or otherwise malformed legacy stock fails the migration closed; it is not clamped or silently converted into a reservation account.

The stock account's `fence_version` is the admission fence and `admission_state` is a durable `OPEN -> DRAINING -> CLOSED -> OPEN` state machine. A create journal claim and every conditional MySQL decrement require `admission_state = 'OPEN'` and the stored current fence; a repair owner claims `OPEN -> DRAINING` with one compare-and-set update, increments the fence, and creates an `inventory_repair_journal` row in the same transaction. Normal claims and terminal mutations are rejected unless the durable account is `OPEN` with the current fence. Redis stores the same fence and admission state. A fence-publication Lua script atomically accepts only a greater fence and writes the new fence/state; normal apply, compensate, and terminal-mirror Lua calls require Redis `admission_state = 'OPEN'`, compare both values, and return `STALE_FENCE` without mutation when they do not match. The repair owner publishes `DRAINING`, drains or rejects old-fence leases, waits for in-flight old-fence operations to quiesce, and only then CASes `DRAINING -> CLOSED`. While `CLOSED`, a repair-only maintenance script writes the MySQL-authoritative snapshot, verifies Redis/MySQL equality and zero old-fence operations, and records the disposition. Only a successful verification may publish `OPEN` and CAS `CLOSED -> OPEN`; failed verification leaves the account closed and the repair journal `FAILED`. Delayed operations carrying the old fence must therefore fail closed and cannot mutate after repair.

- [x] **Step 6: Run migration tests and refactor**

```powershell
mvn.cmd -pl app/backend/xxxx-start -Dtest=FlywayMigrationIntegrationTest test
mvn.cmd test
```

Expected: PASS; Flyway validates all checksums.

The first live MySQL attempt exposed unsupported `ADD COLUMN IF NOT EXISTS` syntax in MySQL 8.0; V2 now uses the Flyway one-time migration boundary with plain `ADD COLUMN`. The final run on 2026-08-09 passed all three cases: fresh schema, the actual `environment/mysql/init` legacy scripts with explicit baseline/history and second-run no-op, and fail-closed oversold-stock validation. Docker Desktop Linux was started locally for this verification.

- [x] **Step 7: Commit migration checkpoint**

```powershell
git add -- .github/workflows/ci.yml app/backend/xxxx-start/README.md app/backend/xxxx-start/pom.xml app/backend/xxxx-start/src/main/resources/application.yml app/backend/xxxx-start/src/main/resources/db/migration app/backend/xxxx-start/src/test/java/com/xxxx/ddd/integration/FlywayMigrationIntegrationTest.java app/backend/xxxx-application/src/main/java/com/xxxx/ddd/application/MQ/OutboxEvent.java app/backend/xxxx-application/src/test/java/com/xxxx/ddd/application/MQ/OutboxEventTest.java environment/docker-compose.prod.yml plan.md
git commit -m "feat: add reservation reliability migrations"
```

The implementation and review-fix checkpoints are committed; the current branch remains unpushed and unrelated dirty paths remain outside these commits.

---

## Phase 2 — Domain contracts and state machine

### Task 2.1: Define reservation domain

**Files:**
- Create: `app/backend/xxxx-domain/src/main/java/com/xxxx/ddd/domain/reservation/ReservationStatus.java`
- Create: `app/backend/xxxx-domain/src/main/java/com/xxxx/ddd/domain/reservation/Reservation.java`
- Create: `app/backend/xxxx-domain/src/main/java/com/xxxx/ddd/domain/reservation/ReservationTransition.java`
- Create: `app/backend/xxxx-domain/src/main/java/com/xxxx/ddd/domain/reservation/InventorySnapshot.java`
- Test: `app/backend/xxxx-domain/src/test/java/com/xxxx/ddd/domain/reservation/ReservationTest.java`

**Interfaces:**
- Produces: immutable domain records and legal transition rules used by all application services.

- [x] **Step 1: Write failing transition tests**

```java
@ParameterizedTest
@CsvSource({
    "RESERVED,CONFIRMED,true",
    "RESERVED,RELEASED,true",
    "RESERVED,EXPIRED,true",
    "CONFIRMED,RELEASED,false",
    "RELEASED,CONFIRMED,false",
    "EXPIRED,CONFIRMED,false"
})
void enforcesTransitionMatrix(ReservationStatus from, ReservationStatus to, boolean allowed) {
    assertThat(ReservationTransition.canTransition(from, to)).isEqualTo(allowed);
}
```

- [x] **Step 2: Run and confirm RED**

```powershell
mvn.cmd -pl app/backend/xxxx-domain -Dtest=ReservationTest test
```

Expected: compilation failure because reservation types do not exist.

Observed RED on 2026-08-09: `ReservationTest` failed during test compilation because `ReservationStatus` was not yet defined.

- [x] **Step 3: Implement immutable contracts**

```java
public enum ReservationStatus {
    RESERVED, CONFIRMED, RELEASED, EXPIRED
}

public record InventorySnapshot(
        long ticketItemId,
        int initial,
        int available,
        int reserved,
        int confirmed) {
    public boolean invariantHolds() {
        return initial == available + reserved + confirmed;
    }
}

public final class ReservationTransition {
    private ReservationTransition() {}

    public static boolean canTransition(ReservationStatus from, ReservationStatus to) {
        return from == ReservationStatus.RESERVED
                && EnumSet.of(ReservationStatus.CONFIRMED,
                              ReservationStatus.RELEASED,
                              ReservationStatus.EXPIRED).contains(to);
    }
}
```

- [x] **Step 4: Run tests and commit**

```powershell
mvn.cmd -pl app/backend/xxxx-domain test
git add -- app/backend/xxxx-domain/src/main/java/com/xxxx/ddd/domain/reservation app/backend/xxxx-domain/src/test/java/com/xxxx/ddd/domain/reservation
git commit -m "feat: define reservation state machine"
```

The domain module passes 8 tests covering the transition matrix, inventory accounting invariant, immutable reservation shape, and quantity bounds. `InventorySnapshot` uses widened arithmetic for the accounting sum.

### Task 2.2: Define ports for persistence, Redis, telemetry and chaos

**Files:**
- Create: `app/backend/xxxx-application/src/main/java/com/xxxx/ddd/application/reservation/port/ReservationRepository.java`
- Create: `app/backend/xxxx-application/src/main/java/com/xxxx/ddd/application/reservation/port/InventoryRepository.java`
- Create: `app/backend/xxxx-application/src/main/java/com/xxxx/ddd/application/reservation/port/OperationJournalRepository.java`
- Create: `app/backend/xxxx-application/src/main/java/com/xxxx/ddd/application/reservation/port/ReservationStockPort.java`
- Create: `app/backend/xxxx-application/src/main/java/com/xxxx/ddd/application/reservation/port/ReservationTelemetryPort.java`
- Create: `app/backend/xxxx-application/src/main/java/com/xxxx/ddd/application/reservation/port/FaultInjectionPort.java`

**Interfaces:**
- Produces these exact core signatures:

```java
public interface ReservationStockPort {
    RedisApplyResult applyOnce(UUID operationId, long ticketItemId, int quantity, long fenceVersion);
    RedisCompensationResult compensateOnce(UUID operationId, long ticketItemId, int quantity, long fenceVersion);
    void mirrorTerminalOnce(UUID operationId, long ticketItemId, int delta, long fenceVersion);
    Optional<RedisOperationState> operationState(UUID operationId);
}

public interface ReservationTelemetryPort {
    void record(String operation, String outcome, String reason, Duration duration);
}

public interface FaultInjectionPort {
    void hit(FaultPoint point, UUID operationId);
}
```

- [x] **Step 1: Create compile-only contract test**

Instantiate no-op implementations and verify method signatures compile.

- [x] **Step 2: Run and confirm RED**

```powershell
mvn.cmd -pl app/backend/xxxx-application test
```

Observed the expected test-compilation failure because the reservation port types and no-op adapters did not exist yet. The application-only invocation also resolved the previously installed domain artifact, so the first green run used the reactor-safe `-am` form to compile the new reservation domain types.

- [x] **Step 3: Implement ports and no-op adapters**

`NoOpReservationTelemetry` must return without side effects. `NoOpFaultInjection` must never throw.

The Redis result types and finite fault catalog are nested in their owning ports to keep the planned application-port boundary compact. Redis results require a bounded stock value for applied/replayed/sold-out outcomes and never expose stock for stale-fence or conflict outcomes. Persistence ports cover reservation lookup/transition, conditional inventory mutation, journal claims/transitions, and recovery leases.

- [x] **Step 4: Run and commit**

```powershell
mvn.cmd -pl app/backend/xxxx-application test
git add -- app/backend/xxxx-application/src/main/java/com/xxxx/ddd/application/reservation
git commit -m "feat: add reservation application ports"
```

The reactor-safe verification command `mvn.cmd -pl app/backend/xxxx-application -am test` passed 19 application tests and 8 domain tests. The application-only command remains dependent on a locally installed matching domain artifact in this checkout.

---

## Phase 3 — Durable persistence and idempotency

### Task 3.1: Implement JPA reservation repositories

**Files:**
- Create focused entities/repositories under `app/backend/xxxx-infrastructure/src/main/java/com/xxxx/ddd/infrastructure/reservation/persistence/`
- Test: `app/backend/xxxx-start/src/test/java/com/xxxx/ddd/integration/ReservationPersistenceIntegrationTest.java`

**Interfaces:**
- Consumes: domain records and ports from Phase 2.
- Produces: conditional stock decrement, conditional terminal transition, aggregate snapshot and journal lease/claim.

- [x] **Step 1: Write failing MySQL integration tests**

Tests must prove:

```text
decrement succeeds only when available >= quantity, admission_state = OPEN and fence_version matches
one of concurrent confirm/expire transitions wins
release/expire restores stock exactly once
journal lease cannot be claimed by two workers
same actor + idempotency hash is unique for `CREATE` claims; terminal/mirror retries reuse the existing journal row
an old-fence operation cannot mutate MySQL after repair reopens admission
```

- [x] **Step 2: Run and confirm RED**

```powershell
mvn.cmd -pl app/backend/xxxx-start -Dtest=ReservationPersistenceIntegrationTest test
```

Observed the expected compilation failure because the new reservation ports and persistence adapters did not exist yet. The application-only classpath also required the reactor-safe `-am` form after the Phase 2 domain and port commits.

- [x] **Step 3: Implement SQL conditions**

Use parameterized queries equivalent to:

```sql
UPDATE inventory_stock_account
SET available_quantity = available_quantity - :quantity,
    version = version + 1
WHERE ticket_item_id = :ticketItemId
  AND admission_state = 'OPEN'
  AND fence_version = :fenceVersion
  AND available_quantity >= :quantity;

UPDATE inventory_reservation
SET status = 'CONFIRMED', terminal_at = UTC_TIMESTAMP(6), version = version + 1
WHERE id = :reservationId
  AND status = 'RESERVED'
  AND expires_at > UTC_TIMESTAMP(6);
```

Implemented native JPA adapter queries for conditional inventory decrement/restore, database-time reservation transitions, expiry lookup, and journal claims/leases. Release/expire transitions restore inventory in the same transaction and fail closed if the conditional restore cannot complete. The journal lease uses one ordered conditional update, so concurrent workers cannot claim the same eligible row.

- [x] **Step 4: Implement durable idempotency decision**

Canonical fingerprint:

```text
SHA-256("ticketItemId=<decimal>&quantity=<decimal>")
```

Store SHA-256 of the idempotency key; never store or log the raw key.

`OperationJournalRepository` carries the idempotency-key digest and canonical request fingerprint. `claimCreate` uses the MySQL unique actor/digest boundary and returns the original journal row for replay or conflict comparison; reservation writes accept both digests and validate 64-character hexadecimal values.

- [x] **Step 5: Run tests and commit**

```powershell
mvn.cmd -pl app/backend/xxxx-start -Dtest=ReservationPersistenceIntegrationTest test
git add -- app/backend/xxxx-infrastructure/src/main/java/com/xxxx/ddd/infrastructure/reservation app/backend/xxxx-start/src/test/java/com/xxxx/ddd/integration/ReservationPersistenceIntegrationTest.java
git commit -m "feat: persist reservation inventory and journal"
```

The reactor-safe verification command `mvn.cmd -pl app/backend/xxxx-start -am -Dtest=ReservationPersistenceIntegrationTest "-Dsurefire.failIfNoSpecifiedTests=false" test` passed all 6 MySQL/Testcontainers cases. The initial live runs caught an invalid over-limit test quantity, cross-test journal eligibility, and missing stock decrement before terminal restore; each was corrected before the final pass.

---

## Phase 4 — Redis apply-once protocol

### Task 4.1: Implement idempotent Redis Lua scripts

**Files:**
- Create: `app/backend/xxxx-infrastructure/src/main/resources/redis/reservation-apply-once.lua`
- Create: `app/backend/xxxx-infrastructure/src/main/resources/redis/reservation-compensate-once.lua`
- Create: `app/backend/xxxx-infrastructure/src/main/resources/redis/reservation-terminal-mirror-once.lua`
- Create: `app/backend/xxxx-infrastructure/src/main/resources/redis/reservation-fence-publish.lua`
- Create: `app/backend/xxxx-infrastructure/src/main/resources/redis/reservation-repair-mirror.lua`
- Create: `app/backend/xxxx-infrastructure/src/main/java/com/xxxx/ddd/infrastructure/reservation/redis/RedisReservationStockAdapter.java`
- Test: `app/backend/xxxx-start/src/test/java/com/xxxx/ddd/integration/RedisReservationProtocolIntegrationTest.java`

**Interfaces:**
- Implements: `ReservationStockPort`.
- Redis keys: `flashsale:reservation:stock:{ticketItemId}` and `flashsale:reservation:op:{operationId}`.

- [x] **Step 1: Write failing Redis protocol tests**

Prove:

```text
first apply decrements once
second apply with same operation returns original result
insufficient stock never decrements
stale fence never mutates stock and returns STALE_FENCE without a stock value
fence publication accepts only a greater version and publishes admission state atomically
repair mirror writes the MySQL snapshot only while CLOSED and records a bounded disposition
compensation increments only from APPLIED
second compensation is a no-op
terminal mirror applies a delta once
```

Implemented `RedisReservationProtocolIntegrationTest` with eight live Redis 7 Testcontainers cases covering the apply, replay, fence, repair, compensation, terminal-mirror, and argument-conflict contracts.

- [x] **Step 2: Run and confirm RED**

```powershell
mvn.cmd -pl app/backend/xxxx-start -Dtest=RedisReservationProtocolIntegrationTest test
```

The direct module run was RED because the installed application/infrastructure artifacts did not yet contain the new reservation port and adapter types; the reactor-safe command below was required after implementation.

- [x] **Step 3: Implement apply script contract**

The Lua script must return one of these bounded results:

```text
APPLIED:<stockAfter>
REPLAYED:<stockAfter>
SOLD_OUT:<stockCurrent>
STALE_FENCE
CONFLICT
```

Operation records expire after 7 days, exceeding the recovery horizon.

Implemented the five Lua scripts and `RedisReservationStockAdapter`. All stock mutations and operation records are performed inside Redis scripts; stale fences do not expose stock, operation arguments are checked before replay, repair is limited to `CLOSED` snapshots with bounded dispositions, and terminal/compensation paths are apply-once.

- [x] **Step 4: Run tests and commit**

```powershell
mvn.cmd -pl app/backend/xxxx-start -Dtest=RedisReservationProtocolIntegrationTest test
git add -- app/backend/xxxx-infrastructure/src/main/resources/redis app/backend/xxxx-infrastructure/src/main/java/com/xxxx/ddd/infrastructure/reservation/redis app/backend/xxxx-start/src/test/java/com/xxxx/ddd/integration/RedisReservationProtocolIntegrationTest.java
git commit -m "feat: add idempotent reservation redis protocol"
```

The reactor-safe command `mvn.cmd -pl app/backend/xxxx-start -am -Dtest=RedisReservationProtocolIntegrationTest "-Dsurefire.failIfNoSpecifiedTests=false" test` passed all 8 live Redis 7 Testcontainers cases. Committed as `8563afe`.

---

## Phase 5 — Reservation creation vertical slice

### Task 5.1: Implement journaled Redis-first create flow

**Files:**
- Create: `app/backend/xxxx-application/src/main/java/com/xxxx/ddd/application/reservation/CreateReservationCommand.java`
- Create: `app/backend/xxxx-application/src/main/java/com/xxxx/ddd/application/reservation/CreateReservationResult.java`
- Create: `app/backend/xxxx-application/src/main/java/com/xxxx/ddd/application/reservation/CreateReservationService.java`
- Test: `app/backend/xxxx-application/src/test/java/com/xxxx/ddd/application/reservation/CreateReservationServiceTest.java`

**Interfaces:**
- Consumes: repositories, `ReservationStockPort`, `ReservationTelemetryPort`, `FaultInjectionPort`, existing `OutboxService`.
- Produces: NEW, REPLAYED, PROCESSING, SOLD_OUT, FENCE_STALE, REJECTED and CONFLICT outcomes.

- [x] **Step 1: Write failing service tests**

Cover this exact sequence:

```text
claim/insert RECEIVED journal
Lua applyOnce
fault point AFTER_REDIS_BEFORE_DB
MySQL transaction: decrement + reservation + outbox + COMMITTED
fault point AFTER_DB_COMMIT_BEFORE_RESPONSE
return stock snapshot
```

Also test DB failure invokes `compensateOnce` and marks `COMPENSATED` when compensation succeeds; a failed compensation must remain `COMPENSATION_PENDING` and be retried by recovery.

Implemented 9 focused service tests covering ordering, replay and idempotency conflict, sold-out and stale-fence rejections, successful and failed compensation, and both fault boundaries.

- [x] **Step 2: Run and confirm RED**

```powershell
mvn.cmd -pl app/backend/xxxx-application -Dtest=CreateReservationServiceTest test
```

The direct module command was RED because its installed `xxxx-domain` artifact predates the reservation types. The reactor-safe command with `-am` was used for GREEN verification.

- [x] **Step 3: Implement the immutable command**

```java
public record CreateReservationCommand(
        long ticketItemId,
        int quantity,
        UUID demoActorId,
        String idempotencyKey) {
    public CreateReservationCommand {
        if (ticketItemId <= 0) throw new IllegalArgumentException("ticketItemId must be positive");
        if (quantity < 1 || quantity > 4) throw new IllegalArgumentException("quantity must be between 1 and 4");
        Objects.requireNonNull(demoActorId, "demoActorId");
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey is required");
        }
    }
}
```

Implemented `CreateReservationCommand` with the specified immutable shape and boundary validation, plus a typed `CreateReservationResult` for the seven bounded outcomes.

- [x] **Step 4: Implement transaction boundaries**

The service generates `operationId` and `reservationId` before the journal claim. The journal insert is committed before Redis and uniquely claims `(demo_actor_id, idempotency_key_hash)` for `operation_type=CREATE`; terminal/mirror retries reuse that journal row. An existing create claim returns its durable operation state instead of admitting a second Redis operation. The reservation/outbox commit is a separate transaction. A crash after Redis is recoverable because the journal, its `fence_version`, and the Redis operation token share `operationId`.

Implemented explicit `REQUIRES_NEW` claim/transition transactions and a separate database transaction for durable decrement, reservation, outbox, and `COMMITTED`. The inventory port and JPA adapter now read the durable current fence version before Redis admission; a MySQL/Testcontainers test proves that lookup.

- [x] **Step 5: Run tests and commit**

```powershell
mvn.cmd -pl app/backend/xxxx-application -Dtest=CreateReservationServiceTest test
git add -- app/backend/xxxx-application/src/main/java/com/xxxx/ddd/application/reservation app/backend/xxxx-application/src/test/java/com/xxxx/ddd/application/reservation/CreateReservationServiceTest.java
git commit -m "feat: create durable redis-first reservations"
```

The reactor-safe command `mvn.cmd -pl app/backend/xxxx-application -am -Dtest=CreateReservationServiceTest "-Dsurefire.failIfNoSpecifiedTests=false" test` passed all 9 service tests. The durable-fence persistence coverage also passed all 7 MySQL/Testcontainers cases through `ReservationPersistenceIntegrationTest`.

---

## Phase 6 — Confirm, release and expiry

### Task 6.1: Implement terminal operations

**Files:**
- Create: `ConfirmReservationService.java`, `ReleaseReservationService.java`, `ExpireReservationService.java` in the reservation application package.
- Test: corresponding focused test classes in the same module.

**Interfaces:**
- Confirm creates one `reservation_order` and never decrements stock again.
- Release/expire restore available stock in the same transaction as terminal state/outbox.

- [x] **Step 1: Write the failing transition tests**

```text
RESERVED -> CONFIRMED succeeds and creates one order
duplicate confirm returns the same order
confirm at or after expires_at returns late conflict and expires the reservation
RESERVED -> RELEASED restores stock once
duplicate release returns current state without another increment
confirm versus expire has exactly one winner
```

- [x] **Step 2: Run and confirm RED**

```powershell
mvn.cmd -pl app/backend/xxxx-application -Dtest=*ReservationServiceTest test
```

- [x] **Step 3: Implement DB-time transitions**

All expiry comparisons use `UTC_TIMESTAMP(6)`. The exact-valid condition is `expires_at > UTC_TIMESTAMP(6)`; equality is expired.

- [x] **Step 4: Emit outbox events**

Use stable event types:

```text
reservation.created
reservation.confirmed
reservation.released
reservation.expired
```

- [x] **Step 5: Mirror terminal deltas idempotently**

Release/expire call `mirrorTerminalOnce(operationId, ticketItemId, quantity, fenceVersion)` after DB commit. Mirror failure records `MIRROR_PENDING` in the same operation journal and does not roll back the durable transition. Compensation failure records `COMPENSATION_PENDING`; neither pending state may be treated as converged.

- [x] **Step 6: Run tests and commit**

```powershell
mvn.cmd -pl app/backend/xxxx-application test
git add -- app/backend/xxxx-application/src/main/java/com/xxxx/ddd/application/reservation app/backend/xxxx-application/src/test/java/com/xxxx/ddd/application/reservation
git commit -m "feat: add reservation terminal lifecycle"
```

---

## Phase 7 — Recovery, sweeper and outbox hardening

### Task 7.1: Recover interrupted operations

**Files:**
- Create: `ReservationRecoveryService.java`
- Create: `ReservationRecoveryScheduler.java`
- Create: `ReservationExpiryScheduler.java`
- Test: `ReservationRecoveryIntegrationTest.java`

**Interfaces:**
- Batch size 50, lease 30 seconds, retry delays 1/2/4/8/16 seconds, maximum 5 attempts.

- [x] **Step 1: Write failing crash-window tests**

Test crash before Redis, after Redis/before DB, after DB commit/before response, and during terminal Redis mirror. Test stale-fence compensation/mirror transitions stop retrying the old token and resolve through a new fenced repair ID.

- [x] **Step 2: Implement claim loop**

```java
@Scheduled(fixedDelayString = "${flashsale.reservation.recovery-delay:1000}")
public void recover() {
    journal.claimRecoverable(workerId, 50, Duration.ofSeconds(30))
            .forEach(recoveryService::recover);
}
```

- [x] **Step 3: Implement deterministic dispositions**

```text
RECEIVED + no Redis token + current fence -> retry apply
RECEIVED + no Redis token + stale fence -> REJECTED with FENCE_STALE; do not retry
RECEIVED + APPLIED token -> finalize DB or enter COMPENSATION_PENDING
REDIS_APPLIED + missing reservation -> finalize DB or enter COMPENSATION_PENDING
COMMITTED + missing response -> replay reservation
REJECTED -> stable persisted result; never retry
COMPENSATED -> stable terminal journal state
COMPENSATION_PENDING + current fence -> retry compensateOnce; on success mark COMPENSATED
COMPENSATION_PENDING + stale fence -> REPAIR_REQUIRED; never retry old token
MIRROR_PENDING + current fence -> retry mirrorTerminalOnce; on success mark COMMITTED
MIRROR_PENDING + stale fence -> REPAIR_REQUIRED; never retry old token
REPAIR_REQUIRED -> fenced repair; resolve to COMPENSATED, COMMITTED or REJECTED after verification
five failed repair attempts -> REPAIR_REQUIRED + alert metric and certification NO-GO
```

`SOLD_OUT` is persisted as `REJECTED` with `result_code=SOLD_OUT` and the bounded Redis `stockCurrent`. `FENCE_STALE` is persisted as `REJECTED` with `result_code=FENCE_STALE` and `result_stock_after=NULL`, because a stale Redis value is not authoritative; the API returns `stockAfter=null` and the later repair snapshot is the source of truth. Replay returns the stored bounded result without a new Redis operation. If a `COMPENSATION_PENDING` or `MIRROR_PENDING` operation receives `STALE_FENCE`, recovery must stop retrying the old operation token and transition it to `REPAIR_REQUIRED`. A fenced repair uses a new repair ID and maintenance write, then resolves `REPAIR_REQUIRED -> COMPENSATED` when no reservation remains, `REPAIR_REQUIRED -> COMMITTED` when the durable terminal reservation is mirrored, or `REPAIR_REQUIRED -> REJECTED` for an unadmitted stale-fence create. Each transition stores the `repair_id` and disposition in the journal and repair journal, and HTTP 503 remains until the repair is verified. A run with `COMPENSATION_PENDING`, `MIRROR_PENDING`, or `REPAIR_REQUIRED` is not allowed to satisfy the zero-pending convergence gate.

- [ ] **Step 4: Run tests and commit**

```powershell
mvn.cmd -pl app/backend/xxxx-start -Dtest=ReservationRecoveryIntegrationTest test
git add -- app/backend/xxxx-application/src/main/java/com/xxxx/ddd/application/reservation app/backend/xxxx-start/src/test/java/com/xxxx/ddd/integration/ReservationRecoveryIntegrationTest.java
git commit -m "feat: recover interrupted reservation operations"
```

### Task 7.2: Make outbox safe for multiple schedulers

**Files:**
- Modify: `app/backend/xxxx-application/src/main/java/com/xxxx/ddd/application/MQ/OutboxService.java`
- Modify: `app/backend/xxxx-application/src/main/java/com/xxxx/ddd/application/MQ/OutboxRepository.java`
- Modify: `app/backend/xxxx-application/src/main/java/com/xxxx/ddd/application/MQ/OutboxPublishScheduler.java`
- Test: `OutboxClaimIntegrationTest.java`

- [x] **Step 1: Write failing two-publisher test**

Start two relay workers and assert one event ID is published at most once per claim lease; consumer processing remains idempotent even if publication is retried.

- [x] **Step 2: Implement claim/lease**

Use MySQL 8 row locking/claim transaction; a scheduler publishes only events bearing its `lease_owner` and an unexpired `lease_until`.

- [ ] **Step 3: Run and commit**

```powershell
mvn.cmd -pl app/backend/xxxx-start -Dtest=OutboxClaimIntegrationTest test
git add -- app/backend/xxxx-application/src/main/java/com/xxxx/ddd/application/MQ app/backend/xxxx-start/src/test/java/com/xxxx/ddd/integration/OutboxClaimIntegrationTest.java
git commit -m "feat: lease outbox publication batches"
```

---

## Phase 8 — API and fixed admission control

### Task 8.1: Add versioned reservation API

**Files:**
- Create: `app/backend/xxxx-controller/src/main/java/com/xxxx/ddd/controller/http/reservation/ReservationController.java`
- Create request/response/error records in the same package.
- Create: `ReservationExceptionHandler.java`
- Test: `ReservationControllerTest.java`

**Interfaces:**

```text
POST /api/v1/reservations
GET  /api/v1/reservations/{reservationId}
POST /api/v1/reservations/{reservationId}/confirm
POST /api/v1/reservations/{reservationId}/release
GET  /api/v1/inventory/{ticketItemId}
```

- [x] **Step 1: Write failing MockMvc contract tests**

Assert exact statuses: 201 new, 200 replay/transition, 202 recovering journal states, 400 validation, 404 only when neither reservation nor journal exists, 409 initial sold-out/fence-stale/conflict/late transition and persisted rejected/compensated outcomes, 429 rate limit and 503 saturation or REPAIR_REQUIRED with `Retry-After`.

- [x] **Step 2: Define request contract**

```java
public record CreateReservationRequest(
        @Positive long ticketItemId,
        @Min(1) @Max(4) int quantity) {}
```

Headers `Idempotency-Key` and `X-Demo-Actor-Id` are required UUID strings.

- [x] **Step 3: Define bounded error contract**

```java
public record ReservationErrorResponse(
        String code,
        String message,
        boolean retryable,
        String traceId,
        Integer stockAfter) {}
```

Never return stack traces, SQL messages or raw keys.

- [x] **Step 4: Run and commit**

```powershell
mvn.cmd -pl app/backend/xxxx-controller -Dtest=ReservationControllerTest test
git add -- app/backend/xxxx-controller/src/main/java/com/xxxx/ddd/controller/http/reservation app/backend/xxxx-controller/src/test/java/com/xxxx/ddd/controller/http/reservation
git commit -m "feat: expose reservation lifecycle api"
```

### Task 8.2: Protect create while reserving terminal capacity

**Files:**
- Modify: `app/backend/xxxx-start/src/main/resources/application.yml`
- Modify: `ReservationController.java`
- Test: `ReservationAdmissionIntegrationTest.java`

- [x] **Step 1: Write failing saturation test**

Flood create while issuing confirm/release requests; assert create is shed and terminal requests can acquire DB capacity.

- [x] **Step 2: Add exact Resilience4j config**

```yaml
resilience4j:
  ratelimiter:
    instances:
      reservationCreate:
        limitForPeriod: 40
        limitRefreshPeriod: 1s
        timeoutDuration: 0
  bulkhead:
    instances:
      reservationCreate:
        maxConcurrentCalls: 4
        maxWaitDuration: 0
      reservationTerminal:
        maxConcurrentCalls: 2
        maxWaitDuration: 100ms
```

- [x] **Step 3: Map rejection semantics**

Rate limiter rejection → 429. Bulkhead/dependency saturation → 503. Both include `Retry-After: 1`.

- [ ] **Step 4: Run and commit**

```powershell
mvn.cmd -pl app/backend/xxxx-start -Dtest=ReservationAdmissionIntegrationTest test
git add -- app/backend/xxxx-start/src/main/resources/application.yml app/backend/xxxx-controller/src/main/java/com/xxxx/ddd/controller/http/reservation/ReservationController.java app/backend/xxxx-start/src/test/java/com/xxxx/ddd/integration/ReservationAdmissionIntegrationTest.java
git commit -m "feat: add reservation admission lanes"
```

---

## Phase 9 — Deterministic chaos testing

### Task 9.1: Add chaos-only fault injection

**Files:**
- Create chaos adapter/controller under `app/backend/xxxx-start/src/main/java/com/xxxx/ddd/chaos/`
- Create: `app/backend/xxxx-start/src/main/resources/application-chaos.yml`
- Test: `ReservationChaosIntegrationTest.java`

**Interfaces:**
- Fault points: `AFTER_REDIS_BEFORE_DB`, `AFTER_DB_COMMIT_BEFORE_RESPONSE`, `REDIS_MIRROR_TIMEOUT`, `KAFKA_UNAVAILABLE`, `CONFIRM_EXPIRE_RACE`.
- Beans exist only under `@Profile("chaos")`.

- [x] **Step 1: Write profile isolation tests**

Default profile must not contain chaos beans or demo fault endpoints. Chaos profile must expose the finite scenario catalog.

- [x] **Step 2: Implement deterministic injector**

```java
@Component
@Profile("chaos")
final class ConfigurableFaultInjection implements FaultInjectionPort {
    private final AtomicReference<FaultPoint> active = new AtomicReference<>();

    @Override
    public void hit(FaultPoint point, UUID operationId) {
        if (point == active.get()) {
            throw new InjectedFaultException(point, operationId);
        }
    }
}
```

- [x] **Step 3: Add Toxiproxy-backed dependency faults**

Network partition tests must first assert the Toxiproxy control endpoint and configured Redis/Kafka proxy paths are healthy, then interrupt Redis and Kafka at protocol boundaries through Toxiproxy and assert health/reachability after toxic removal; do not fake connection failures with mocks in integration evidence. If Toxiproxy or the proxy-path health checks are unavailable, the dependency-fault certification gate is failed rather than substituted with mock evidence.

- [x] **Step 4: Assert convergence**

Each scenario must end with no negative stock, invariant true, no duplicate order and pending recovery/outbox equal to zero within 30 seconds after dependency recovery.

- [ ] **Step 5: Run and commit**

```powershell
mvn.cmd -pl app/backend/xxxx-start -Dtest=ReservationChaosIntegrationTest test
git add -- app/backend/xxxx-start/src/main/java/com/xxxx/ddd/chaos app/backend/xxxx-start/src/main/resources/application-chaos.yml app/backend/xxxx-start/src/test/java/com/xxxx/ddd/integration/ReservationChaosIntegrationTest.java
git commit -m "test: add deterministic reservation chaos scenarios"
```

---

## Phase 10 — Observability contract and dashboard

### Task 10.1: Instrument bounded metrics and traces

**Files:**
- Create: `app/backend/xxxx-start/src/main/java/com/xxxx/ddd/observability/ReservationTelemetryAdapter.java`
- Create: `docs/observability/reservation-telemetry-schema.md`
- Test: `ReservationTelemetryTest.java`

**Interfaces:**

```text
flashsale_reservation_operation_seconds{operation,outcome}
flashsale_reservation_transitions_total{operation,outcome,reason}
flashsale_reservation_units{status}
flashsale_admission_rejections_total{operation,reason}
flashsale_recovery_operations{state}
flashsale_outbox_oldest_age_seconds
flashsale_inventory_drift_units
flashsale_redis_mirror_pending
```

- [x] **Step 1: Write failing metric cardinality tests**

Assert meter IDs contain only `operation`, `outcome`, `reason`, `status` or `state`; reject raw IDs as tags.

- [x] **Step 2: Implement fixed span names**

```text
flashsale.reservation.create
flashsale.reservation.confirm
flashsale.reservation.release
flashsale.reservation.expire
flashsale.reservation.recover
flashsale.outbox.publish
```

- [x] **Step 3: Write telemetry schema**

For each metric/span document unit, bounded attributes, emission point and portfolio interpretation. This adapts OTel Demo's schema-first approach without migrating from Brave.

- [ ] **Step 4: Run and commit**

```powershell
mvn.cmd -pl app/backend/xxxx-start -Dtest=ReservationTelemetryTest test
git add -- app/backend/xxxx-start/src/main/java/com/xxxx/ddd/observability docs/observability/reservation-telemetry-schema.md app/backend/xxxx-start/src/test/java/com/xxxx/ddd/observability/ReservationTelemetryTest.java
git commit -m "feat: instrument reservation reliability signals"
```

### Task 10.2: Provision the System X-Ray dashboard

**Files:**
- Create: `environment/grafana/provisioning/dashboards/flashsale-reservation-reliability.json`
- Modify the existing dashboard provider only if needed to discover this file.

- [x] **Step 1: Add panels in narrative order**

```text
1. Request throughput and p95/p99
2. 429/503 admission reasons
3. Available/Reserved/Confirmed stock buckets
4. Recovery journal states
5. Outbox oldest age and backlog
6. Redis/MySQL drift and convergence
```

- [x] **Step 2: Validate provisioning**

```powershell
docker compose -f environment/docker-compose-dev.yml --profile observability config
```

Expected: rendered Compose config contains the dashboard mount with no schema error.

- [ ] **Step 3: Commit**

```powershell
git add -- environment/grafana
git commit -m "feat: add reservation reliability dashboard"
```

---

## Phase 11 — Demo UI without real auth

### Task 11.1: Add typed reservation client

**Files:**
- Create: `app/frontend/src/lib/reservation-types.ts`
- Create: `app/frontend/src/lib/reservation-client.ts`
- Test: `app/frontend/src/lib/reservation-client.test.ts`

**Interfaces:**
- Session actor: `crypto.randomUUID()` in `sessionStorage` key `flashsale.demoActorId`.
- New idempotency key per user intent; retry reuses the same key.

- [x] **Step 1: Write failing client tests**

Prove required headers, 201/200/202 parsing, retry key reuse and error mapping.

- [x] **Step 2: Implement actor helper**

```ts
export function getDemoActorId(): string {
  const key = "flashsale.demoActorId";
  const existing = sessionStorage.getItem(key);
  if (existing) return existing;
  const created = crypto.randomUUID();
  sessionStorage.setItem(key, created);
  return created;
}
```

- [x] **Step 3: Run frontend tests/typecheck**

```powershell
Push-Location app/frontend
npm.cmd run typecheck
Pop-Location
```

- [x] **Step 4: Commit**

```powershell
git add -- app/frontend/src/lib/reservation-types.ts app/frontend/src/lib/reservation-client.ts app/frontend/src/lib/reservation-client.test.ts
git commit -m "feat: add typed reservation client"
```

### Task 11.2: Build Customer + System X-Ray journey

**Files:**
- Create: `app/frontend/src/components/reservation-journey.tsx`
- Create: `app/frontend/src/components/reservation-stock-buckets.tsx`
- Create: `app/frontend/src/components/reservation-timeline.tsx`
- Create: `app/frontend/src/components/demo-scenario-drawer.tsx`
- Modify: `app/frontend/src/app/(public)/events/[ticketItemId]/page.tsx`
- Test: `app/frontend/e2e/specs/reservation-journey.spec.ts`

- [x] **Step 1: Write failing E2E journey**

Test reserve → countdown → confirm, reserve → release, reserve → expiry, duplicate replay, sold-out, overload and hidden chaos drawer in normal profile.

- [x] **Step 2: Implement one contextual CTA**

```text
No reservation: Reserve ticket
RESERVED: Confirm purchase
CONFIRMED: View confirmed order
RELEASED/EXPIRED: Try again
```

Release/expiry/duplicate/overload live in “Try another outcome” drawer to keep the main screen simple.

- [x] **Step 3: Render business-readable stock and timeline**

Show Available, Reserved and Confirmed buckets plus a 120-second countdown. Timeline data must come from API state; do not fabricate completed stages client-side.

- [x] **Step 4: Group old tools**

Keep benchmark and consistency pages, but link them under “Engineering Evidence” rather than placing them in the primary customer journey.

- [ ] **Step 5: Run UI gates and commit**

```powershell
Push-Location app/frontend
npm.cmd run lint
npm.cmd run typecheck
npm.cmd run build
npm.cmd run test:e2e -- reservation-journey.spec.ts
Pop-Location
git add -- app/frontend/src/lib app/frontend/src/components/reservation-journey.tsx app/frontend/src/components/reservation-stock-buckets.tsx app/frontend/src/components/reservation-timeline.tsx app/frontend/src/components/demo-scenario-drawer.tsx 'app/frontend/src/app/(public)/events/[ticketItemId]/page.tsx' app/frontend/e2e/specs/reservation-journey.spec.ts
git commit -m "feat: add reservation system x-ray demo"
```

---

## Phase 12 — Integrated correctness suite

### Task 12.1: Make integration tests mandatory

**Files:**
- Modify: `app/backend/xxxx-start/pom.xml`
- Modify/add CI workflow that runs Maven verification.
- Create: `ReservationEndToEndIntegrationTest.java`

- [x] **Step 1: Remove optional skip behavior**

`mvn.cmd verify -Pflashsale-integration` must execute MySQL, Redis and Kafka tests in CI. A missing Docker runtime must fail the integration job rather than silently skip tests.

- [x] **Step 2: Add end-to-end invariant test**

Seed 100 units; submit 500 concurrent reservation attempts with quantities 1–4; confirm/release/expire accepted reservations; assert:

```text
available >= 0
exactly one reservation per successful idempotency key
exactly one order per confirmed reservation
initial = available + reserved + confirmed
Redis available = MySQL available after convergence
pending journal = 0
pending outbox = 0 after Kafka recovery
```

- [ ] **Step 3: Run full backend verification**

```powershell
mvn.cmd clean verify -Pflashsale-integration
```

Expected: all unit/integration tests pass and no critical test is skipped.

- [ ] **Step 4: Check coverage**

Expected: line coverage for newly added reservation packages is at least 80%; correctness services/state transitions target at least 90% branch coverage.

- [ ] **Step 5: Commit**

```powershell
git add -- app/backend/xxxx-start/pom.xml app/backend/xxxx-start/src/test/java/com/xxxx/ddd/integration/ReservationEndToEndIntegrationTest.java .github/workflows/ci.yml
git commit -m "test: enforce reservation integration gates"
```

---

## Phase 13 — JMeter workload and evidence

### Task 13.1: Extend the existing benchmark harness

**Files:**
- Create: `benchmark/flash-sale-reservation.jmx`
- Create: `benchmark/reservation-experiment-spec.json`
- Create: `benchmark/run-reservation-jmeter.ps1`
- Modify: `benchmark/jmeter/README.md`

**Interfaces:**
- Healthy scenario, duplicate retry scenario, overload scenario and dependency-recovery scenario.

- [x] **Step 1: Define manifest**

```json
{
  "api": "/api/v1/reservations",
  "stock": 1000,
  "threads": 100,
  "attempts": 5000,
  "quantityDistribution": [1, 1, 1, 2, 2, 3, 4],
  "reservationTtlSeconds": 120,
  "createLimitPerSecondPerInstance": 40,
  "scenarios": ["healthy", "duplicate-retry", "overload", "kafka-recovery"]
}
```

- [x] **Step 2: Propagate correlation**

Each virtual user gets one `X-Demo-Actor-Id`; each logical action gets one idempotency key reused on retry. Add W3C `traceparent` where supported and mark load traffic as synthetic in a bounded header/trace attribute.

- [x] **Step 3: Persist evidence metadata**

Every run directory contains:

```text
manifest.json
git.json
environment.json
fault-timeline.json
results.jtl
html/
consistency.json
convergence.json
summary.md
```

- [x] **Step 4: Run benchmark**

```powershell
./benchmark/run-reservation-jmeter.ps1
```

- [ ] **Step 5: Validate claims**

Healthy p95 must not regress more than 20% versus the same-SHA pre-change baseline on the same machine/config. Correctness gates are absolute: zero oversell, zero negative stock, zero duplicate order and zero final drift.

- [ ] **Step 6: Commit harness, not ad-hoc local output**

```powershell
git add -- benchmark/flash-sale-reservation.jmx benchmark/reservation-experiment-spec.json benchmark/run-reservation-jmeter.ps1 benchmark/jmeter/README.md
git commit -m "perf: add reservation reliability workload"
```

---

## Phase 14 — Effectiveness measurement and full browser control audit

### Task 14.1: Measure correctness, performance and recovery effectiveness

**Files:**
- Create: `benchmark/measure-reservation-effectiveness.ps1`
- Create: `benchmark/effectiveness-thresholds.json`
- Create: `docs/reports/reservation-effectiveness-report.md`
- Produce: `benchmark/results/reservation-effectiveness-<UTC timestamp>/`

**Interfaces:**
- Consumes: baseline from Phase 0, integrated reservation build, JMeter harness, Prometheus metrics and chaos scenarios.
- Produces: before/after and healthy/fault evidence tied to exact SHA and environment.

- [x] **Step 1: Lock measurement thresholds before running the test**

Create this threshold file before collecting results:

```json
{
  "correctness": {
    "maxOversoldUnits": 0,
    "maxNegativeStockObservations": 0,
    "maxDuplicateReservations": 0,
    "maxDuplicateOrders": 0,
    "maxFinalDriftUnits": 0
  },
  "recovery": {
    "maxConvergenceSeconds": 30,
    "maxPendingJournalAfterConvergence": 0,
    "maxPendingOutboxAfterConvergence": 0
  },
  "performance": {
    "maxHealthyP95RegressionPercent": 20,
    "minTerminalSuccessPercentDuringCreateFlood": 99,
    "maxUnexpectedHttpFailurePercent": 0
  },
  "ui": {
    "requiredControlPassPercent": 100,
    "maxUnexpectedConsoleErrors": 0,
    "maxUnexpectedNetworkFailures": 0
  }
}
```

- [x] **Step 2: Capture exact execution identity**

```powershell
git rev-parse HEAD
git status --porcelain=v1
docker version
docker compose version
java -version
mvn.cmd -version
node --version
npm --version
jmeter --version
```

Write every output to the run's `environment.json` or `versions.txt` before load begins.

- [x] **Step 3: Run the healthy effectiveness workload**

Use identical stock, threads, attempts and quantity distribution for the historical baseline-compatible run and the reservation run. Collect:

```text
requests/second
average, p50, p95 and p99 latency
201/200/202/409/429/503 counts
accepted units and rejected units
Hikari active/idle/pending connections
Redis command latency
outbox oldest age
journal state counts
oversold/negative/drift units
```

- [x] **Step 4: Run overload and terminal-priority measurement**

Flood create at five times the configured 40 requests/second limit while issuing confirm/release traffic. Report create admission rejects, terminal success percentage, terminal p95 and maximum Hikari pending connections.

- [ ] **Step 5: Run each deterministic fault measurement**

For each fault, record activation time, dependency restoration time, first healthy request time and convergence time:

```text
AFTER_REDIS_BEFORE_DB
AFTER_DB_COMMIT_BEFORE_RESPONSE
REDIS_MIRROR_TIMEOUT
KAFKA_UNAVAILABLE
CONFIRM_EXPIRE_RACE
```

- [x] **Step 6: Generate the effectiveness report**

The report must contain tables for baseline versus upgraded behavior, healthy versus faulted behavior, measured improvements, regressions, threshold verdicts and raw artifact paths. Mark every number as local/environment-specific.

- [ ] **Step 7: Verify measurement gate**

```powershell
./benchmark/measure-reservation-effectiveness.ps1
```

Expected: exit `0` only when every correctness/recovery threshold passes. Performance regressions produce an explicit failed threshold, never a silently edited target.

### Task 14.2: Audit every interactive UI control with Chrome or the in-app browser

**Files:**
- Create: `docs/reports/ui-control-audit.md`
- Create: `docs/reports/ui-control-matrix.json`
- Create evidence images under: `docs/reports/ui-evidence/`
- Modify: `app/frontend/e2e/specs/reservation-journey.spec.ts` only when the browser audit exposes an uncovered real defect.

**Interfaces:**
- Browser surface: connected Chrome through `chrome:control-chrome`; if Chrome is unavailable, use the explicitly allowed in-app browser. Record which surface/version was used.
- Produces: one result row for every visible button, link, tab, form control and drawer action.

- [ ] **Step 1: Run automated browser regression first**

```powershell
Push-Location app/frontend
npm.cmd run test:e2e
Pop-Location
```

Expected: existing and new Playwright journeys pass before manual control inspection begins.

- [ ] **Step 2: Start the integrated runtime and verify readiness**

```powershell
docker compose -f environment/docker-compose-dev.yml --profile observability up -d
./benchmark/smoke-local.ps1
```

Expected: frontend, backend, MySQL, Redis, Kafka, Prometheus and Grafana health checks pass.

- [ ] **Step 3: Build the control inventory before clicking**

Use the selected browser to visit every public/admin page. Record each control with this schema:

```json
{
  "page": "/events/4",
  "controlRole": "button",
  "accessibleName": "Reserve ticket",
  "precondition": "No active reservation",
  "expectedRequest": "POST /api/v1/reservations",
  "expectedUiState": "RESERVED with countdown",
  "result": "PASS",
  "consoleErrors": 0,
  "unexpectedNetworkFailures": 0,
  "evidence": "docs/reports/ui-evidence/events-4-reserve.png"
}
```

Inventory all controls before execution so failed/hidden controls cannot disappear from the denominator.

- [ ] **Step 4: Exercise every primary business control**

At minimum verify event navigation, quantity selection, reserve, confirm, released/expired try-again, order navigation, stock refresh and the contextual CTA for every lifecycle state.

- [ ] **Step 5: Exercise every scenario-drawer control**

Verify drawer open/close plus duplicate, sold-out, overload and expiry scenarios. Each action must produce the expected backend request/status, visible state, timeline event and stock buckets; a client-only simulated success is a failure.

- [ ] **Step 6: Exercise every Engineering Evidence/admin control**

Verify navigation links, tabs, refresh controls, benchmark start/status controls, consistency check, Redis warm/reset controls and every existing button retained after the UI reorganization. Destructive lab controls require a seeded disposable fixture and must restore it after the check.

- [ ] **Step 7: Capture browser diagnostics and user-visible timings**

For each page/state capture:

```text
navigation result
control pass/fail
unexpected console errors
unexpected 4xx/5xx network responses
API action duration
visible state update duration
LCP and CLS when exposed by the browser performance surface
desktop screenshot
narrow viewport screenshot
```

Expected final gate: 100% inventoried controls pass, zero unexpected console errors and zero unexpected network failures.

- [ ] **Step 8: Convert browser defects into TDD fixes**

For every failure: first add or tighten a Playwright/component test that reproduces it, observe RED, implement the smallest fix, rerun GREEN, then repeat the exact browser action and replace failed evidence with passing evidence. Do not patch UI behavior before a reproducing test exists.

- [ ] **Step 9: Write and commit the audit evidence**

```powershell
git status --porcelain=v1
git diff --stat
git add -- docs/reports/reservation-effectiveness-report.md docs/reports/ui-control-audit.md docs/reports/ui-control-matrix.json docs/reports/ui-evidence benchmark/measure-reservation-effectiveness.ps1 benchmark/effectiveness-thresholds.json
git commit -m "test: certify reservation effectiveness and ui controls" -m "Records same-SHA load, recovery, browser-control and per-control UI evidence."
```

After this commit, perform the named strategic compact before entering comparison or telemetry migration work.

---

## Phase 15 — Comparative MySQL reservation strategy

### Task 15.1: Add a common-strategy comparison only after Redis path passes

**Files:**
- Create: `app/backend/xxxx-application/src/main/java/com/xxxx/ddd/application/reservation/strategy/ReservationCoordinationStrategy.java`
- Create: `app/backend/xxxx-application/src/main/java/com/xxxx/ddd/application/reservation/strategy/ReservationStrategy.java`
- Create: `app/backend/xxxx-infrastructure/src/main/java/com/xxxx/ddd/infrastructure/reservation/mysql/MySqlConditionalReservationStrategy.java`
- Create: `benchmark/reservation-strategy-matrix.json`
- Test: `app/backend/xxxx-start/src/test/java/com/xxxx/ddd/integration/ReservationStrategyContractTest.java`

**Interfaces:**
- Both Redis-first and MySQL strategies consume the same command, lifecycle, durable tables, fixture and acceptance assertions.

- [x] **Step 1: Write shared contract tests**

Run every strategy against duplicate, sold-out, confirm/expiry race and invariant scenarios.

- [x] **Step 2: Implement MySQL conditional reservation**

Do not introduce one-row-per-unit or multi-warehouse in this comparison; otherwise the benchmark would compare data models rather than coordination strategies.

- [ ] **Step 3: Benchmark separately**

Reset/seed before each strategy, never run both against the same live stock account, and report throughput/latency/pool pressure separately from correctness.

- [ ] **Step 4: Commit**

```powershell
git add -- app/backend/xxxx-application/src/main/java/com/xxxx/ddd/application/reservation/strategy/ReservationCoordinationStrategy.java app/backend/xxxx-application/src/main/java/com/xxxx/ddd/application/reservation/strategy/ReservationStrategy.java app/backend/xxxx-infrastructure/src/main/java/com/xxxx/ddd/infrastructure/reservation/mysql/MySqlConditionalReservationStrategy.java app/backend/xxxx-start/src/test/java/com/xxxx/ddd/integration/ReservationStrategyContractTest.java benchmark/reservation-strategy-matrix.json
git commit -m "perf: compare reservation coordination strategies"
```

---

## Phase 16 — OTel/k6 evidence integration after stable baseline

### Task 16.1: Add a reversible OTel/k6 evidence path

**Files:**
- Create: `environment/otel/otel-collector.yml`
- Create: `environment/docker-compose-otel.yml`
- Create: `benchmark/flash-sale-reservation-k6.js`
- Create: `benchmark/run-reservation-k6.ps1`
- Create: `app/backend/xxxx-start/src/main/resources/application-otel.yml`
- Create: `docs/observability/otel-migration.md`
- Test: `app/backend/xxxx-start/src/test/java/com/xxxx/ddd/integration/OtelPipelineSmokeIntegrationTest.java`

- [x] **Step 1: Run a new Xia version audit**

Re-read pinned OTel Demo source because its official docs and main branch have already shown load-generator drift from Locust to k6.

- [x] **Step 2: Define a migration boundary**

Choose one tracing bridge at runtime; do not enable Brave and an OTel bridge simultaneously. Preserve existing Prometheus metric names or provide an explicit dashboard migration.

- [x] **Step 3: Port only these OTel ideas**

```text
typed default-off fault catalog
synthetic traffic marker
traceparent propagation
schema-first custom telemetry
route/span normalization
cardinality controls
layered observability Compose
```

- [ ] **Step 4: Prove parity before switching defaults**

The OTLP/k6 path must reproduce the same correctness/convergence assertions and dashboard signals as the JMeter/Micrometer path.

- [ ] **Step 5: Commit as a separate reversible change**

```powershell
git add -- environment/otel/otel-collector.yml environment/docker-compose-otel.yml benchmark/flash-sale-reservation-k6.js benchmark/run-reservation-k6.ps1 app/backend/xxxx-start/src/main/resources/application-otel.yml app/backend/xxxx-start/src/test/java/com/xxxx/ddd/integration/OtelPipelineSmokeIntegrationTest.java docs/observability/otel-migration.md
git commit -m "feat: add otel reservation evidence path"
```

---

## Phase 17 — Final documentation and release certification

### Task 17.1: Produce the final source-backed report

**Files:**
- Create: `docs/reports/flash-sale-reliability-upgrade-report.md`
- Modify: `README.md`
- Modify: `CLAUDE.md` when final commands or architecture guidance changed.
- Modify: `AGENTS.md` when final agent workflow, verification gate or reference reuse rule changed.
- Modify: `plan.md` checkboxes only as tasks are actually completed.

- [x] **Step 1: Record delivered scope**

Separate completed phases from unexecuted phases. Do not present Phases 15 or 16 as delivered unless their gates ran on the reported SHA.

- [x] **Step 2: Include reference adaptation ledger**

For every reused unit list repository, pinned SHA, source file/function, reuse mode (`DIRECT_COPY`, `LOGIC_PORT` or `DESIGN_REFERENCE`), target symbol and tests. State explicitly that the target may reuse source under the user-confirmed permissions while the reference clones themselves remained unmodified.

- [ ] **Step 3: Include exact certification evidence**

```text
target Git SHA
backend unit/integration test commands and counts
frontend lint/typecheck/build/E2E results
coverage result
Compose render/runtime result
JMeter/k6 artifact paths
fault scenario results
invariant and convergence result
effectiveness threshold verdicts
browser surface/version and 100% control audit result
console/network error counts and UI timing measurements
known limitations: per-instance rate limit, no real auth, one ticket/one location
```

- [ ] **Step 4: Update public and agent documentation**

Update `README.md` only with numbers from the final same-SHA evidence directory. Update `CLAUDE.md` and `AGENTS.md` only with stable commands, branch/TDD workflow, reference-reuse policy and browser certification location; remove any superseded command or claim rather than duplicating it.

- [ ] **Step 5: Run final verification**

```powershell
mvn.cmd clean verify -Pflashsale-integration
Push-Location app/frontend
npm.cmd run lint
npm.cmd run typecheck
npm.cmd run build
npm.cmd run test:e2e
Pop-Location
./benchmark/measure-reservation-effectiveness.ps1
docker compose -f environment/docker-compose-dev.yml --profile observability config
git -C reference/saleor status --short
git -C reference/opentelemetry-demo status --short
git status --short
```

Expected: all required gates pass, both references remain clean, and only intentional target files are staged/modified.

- [ ] **Step 6: Commit final documentation with smart-commits**

```powershell
git status --porcelain=v1
git diff --stat
git add -- README.md CLAUDE.md AGENTS.md plan.md docs/reports/flash-sale-reliability-upgrade-report.md
git commit -m "docs: certify flash sale reservation reliability upgrade" -m "Publishes only same-SHA correctness, performance, recovery and browser-control evidence."
```

---

## Final Acceptance Checklist

- [ ] `/orders` regression suite and legacy benchmark behavior remain unchanged.
- [ ] No negative stock or oversell under healthy, overloaded or fault-injected runs.
- [x] Same idempotency key and payload replay the same reservation.
- [x] Same key with another payload returns 409 without stock mutation.
- [x] Confirm creates exactly one order; release/expire restore exactly once.
- [ ] MySQL invariant holds per ticket after every converged run.
- [ ] Redis/MySQL drift converges to zero within 30 seconds after recovery.
- [ ] Create overload is rejected at admission boundary without starving terminal operations.
- [ ] No critical integration test is skipped in CI.
- [ ] New reservation code reaches at least 80% coverage.
- [x] UI contains no login and makes the demo-only identity limitation explicit.
- [x] Metrics contain no high-cardinality identifiers.
- [ ] Benchmark/report claims are tied to exact SHA, environment and raw artifacts.
- [x] Both reference repositories remain unchanged at their pinned SHAs.
- [x] Every directly copied or cross-language-ported unit has a source mapping and a target test that was observed failing before implementation.
- [ ] Effectiveness report contains exact-SHA correctness, throughput, p50/p95/p99, admission, recovery and convergence measurements.
- [ ] Chrome or in-app browser audit inventories and executes 100% of visible buttons, links, tabs, form controls and drawer actions.
- [ ] Browser audit ends with 100% control pass rate, zero unexpected console errors and zero unexpected network failures.
- [ ] `README.md` contains only final measured results; `CLAUDE.md` and `AGENTS.md` reflect any stable new workflow/verification commands without duplicating the plan.
- [x] No Git worktree was created or used during execution.
