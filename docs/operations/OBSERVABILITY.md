# Observability: Log → Metric → Tracing

This document describes the end-to-end observability flow in the Flash-Sale Concurrency Engine. It covers the three-pillar architecture (logging, metrics, tracing), how they interconnect, and how to verify each pillar works.

## Architecture Overview

```
HTTP Request → Controller (@Observed) → Application Service (@Observed) → Domain → Infrastructure
                    │                           │                            │
                    ▼                           ▼                            ▼
              ┌──────────┐              ┌──────────────┐            ┌─────────────────┐
              │  TRACE   │◄─────────────│  TRACE ID    │────────────│  TRACE ID       │
              │  Span    │   propagates │  in MDC      │   flows to │  in Kafka msg   │
              └────┬─────┘              └──────┬───────┘            └─────────────────┘
                   │                           │
                   ▼                           ▼
              ┌──────────┐              ┌──────────────┐
              │ METRICS  │              │    LOGS      │
              │ Timer    │              │ traceId=abc  │
              │ Counter  │              │ level=INFO   │
              └──────────┘              └──────────────┘
```

All three pillars are linked by the **traceId**: every log line carries it, every metric can be correlated to a trace via exemplars, and every span produces structured logs via Micrometer Observation.

## Pillar 1: Logging

### Configuration

| File | Purpose |
|------|---------|
| `app/backend/xxxx-start/src/main/resources/logback-spring.xml` | Production log format: pipe-delimited with traceId, JSON to Logstash |
| `app/backend/xxxx-start/src/test/resources/logback-test.xml` | Test log format: pipe-delimited with traceId, console only |
| `environment/elk/pipeline/logstash.conf` | Logstash pipeline: TCP JSON → Elasticsearch daily index |

### Log Format

```
yyyy-MM-dd HH:mm:ss:SSS | thread | traceId | level | logger | message
```

Example:
```
2026-06-09 14:30:01:234 | http-nio-1122-exec-1 | 64f2a8b3c9d1e0f2 | INFO | c.x.d.a.s.o.OrderCreationService | Order created OKX-SGN-42-1777280000000
```

The `traceId` field is automatically populated by Micrometer Tracing (Brave) via SLF4J MDC. Every HTTP request receives a unique traceId before any application code runs.

### ELK Stack (optional)

Start with the `elk` profile:

```bash
docker compose -f environment/docker-compose-dev.yml --profile elk up -d
```

- **Logstash**: TCP listener on `localhost:5044`
- **Elasticsearch**: `http://localhost:9200`
- **Kibana**: `http://localhost:5601`

## Pillar 2: Metrics

### Configuration

| File | Purpose |
|------|---------|
| `app/backend/xxxx-start/pom.xml` | `micrometer-registry-prometheus` (runtime) |
| `app/backend/xxxx-start/src/main/resources/application.yml` | Actuator exposes `/actuator/prometheus`, percentile histograms enabled |
| `environment/prometheus/prometheus.yml` | Prometheus scrape config for Spring Boot, MySQL, Redis, Node, Logstash exporters |
| `environment/docker-compose-dev.yml` | Prometheus, Grafana, and exporters behind `observability` profile |

### Available Metrics

| Metric namespace | Source | Description |
|-----------------|--------|-------------|
| `flashsale.orders` | `OrderCreationService` | Counter: order attempts by strategy and result (success/failed) |
| `flashsale.orders.latency` | `OrderCreationService` | Timer: order creation latency by strategy and result, with percentile histogram |
| `flashsale.compensation` | `OrderCreationService` | Counter: compensation events (attempted/double_fault) |
| `flashsale.reconciliation` | `OrderReconciliationService` | Counter: reconciliation repairs by direction (redis_over/redis_under) |
| `jvm_memory_*` | JVM (auto) | Heap/non-heap memory usage |
| `jvm_gc_*` | JVM (auto) | Garbage collection pause times and counts |
| `http_server_requests_*` | Spring Web (auto) | HTTP request count, latency, status codes |

### Verification

```bash
# Check metrics endpoint
curl -s http://localhost:1122/actuator/prometheus | grep flashsale

# Check JVM metrics are present
curl -s http://localhost:1122/actuator/prometheus | grep jvm_memory
```

### Prometheus + Grafana (optional)

Start with the `observability` profile:

```bash
docker compose -f environment/docker-compose-dev.yml --profile observability up -d
```

- **Prometheus**: `http://localhost:9090`, scrapes Spring Boot at `/actuator/prometheus`
- **Grafana**: `http://localhost:3000` (admin/admin), pre-configured with Prometheus datasource via UI
- **Node Exporter**: `http://localhost:9100/metrics`
- **MySQL Exporter**: `http://localhost:9104/metrics`
- **Redis Exporter**: `http://localhost:9121/metrics`
- **Logstash Exporter**: `http://localhost:9304/metrics`

## Pillar 3: Tracing

### Configuration

| File | Purpose |
|------|---------|
| `app/backend/xxxx-start/pom.xml` | `micrometer-tracing-bridge-brave` (auto-managed version) |
| `app/backend/xxxx-start/src/main/resources/application.yml` | `management.tracing.sampling.probability: 1.0` (always trace in lab) |
| `app/backend/xxxx-start/src/main/java/com/xxxx/config/ObservationConfig.java` | Registers `ObservedAspect` for `@Observed` annotation support |

