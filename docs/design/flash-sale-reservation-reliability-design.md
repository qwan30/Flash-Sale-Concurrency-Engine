# Flash-Sale Reservation Reliability Design

## Scope and compatibility boundary

The reservation system is a new v1 surface under `/api/v1/reservations`. The existing `/orders` route, response envelope, strategy names, benchmark behavior, and local lab controls remain compatible. Reservation v1 accepts one `ticketItemId`, one inventory location represented by the stock account, quantity 1–4, and a default TTL of 120 seconds. `X-Demo-Actor-Id` identifies a demo session only; it is not authentication or an authorization boundary.

MySQL is the durable source of truth. Redis is a fast admission counter and a convergent mirror. Redis is never allowed to overwrite durable stock while admission is open. Every path that changes durable reservation state is idempotent and emits an outbox event in the same MySQL transaction as the state change.

## Reservation creation sequence

```text
validate request and derive SHA-256 fingerprint
        |
durably claim actor + idempotency hash in operation journal (RECEIVED)
        |
Redis Lua apply-once using operationId and stock key
        |-- SOLD_OUT / CONFLICT / replay -> return bounded result
        |
MySQL transaction: conditional stock decrement
                   + RESERVED reservation
                   + reservation.created outbox event
                   + journal COMMITTED
        |
return durable reservation and converged stock snapshot
```

The journal insert/claim commits before Redis. The MySQL reservation transaction is separate from the Redis operation. If the database transaction fails after Redis returns `APPLIED`, the service invokes `compensateOnce` and records a terminal `COMPENSATED` disposition. A crash in any gap is recovered from the journal and the Redis operation token; the same `operationId` prevents a second Redis mutation.

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
| `RECEIVED` with an `APPLIED` Redis token | Finalize the MySQL reservation or compensate |
| `REDIS_APPLIED` with no reservation | Finalize the MySQL reservation or compensate |
| `COMMITTED` with no response | Return/replay the durable reservation |
| `COMPENSATED` | Keep the stable terminal journal state |
| five failed attempts | Mark `FAILED` and emit the alert metric |

An expiry scheduler finds due `RESERVED` rows and executes the same conditional expiry service. A Redis mirror failure never rolls back a durable terminal transition; it creates a retryable mirror journal entry.

## Admission and fault injection

Reservation creation receives a fixed 40 permits/second per instance and a four-call create bulkhead. Terminal operations use a separate two-call bulkhead with a 100 ms wait, so create floods cannot consume all terminal capacity. Rejections map to 429 for rate limiting and 503 for saturation, each with `Retry-After: 1`.

Fault injection exists only under the `chaos` profile and has a finite catalog: `AFTER_REDIS_BEFORE_DB`, `AFTER_DB_COMMIT_BEFORE_RESPONSE`, `REDIS_MIRROR_TIMEOUT`, `KAFKA_UNAVAILABLE`, and `CONFIRM_EXPIRE_RACE`. Redis and Kafka integration tests use protocol-boundary faults (Toxiproxy where available), not mock-only failure evidence. Every scenario must converge within 30 seconds after dependency recovery with no negative stock, duplicate order, invariant violation, or pending journal/outbox work.

## Observability

Telemetry uses fixed operation/outcome/reason/status/state labels only. The initial Brave/Micrometer path remains the default. Span names are fixed: `flashsale.reservation.create`, `flashsale.reservation.confirm`, `flashsale.reservation.release`, `flashsale.reservation.expire`, `flashsale.reservation.recover`, and `flashsale.outbox.publish`. The dashboard narrative is throughput/latency, admission, stock buckets, recovery, outbox, and Redis/MySQL convergence.

## Verification gates

Each product task follows RED → GREEN → REFACTOR: write one behavior test, observe the expected failure, implement the smallest change, rerun the narrow test, refactor only while green, and run the module/coverage gate. Integration evidence must run with MySQL, Redis, and Kafka rather than silently skipping. Final claims are tied to the exact Git SHA, environment, raw artifacts, and browser control audit; local benchmark values are not production guarantees.
