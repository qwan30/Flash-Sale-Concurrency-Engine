# Reservation API, State, and Invariant Contract

## API surface

| Method | Route | Success behavior |
|---|---|---|
| Method | Route | 2xx result and response body | 4xx/5xx result |
|---|---|---|---|
| `POST` | `/api/v1/reservations` | `201` with `ReservationResponse` for a new `RESERVED`; `200` with the same response for an idempotent replay; `202` with `ReservationProcessingResponse` when a claimed operation is still recoverable | `400` validation; `409` sold out or idempotency conflict; `429` rate limit; `503` saturation/dependency |
| `GET` | `/api/v1/reservations/{reservationId}` | `200` with `ReservationResponse` for any durable state, including `CONFIRMED`, `RELEASED`, and `EXPIRED` | `404` when the reservation does not exist; `503` dependency saturation |
| `POST` | `/api/v1/reservations/{reservationId}/confirm` | `200` with the confirmed reservation and one order; duplicate confirm replays the same order | `400` malformed request; `404` missing reservation; `409` expired/late or illegal transition; `429`/`503` admission/dependency rejection |
| `POST` | `/api/v1/reservations/{reservationId}/release` | `200` with the released reservation; duplicate release replays the state without another increment | `400` malformed request; `404` missing reservation; `409` illegal transition; `429`/`503` admission/dependency rejection |
| `GET` | `/api/v1/inventory/{ticketItemId}` | `200` with available, reserved, confirmed, initial, and convergence state | `404` unknown ticket item; `503` when the durable snapshot cannot be read |

`ReservationResponse` contains `reservationId`, `ticketItemId`, `quantity`, `status`, `expiresAt`, `terminalAt`, an optional order identifier, a stock snapshot, and the API timeline. `ReservationProcessingResponse` contains the generated `reservationId`, `status=PROCESSING`, `retryAfterSeconds=1`, and a bounded trace ID; clients poll the GET route. `ReservationErrorResponse` is used for every error row.

Create requires `Idempotency-Key` and `X-Demo-Actor-Id` UUID strings. The JSON body contains a positive `ticketItemId` and `quantity` from 1 through 4. Error responses are the bounded record:

```json
{
  "code": "SOLD_OUT",
  "message": "No inventory is available for this request",
  "retryable": false,
  "traceId": "bounded-trace-id"
}
```

Errors never contain stack traces, SQL messages, raw idempotency keys, or unbounded user-controlled values. Create replays of a terminal reservation are not new reservations: they return the original durable `ReservationResponse`; a confirm request at or after `expires_at` returns 409 and causes the conditional expiry transition to win. Rate-limit rejection is 429; dependency/bulkhead saturation is 503 with `Retry-After: 1`.

## Reservation transition matrix

| From | To | Allowed | Side effects |
|---|---|---:|---|
| `RESERVED` | `CONFIRMED` | yes, only before expiry | one `reservation_order`, one `reservation.confirmed` outbox event |
| `RESERVED` | `RELEASED` | yes | restore quantity, one `reservation.released` outbox event |
| `RESERVED` | `EXPIRED` | yes | restore quantity, one `reservation.expired` outbox event |
| `CONFIRMED` | any other state | no | replay current order/state |
| `RELEASED` | any other state | no | replay current state |
| `EXPIRED` | any other state | no | replay current state |

The database conditional update is the winner for confirm-versus-expire and duplicate terminal races. A second worker sees zero affected rows and returns the already durable state without another stock mutation.

## Operation journal states

| State | Meaning | Retry disposition |
|---|---|---|
| `RECEIVED` | durable request/operation claim exists | apply or inspect Redis token |
| `REDIS_APPLIED` | Redis accepted the operation | finalize database or compensate |
| `COMMITTED` | reservation and outbox are durable | replay response/mirror |
| `COMPENSATED` | Redis admission was restored after DB failure | stable terminal result |
| `COMPENSATION_PENDING` | compensation has not completed | retry `compensateOnce`; not converged |
| `MIRROR_PENDING` | durable terminal state exists but Redis mirror is not repaired | retry `mirrorTerminalOnce`; not converged |
| `REPAIR_REQUIRED` | bounded retries exhausted without a safe repair | alert, close admission, and run MySQL-authoritative repair; certification is NO-GO until cleared |

The journal lease is exclusive for the lease window. An expired lease can be reclaimed; `operationId` remains the idempotency token across retries. `REPAIR_REQUIRED` is not counted as convergence and cannot be silently acknowledged as success.

## Idempotency contract

The durable uniqueness boundary is `(demo_actor_id, SHA-256(Idempotency-Key))`. For one actor and key:

| Request relationship | Result |
|---|---|
| Same key and same fingerprint | Replay the original result; no stock mutation |
| Same key and different fingerprint | 409 `IDEMPOTENCY_CONFLICT`; no stock mutation |
| Different key | Independent operation, subject to available stock |

The request fingerprint is `SHA-256("ticketItemId=<decimal>&quantity=<decimal>")`. Only the digest is stored and compared.

## Inventory invariant

For every ticket item after each committed transaction:

```text
initial_quantity
  = available_quantity
  + SUM(quantity WHERE status = 'RESERVED')
  + SUM(quantity WHERE status = 'CONFIRMED')
```

The quantities for `RELEASED` and `EXPIRED` reservations are no longer counted because their terminal transition restores `available_quantity` exactly once. Required bounds are `available_quantity >= 0`, `available_quantity <= initial_quantity`, and all reservation quantities in `[1,4]`.

After recovery convergence:

```text
Redis available quantity = MySQL available quantity
pending recovery journal = 0
pending outbox work = 0
duplicate reservation count = 0
duplicate order count = 0
```

These are verification assertions, not claims that a local unit test or a source file alone proves them. The integrated correctness suite, chaos runs, and dated benchmark artifacts must provide the evidence.