### How It Works

1. **Micrometer Tracing + Brave** auto-configures on startup (Spring Boot 3.3.x).
2. Every inbound HTTP request creates a **root span** with a unique traceId.
3. The traceId is automatically injected into SLF4J **MDC** → every log line carries it.
4. **`@Observed`** annotations on service methods create child spans within the same trace.
5. **Kafka producers** auto-propagate `b3` trace headers in outbox messages.
6. **HTTP clients** (RestTemplate) auto-propagate `b3` headers on outbound calls.
7. Percentile histograms on key timers enable **metric-to-trace correlation** via Prometheus exemplars.

### Annotated Service Methods

| Method | Span name | File |
|--------|-----------|------|
| `TicketOrderAppServiceImpl.createOrder()` | `order.create` | `xxxx-application/.../impl/TicketOrderAppServiceImpl.java` |
| `TicketOrderAppServiceImpl.warmupStock()` | `stock.warmup` | (same file) |
| `TicketOrderAppServiceImpl.resetBenchmark()` | `benchmark.reset` | (same file) |
| `TicketOrderAppServiceImpl.getConsistency()` | `consistency.check` | (same file) |
| `OrderCreationService.createOrder()` | `order.creation` | `xxxx-application/.../order/OrderCreationService.java` |
| `OrderReconciliationService.reconcile()` | `reconciliation.run` | `xxxx-application/.../order/OrderReconciliationService.java` |

### Verification

```bash
# Make a traced request and check the response headers
curl -v http://localhost:1122/actuator/health 2>&1 | grep -i b3

# Check that logs contain real traceId values (not empty)
# After making a few requests, check the log file:
tail -5 ~/MyEventApplication/logs/application.log
# The 3rd pipe-delimited field should contain a 16-char hex traceId, not whitespace
```

## Log → Metric → Tracing Flow

The three pillars are interconnected:

```
1. REQUEST arrives
   → Brave creates Span(traceId=abc123)
   → MDC.put("traceId", "abc123")

2. SERVICE METHOD executes (@Observed)
   → Child span created under root trace
   → Timer.Sample records latency with traceId exemplar
   → Counter increments with traceId tag
   → log.info("Order created ...") → "traceId=abc123" in log line

3. RESPONSE returns
   → b3 trace headers in HTTP response
   → Prometheus scrapes /actuator/prometheus (metrics with exemplars)
   → Logstash ships JSON logs to Elasticsearch (with traceId)
```

To trace a single request end-to-end:
1. Get the traceId from a log line or HTTP response header (`X-B3-TraceId`)
2. Search Kibana for `mv_traceId: "abc123"` to find all logs for that request
3. Cross-reference metric counters at `/actuator/prometheus` for the same time window
4. If Zipkin/Jaeger were added, search by traceId to see the full span tree

## CI/CD Validation

The GitHub Actions pipeline (`.github/workflows/ci.yml`) includes:

| Job | What it validates |
|-----|-------------------|
| `backend-unit-test` | All unit tests pass with tracing/metrics on classpath |
| `backend-integration-test` | Integration tests with Testcontainers (MySQL, Redis, Kafka) |
| `backend-build` | JAR packaging succeeds |
| `frontend-checks` | TypeScript typecheck, ESLint, Next.js build |
| `observability-smoke` | Backend starts, `/actuator/prometheus` returns JVM metrics, log format includes traceId |

## Key Dependencies

| Artifact | Version | Purpose |
|----------|---------|---------|
| `micrometer-registry-prometheus` | 1.13.6 | Expose `/actuator/prometheus` |
| `micrometer-tracing-bridge-brave` | (managed by Spring Boot 3.3.5) | Auto-tracing via Brave |
| `spring-boot-starter-actuator` | 3.3.5 | Health, metrics, tracing endpoints |
| `logstash-logback-encoder` | 8.0 | JSON log output to Logstash |

## Local Verification Checklist

Before pushing, verify the observability stack works:

```bash
# 1. Start infrastructure
docker compose -f environment/docker-compose-dev.yml up -d mysql redis kafka

# 2. Package and start backend
mvn -pl app/backend/xxxx-start -am -DskipTests package
java -jar app/backend/xxxx-start/target/xxxx-start-1.0-SNAPSHOT.jar &

# 3. Verify logging
curl -s http://localhost:1122/actuator/health
sleep 1
tail -3 ~/MyEventApplication/logs/application.log
# → Should show pipe-delimited format with traceId in 3rd field

# 4. Verify metrics
curl -s http://localhost:1122/actuator/prometheus | grep -E "flashsale|jvm_memory"
# → Should return counters and gauges

# 5. Verify tracing
curl -sv http://localhost:1122/orders -X POST \
  -H "Content-Type: application/json" \
  -d '{"ticketItemId":4,"userId":1,"quantity":1,"strategy":"CONDITIONAL_DB","idempotencyKey":"obs-test"}' \
  2>&1 | grep -i b3
# → Should show b3 trace headers in response

# 6. Optional: Start ELK + Prometheus
docker compose -f environment/docker-compose-dev.yml --profile elk --profile observability up -d
```
