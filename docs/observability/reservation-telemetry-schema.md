# Reservation telemetry schema

This schema is the contract for the reservation reliability signals. Micrometer
names use dots in Java; the Prometheus exporter converts them to underscores
and adds the conventional suffix for timers, time gauges, and counters.

## Metrics

| Micrometer instrument | Prometheus series and unit | Bounded labels | Emission point | Portfolio interpretation |
|---|---|---|---|---|
| `flashsale.reservation.operation` (Timer) | `flashsale_reservation_operation_seconds` (seconds) | `operation`, `outcome` | Once per create, confirm, release, expire, recovery, or outbox-publish result | Request and relay latency by bounded lifecycle outcome |
| `flashsale.reservation.transitions` (Counter) | `flashsale_reservation_transitions_total` (count) | `operation`, `outcome`, `reason` | Alongside each reservation/recovery/outbox telemetry record | Durable state-transition volume and failure mix |
| `flashsale.admission.rejections` (Counter) | `flashsale_admission_rejections_total` (count) | `operation`, `reason` | Reservation admission controller when a rate limiter or bulkhead rejects work | Overload shedding by lane and bounded reason |
| `flashsale.reservation.units` (Gauge) | `flashsale_reservation_units` (units) | `status=available\|reserved\|confirmed` | Read from MySQL stock/reservation tables at scrape time | Current durable stock buckets |
| `flashsale.recovery.operations` (Gauge) | `flashsale_recovery_operations` (operations) | `state` | Count journal rows by bounded recovery state at scrape time | Recovery backlog and unresolved crash windows |
| `flashsale.outbox.oldest.age` (TimeGauge) | `flashsale_outbox_oldest_age_seconds` (seconds) | none | Read oldest pending/failed outbox row at scrape time | Publication lag and relay health |
| `flashsale.inventory.drift.units` (Gauge) | `flashsale_inventory_drift_units` (units) | none | Compare each durable MySQL stock account with its Redis mirror at scrape time | Absolute cross-store stock drift |
| `flashsale.redis.mirror.pending` (Gauge) | `flashsale_redis_mirror_pending` (operations) | none | Count `MIRROR_PENDING` journal rows at scrape time | Terminal Redis mirror convergence backlog |
| `flashsale.telemetry.read.failure` (Counter) | `flashsale_telemetry_read_failure_total` (count) | none | Increment when a database or Redis-backed gauge cannot be read | Telemetry dependency health; never a business success signal |

All operation, outcome, and reason values are allowlisted. Unknown values map
to the single bounded value `OTHER`; raw ticket IDs, actor IDs, operation IDs,
idempotency keys, SQL text, and exception messages are never labels. The
allowed operation values are `create`, `confirm`, `release`, `expire`,
`recover`, and `outbox.publish`. The allowed outcomes/reasons are the finite
reservation and relay dispositions implemented by the adapter, including
`NEW`, `REPLAYED`, `PROCESSING`, `SOLD_OUT`, `FENCE_STALE`, `REJECTED`,
`CONFLICT`, `CONFIRMED`, `RELEASED`, `EXPIRED`, `LATE_CONFLICT`,
`MIRROR_PENDING`, `REPAIR_REQUIRED`, `RECOVERED`, `PUBLISHED`, `IDLE`,
`FAILED`, `PARTIAL`, `RECEIVED`, `REDIS_APPLYING`, `REDIS_APPLIED`,
`COMMITTED`, `COMPENSATED`, `COMPENSATION_PENDING`, `DATABASE_FAILURE`,
`UNHANDLED`, and `MAX_ATTEMPTS_EXCEEDED`.

Gauge labels are fixed to `status = available|reserved|confirmed` and the
finite journal states `RECEIVED`, `REDIS_APPLYING`, `REDIS_APPLIED`,
`COMPENSATION_PENDING`, `MIRROR_PENDING`, `REPAIR_REQUIRED`, `COMMITTED`,
`COMPENSATED`, and `REJECTED`.

If a backing database or Redis read fails, the adapter returns `NaN` and
increments `flashsale.telemetry.read.failure`. `NaN` makes the value
unavailable to a scrape without presenting an unsafe zero; release decisions
must separately verify dependency health and convergence evidence.

## Fixed spans

The `@Observed` aspect emits these exact span names. Application code does not
attach unbounded request values as span attributes; trace correlation remains
available through the configured tracing bridge and MDC.

| Span name | Unit | Bounded attributes | Emission point | Portfolio interpretation |
|---|---|---|---|---|
| `flashsale.reservation.create` | request duration | none application-defined | `CreateReservationService.create` | Reservation admission and idempotent create latency |
| `flashsale.reservation.confirm` | request duration | none application-defined | `ConfirmReservationService.confirm` | Terminal confirmation latency and conflicts |
| `flashsale.reservation.release` | request duration | none application-defined | `ReleaseReservationService.release` | Release/mirror convergence latency |
| `flashsale.reservation.expire` | request duration | none application-defined | `ExpireReservationService.expire` | Expiry sweep transition latency |
| `flashsale.reservation.recover` | recovery duration | none application-defined | `ReservationRecoveryService.recover` | Crash-window repair latency |
| `flashsale.outbox.publish` | batch relay duration | none application-defined | `OutboxService.publishPendingEvents` | Outbox claim/publication latency |

The operation timer and transition counter carry the bounded lifecycle outcome
information; spans provide trace-level timing and causality. Gauge values are
read-only operational views and must not be used alone as release evidence.
