# Resilience Patterns

> Derived from `project-foundation.md` §10. Timeout, retry, circuit breaker, rate limiter, and distributed lock patterns.

## 1. Resilience4j Rate Limiter

```yaml
resilience4j.ratelimiter.instances:
  backendA: { limitForPeriod: 2, limitRefreshPeriod: 10s, timeoutDuration: 0 }
  backendB: { limitForPeriod: 5, limitRefreshPeriod: 10s, timeoutDuration: 3s }
```

Used on `HiController` for local experiments. Pattern: `@RateLimiter(name = "...", fallbackMethod = "...")`.

## 2. Resilience4j Circuit Breaker

```yaml
resilience4j.circuitbreaker.instances.checkRandom:
  slidingWindowSize: 10
  permittedNumberOfCallsInHalfOpenState: 3
  minimumNumberOfCalls: 5
  waitDurationInOpenState: 5s
  failureRateThreshold: 50
```

Used on `HiController.circuitBreaker()`. Pattern: `@CircuitBreaker(name = "...", fallbackMethod = "...")`.

## 3. Distributed Lock (Redisson)

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

## 4. Transactional Outbox Retry

| Parameter | Default | Meaning |
|---|---|---|
| `app.outbox.publish-batch-size` | 50 | Events per batch |
| `app.outbox.retry-delay` | 10s | Delay before retry |
| `app.outbox.max-attempts` | 5 | Max attempts |

**Happy**: `record()` → `publishPendingEvents()` → Kafka → `markPublished()`
**Retry**: `markFailed()` → `retryFailedEvents()` after 10s → reset PENDING → retry → after 5 attempts → stays FAILED

## 5. Retry Decision Matrix

| Failure | Retry? | Strategy |
|---|---|---|
| Kafka connection refused | ✅ Yes | Outbox retry with backoff |
| Kafka broker unavailable | ✅ Yes | Outbox retry with backoff |
| DB deadlock (transient) | ✅ Yes | Spring retry or outbox |
| Validation error | ❌ No | Return error immediately |
| Business rule violation | ❌ No | Return domain result code |
| Idempotency duplicate | ❌ No | Return cached response |

## 6. Thread & Connection Pools

| Pool | Max | Min Idle |
|---|---|---|
| Tomcat threads | 500 | 50 (min-spare), accept-count 20000 |
| HikariCP (DB) | 10 | 5 |
| Lettuce (Redis) | 10 active, 5 idle | 5 |
| Virtual threads | Enabled | — |

## 7. Reconciliation as Resilience

`OrderReconciliationService`: every 30s (10s initial delay), default ticket `4`. Sets Redis to DB truth on drift. Emits `RECONCILIATION` event via outbox → Kafka. Manual: `POST /admin/benchmarks/reconcile`.

Safety net for rare double-fault where both Redis compensation and DB write fail.
