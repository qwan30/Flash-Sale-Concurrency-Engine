# Flash-Sale Reservation Reliability Design

## Scope and compatibility boundary

The reservation system is a new v1 surface under `/api/v1/reservations`. The existing `/orders` route, response envelope, strategy names, benchmark behavior, and local lab controls remain compatible. Reservation v1 accepts one `ticketItemId`, one inventory location represented by the stock account, quantity 1–4, and a default TTL of 120 seconds. `X-Demo-Actor-Id` identifies a demo session only; it is not authentication or an authorization boundary.

MySQL is the durable source of truth. Redis is a fast admission counter and a convergent mirror. Redis is never allowed to overwrite durable stock while admission is open. Every path that changes durable reservation state is idempotent and emits an outbox event in the same MySQL transaction as the state change.

## Reservation creation sequence

```text
validate request, generate operation/reservation IDs, and derive SHA-256 fingerprint
        |
durably claim actor + idempotency hash in operation journal (RECEIVED)
        |
Redis Lua apply-once using operationId and stock key
        |-- SOLD_OUT -> persist REJECTED(SOLD_OUT, stockAfter) and return bounded result
        |-- STALE_FENCE -> persist REJECTED(FENCE_STALE, stockAfter) and return bounded result
        |-- CONFLICT / replay -> return the durable bounded result
        |
MySQL transaction: conditional stock decrement
                   + RESERVED reservation
                   + reservation.created outbox event
                   + journal COMMITTED
        |
return durable reservation and converged stock snapshot
```

The service generates `operationId` and `reservationId` before the claim. For `operation_type=CREATE`, the journal has a unique `(demo_actor_id, idempotency_key_hash)` boundary; terminal and mirror work reuses the original create journal row and operation ID rather than claiming a new idempotency tuple. A duplicate claim returns the existing operation state and cannot admit a second Redis operation. The MySQL reservation transaction is separate from the Redis operation. If Redis returns `SOLD_OUT` or `STALE_FENCE`, the service durably records `REJECTED` with `result_code` and `result_stock_after` before returning; that result is stable and replay never starts a new Redis operation. If the database transaction fails after Redis returns `APPLIED`, the service invokes `compensateOnce`; a successful compensation records `COMPENSATED`, while a failed compensation records `COMPENSATION_PENDING` for recovery. A crash in any gap is recovered from the journal and the Redis operation token; the same `operationId` prevents a second Redis mutation.

The stock account's `fence_version` is the admission fence. The create journal stores the fence observed at claim time, the MySQL decrement requires both `admission_state = OPEN` and that fence, and Redis stores the same fence beside its stock counter. Every apply, compensation, and terminal-mirror Lua call receives `fenceVersion`; a mismatch returns `STALE_FENCE` without mutating stock. Repair atomically changes the MySQL account to `DRAINING` and increments the fence, publishes the new fence to Redis, drains or rejects old-fence leases, and quiesces in-flight old-fence operations before closing admission. While admission is closed, repair uses the MySQL snapshot as authority, restores the Redis mirror, verifies equality and zero stale operations, then reopens admission. Delayed operations carrying the old fence must therefore fail closed and cannot mutate after repair.

The canonical request fingerprint is:

```text
SHA-256("ticketItemId=<decimal>&quantity=<decimal>")
```

Only the hash of `Idempotency-Key` is persisted. Raw keys, actor IDs, reservation IDs, order IDs, and ticket IDs are not metric labels and are not written to error responses.

## Lifecycle and terminal operations

```text
RESERVED --confirm--> CONFIRMED --(no transition)--> terminal
RESERVED --release--> RELEASED   --(no transition)--> terminal
RESERVED --expiry-->  EXPIRED    --(no transition)--> terminal
```

Confirm uses a conditional state transition and creates one `reservation_order` row under a unique reservation constraint; it does not decrement stock again. Release and expiry use a conditional terminal transition and restore stock exactly once in the same transaction as the state change and outbox event. Database time (`UTC_TIMESTAMP(6)`) is authoritative; `expires_at > UTC_TIMESTAMP(6)` is the only valid confirm condition and equality is expired. A terminal Redis mirror is applied after the durable commit and retried independently if Redis is unavailable.

## Recovery and scheduled work

Recovery workers claim journal rows with a 30-second lease, batch size 50, retry delays 1/2/4/8/16 seconds, and a maximum of five attempts. The deterministic dispositions are:

