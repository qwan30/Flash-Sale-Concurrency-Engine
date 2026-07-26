# Resilience Patterns

> Derived from `project-foundation.md` §10. Rate limiter, distributed lock, outbox retry, and reconciliation patterns.

## 1. Resilience4j Rate Limiter

```yaml
resilience4j.ratelimiter.instances:
  orderApi:
    limitForPeriod: ${ORDER_API_RATE_LIMIT:20000}
    limitRefreshPeriod: 1s
    timeoutDuration: 0
```

Applied to `TicketOrderController.createOrder` (`POST /orders`) via
`@RateLimiter(name = "orderApi", fallbackMethod = "createOrderRateLimited")`.

The limiter is the outermost admission control: it sheds load before a request can reach stock
deduction, so a burst beyond the ceiling is rejected rather than queuing on Redis or MySQL. The
fallback returns **HTTP 429**, which keeps shed load distinguishable from a sell-out rejection
(409) and a malformed request (400).

The default ceiling of 20,000 req/s sits far above the highest throughput measured in the
benchmark matrix (443 req/s), so load tests measure the deduction strategies rather than the
limiter. Lower `ORDER_API_RATE_LIMIT` to demonstrate shedding behavior.

## 2. Distributed Lock (Redisson)

| Mode | Config |
|---|---|
| Single | `redis://127.0.0.1:6319` |
| Sentinel | Master `mymaster`, nodes at `26379-26381` |

Connection pool: 50 max, 10 min idle. Database 0.

```java
public interface DistributedLockService {
    <T> T lock(String key, long waitTime, long leaseTime, TimeUnit unit, Supplier<T> criticalSection);
}
```

## 3. Transactional Outbox Retry

| Parameter | Default | Meaning |
|---|---|---|
| `app.outbox.publish-batch-size` | 50 | Events per batch |
| `app.outbox.retry-delay` | 10s | Delay before retry |
| `app.outbox.max-attempts` | 5 | Max attempts |

**Happy**: `record()` → `publishPendingEvents()` → Kafka → `markPublished()`
**Retry**: `markFailed()` → `retryFailedEvents()` after 10s → reset PENDING → retry → after 5 attempts → stays FAILED

## 4. Retry Decision Matrix

| Failure | Retry? | Strategy |
|---|---|---|
| Kafka connection refused | ✅ Yes | Outbox retry with backoff |
| Kafka broker unavailable | ✅ Yes | Outbox retry with backoff |
| DB deadlock (transient) | ✅ Yes | Spring retry or outbox |
| Validation error | ❌ No | Return error immediately |
| Business rule violation | ❌ No | Return domain result code |
| Idempotency duplicate | ❌ No | Return cached response |

## 5. Thread & Connection Pools

| Pool | Max | Min Idle |
|---|---|---|
| Tomcat threads | 500 | 50 (min-spare), accept-count 20000 |
| HikariCP (DB) | 10 | 5 |
| Lettuce (Redis) | 10 active, 5 idle | 5 |
| Virtual threads | Enabled | — |

## 6. Reconciliation as Resilience

`OrderReconciliationService`: every 30s (10s initial delay), default ticket `4`. Sets Redis to DB truth on drift. Emits `RECONCILIATION` event via outbox → Kafka. Manual: `POST /admin/benchmarks/reconcile`.

Safety net for rare double-fault where both Redis compensation and DB write fail.
