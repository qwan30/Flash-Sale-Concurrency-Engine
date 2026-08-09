# Reservation API, State, and Invariant Contract

## API surface

| Method | Route | Success behavior |
|---|---|---|
| `POST` | `/api/v1/reservations` | Creates or replays a reservation; new creates return 201, replay/terminal transition returns 200, recovery-in-progress returns 202 |
| `GET` | `/api/v1/reservations/{reservationId}` | Returns the durable reservation and timeline state |
| `POST` | `/api/v1/reservations/{reservationId}/confirm` | Conditionally confirms a live reservation and creates one order |
| `POST` | `/api/v1/reservations/{reservationId}/release` | Conditionally releases a live reservation and restores stock once |
| `GET` | `/api/v1/inventory/{ticketItemId}` | Returns available, reserved, confirmed, initial, and convergence state |

Create requires `Idempotency-Key` and `X-Demo-Actor-Id` UUID strings. The JSON body contains a positive `ticketItemId` and `quantity` from 1 through 4. Error responses are the bounded record:

```json
{
  "code": "SOLD_OUT",
  "message": "No inventory is available for this request",
  "retryable": false,
  "traceId": "bounded-trace-id"
}
```

Errors never contain stack traces, SQL messages, raw idempotency keys, or unbounded user-controlled values. Validation is 400; missing resources are 404; sold-out, payload conflict, and late terminal transitions are 409; create rate-limit rejection is 429; dependency/bulkhead saturation is 503 with `Retry-After: 1`.

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
| `FAILED` | five bounded attempts exhausted | alert and manual/controlled remediation |

The journal lease is exclusive for the lease window. An expired lease can be reclaimed; `operationId` remains the idempotency token across retries.

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