| Durable/journal observation | Recovery action |
|---|---|
| `RECEIVED` with no Redis operation token | Retry `applyOnce` |
| `RECEIVED` with a stale fence | Persist `REJECTED(FENCE_STALE)` and do not retry |
| `RECEIVED` with an `APPLIED` Redis token | Finalize the MySQL reservation or compensate |
| `REDIS_APPLIED` with no reservation | Finalize the MySQL reservation or compensate |
| `COMMITTED` with no response | Return/replay the durable reservation |
| `REJECTED` with `SOLD_OUT` or `FENCE_STALE` | Return the stored bounded result; never retry Redis |
| `COMPENSATED` | Keep the stable terminal journal state |
| `COMPENSATION_PENDING` | Retry `compensateOnce`; only success can become `COMPENSATED` |
| `MIRROR_PENDING` | Retry `mirrorTerminalOnce`; only success can return to converged `COMMITTED` |
| `REPAIR_REQUIRED` | Close admission and run fenced MySQL-authoritative repair; never report success while uncleared |
| five failed repair attempts | Mark `REPAIR_REQUIRED`, emit the alert metric, and fail certification |

An expiry scheduler finds due `RESERVED` rows and executes the same conditional expiry service. A Redis mirror failure never rolls back a durable terminal transition; it creates a `MIRROR_PENDING` journal entry. `REPAIR_REQUIRED` is not a successful terminal state: a MySQL-authoritative repair job may clear it only after the admission fence has drained stale leases, Redis is reachable, and admission is closed for the affected ticket. Any pending or repair-required state fails the zero-pending convergence gate.

## Admission and fault injection

Reservation creation receives a fixed 40 permits/second per instance and a four-call create bulkhead. Terminal operations use a separate two-call bulkhead with a 100 ms wait, so create floods cannot consume all terminal capacity. Rejections map to 429 for rate limiting and 503 for saturation, each with `Retry-After: 1`.

Fault injection exists only under the `chaos` profile and has a finite catalog: `AFTER_REDIS_BEFORE_DB`, `AFTER_DB_COMMIT_BEFORE_RESPONSE`, `REDIS_MIRROR_TIMEOUT`, `KAFKA_UNAVAILABLE`, and `CONFIRM_EXPIRE_RACE`. Before each dependency scenario, the integration test must assert the Toxiproxy control endpoint is healthy and the application is using the expected Redis/Kafka proxy path; after the toxic is removed it must assert proxy health and dependency reachability again. Redis and Kafka integration tests must use protocol-boundary faults through Toxiproxy, not mock-only failure evidence; if Toxiproxy or its proxy-path health checks are unavailable, the dependency-fault certification gate is failed. Every passing scenario must converge within 30 seconds after dependency recovery with no negative stock, duplicate order, invariant violation, or pending journal/outbox work.

## Observability

Telemetry uses fixed operation/outcome/reason/status/state labels only. The initial Brave/Micrometer path remains the default. Span names are fixed: `flashsale.reservation.create`, `flashsale.reservation.confirm`, `flashsale.reservation.release`, `flashsale.reservation.expire`, `flashsale.reservation.recover`, and `flashsale.outbox.publish`. The dashboard narrative is throughput/latency, admission, stock buckets, recovery, outbox, and Redis/MySQL convergence.

## Contract ownership

| Contract | First implementation phase | Primary verification |
|---|---|---|
| Schema and migration adoption | Phase 1 | `FlywayMigrationIntegrationTest` |
| Domain transitions and invariant | Phase 2 | `ReservationTest` and integrated invariant assertions |
| Durable claim, idempotency, and leases | Phase 3 | `ReservationPersistenceIntegrationTest` |
| Redis apply/compensate/mirror protocol | Phase 4 | `RedisReservationProtocolIntegrationTest` |
| Lifecycle, recovery, and repair dispositions | Phases 5–7 | focused service and recovery integration tests |
| API status/error contract | Phase 8 | `ReservationControllerTest` |
| Chaos, telemetry, UI, and evidence | Phases 9–14 | named chaos, telemetry, E2E, effectiveness, and browser artifacts |

## Verification gates

Each product task follows RED → GREEN → REFACTOR: write one behavior test, observe the expected failure, implement the smallest change, rerun the narrow test, refactor only while green, and run the module/coverage gate. Integration evidence must run with MySQL, Redis, and Kafka rather than silently skipping. Final claims are tied to the exact Git SHA, environment, raw artifacts, and browser control audit; local benchmark values are not production guarantees.
