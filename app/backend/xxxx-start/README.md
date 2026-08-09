# xxxx-start — Tầng Start (Bootstrap Layer)

> **Vai trò:** **Điểm khởi động** của toàn bộ ứng dụng — chứa `main()`, cấu hình Spring Boot, và ghép nối tất cả các tầng lại với nhau.

---

## 📐 Vị trí trong kiến trúc DDD + Clean Architecture

```
               ╔═══════════════════════════════════════════════╗
               ║              ★ xxxx-start ★                   ║
               ║  Khởi động Spring Boot + ghép nối mọi module  ║
               ╚══════════════════╤════════════════════════════╝
                                  │ depends on ALL modules
                    ┌─────────────┼─────────────────┐
                    │             │                  │
              ┌─────▼─────┐ ┌────▼─────┐  ┌────────▼──────────┐
              │ controller │ │ application│ │  infrastructure   │
              └────────────┘ └──────┬────┘  └──────────────────┘
                                    │
                              ┌─────▼─────┐
                              │  domain   │
                              └───────────┘
```

**Vai trò chính:** Start module là module DUY NHẤT có `main()`. Nó **không chứa business logic** mà chỉ:
1. Khởi động Spring Boot
2. Cấu hình cross-cutting concerns (OpenAPI, Observation, Logging)
3. Khai báo `application.yml` với tất cả connection strings

---

## 📁 Cấu trúc thư mục

```
xxxx-start/
├── src/main/java/com/xxxx/
│   ├── StartApplication.java          ← ★ Main class — @SpringBootApplication
│   └── config/
│       ├── OpenApiConfig.java         ← Swagger/OpenAPI configuration
│       └── ObservationConfig.java     ← Micrometer Observation + Tracing
│
├── src/main/resources/
│   ├── application.yml                ← ★ Cấu hình tổng (DB, Redis, Kafka, ...)
│   └── logback-spring.xml             ← Cấu hình logging
│
└── src/test/                          ← Integration tests (chạy toàn bộ hệ thống)
```

---

## 🧩 Giải thích từng thành phần

### `StartApplication.java` — Main Class

```java
@SpringBootApplication
@EnableScheduling
public class StartApplication {
    public static void main(String[] args) {
        SpringApplication.run(StartApplication.class, args);
    }

    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }
}
```

| Annotation | Mục đích |
|------------|----------|
| `@SpringBootApplication` | Auto-configuration + Component Scan toàn bộ `com.xxxx.*` |
| `@EnableScheduling` | Bật cron jobs (`OutboxPublishScheduler`, `OrderReconciliationService`) |

### `OpenApiConfig.java` — Swagger UI

Cấu hình Swagger UI để tự động generate API documentation:

| URL | Mô tả |
|-----|--------|
| `http://localhost:1122/swagger-ui.html` | Swagger UI (interactive) |
| `http://localhost:1122/v3/api-docs` | OpenAPI JSON (full) |
| `http://localhost:1122/v3/api-docs/lab-api` | OpenAPI JSON (lab group only) |

### `ObservationConfig.java` — Distributed Tracing

Bật `@Observed` aspect để:
- Tự động tạo **span** cho các method được annotate `@Observed`
- Truyền **traceId/spanId** qua MDC → log correlation
- Gửi traces đến collector (nếu cấu hình)

### `application.yml` — Cấu hình tổng

```yaml
# Server
server.port: 1122                    # HTTP port
server.tomcat.threads.max: 500       # Max concurrent requests
server.tomcat.accept-count: 20000    # TCP backlog

# Database (MySQL)
spring.datasource.url: jdbc:mysql://localhost:3316/vetautet
spring.datasource.hikari.maximum-pool-size: 10

# Redis
spring.data.redis.host: 127.0.0.1
spring.data.redis.port: 6319

# Kafka
spring.kafka.bootstrap-servers: localhost:9094
app.kafka.topic: flashsale.orders

# Outbox
app.outbox.publish-batch-size: 50
app.outbox.retry-delay: 10s
app.outbox.max-attempts: 5

# Redisson (Distributed Lock)
app.redisson.mode: single
app.redisson.single-address: redis://127.0.0.1:6319

# Resilience4j
resilience4j.ratelimiter.instances.orderApi.*

# Observability
management.endpoints.web.exposure.include: health, prometheus, metrics
management.tracing.sampling.probability: 1.0
```

**Environment Variables** (có thể override):

| Variable | Default | Mô tả |
|----------|---------|--------|
| `MYSQL_URL` | `jdbc:mysql://localhost:3316/vetautet` | MySQL connection |
| `MYSQL_USER` / `MYSQL_PASSWORD` | `root` / `root1234` | MySQL credentials |
| `REDIS_HOST` / `REDIS_PORT` | `127.0.0.1` / `6319` | Redis connection |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9094` | Kafka brokers |
| `KAFKA_TOPIC` | `flashsale.orders` | Kafka topic |
| `REDISSON_MODE` | `single` | Redisson mode (single/sentinel) |
| `SPRING_FLYWAY_BASELINE_ON_MIGRATE` | `false` | Set `true` when starting against the legacy init schema; once `flyway_schema_history` exists, the override is no longer needed |

---

## 🔄 Thứ tự khởi động

Khi `StartApplication.main()` chạy:

```
1. Spring Boot auto-configuration
   ├── DataSource (HikariCP → MySQL)
   ├── JPA (Hibernate, ddl-auto: none)
   ├── Redis (Lettuce connection pool)
   ├── Kafka (Producer configuration)
   └── Redisson (Distributed lock client)

2. Component Scan (@SpringBootApplication)
   ├── @RestController  → TicketOrderController, AdminBenchmarkController, ...
   ├── @Service          → OrderCreationService, OutboxService, ...
   │   └── Domain Service Impl → inject Repository Impl (Infrastructure)
   
3. @PostConstruct (Warmup)
          └── Load stock từ MySQL vào Redis cache

4. @EnableScheduling (Cron Jobs)
   └── OutboxPublishScheduler
       └── Định kỳ quét outbox table → gửi Kafka

5. Ready to serve HTTP requests on port 1122
```

---

## 🧪 Integration Tests

Integration tests nằm trong `xxxx-start/src/test/` vì cần boot toàn bộ Spring context:

```bash
# Chạy integration tests (cần Docker: MySQL + Redis + Kafka)
mvn -pl app/backend/xxxx-start test -Dflashsale.integration=true
```

---

## 🚀 Cách chạy ứng dụng

### Với Docker Compose (recommended):

```bash
# Khởi động infrastructure (MySQL, Redis, Kafka)
docker compose up -d

# Chạy ứng dụng
$env:SPRING_FLYWAY_BASELINE_ON_MIGRATE="true"
mvn -pl app/backend/xxxx-start spring-boot:run
```

### Với IDE:

1. Đặt biến môi trường `SPRING_FLYWAY_BASELINE_ON_MIGRATE=true` khi database vẫn dùng legacy init schema.
2. Chạy `StartApplication.main()` từ IDE
3. Đảm bảo MySQL, Redis, Kafka đang chạy
4. Truy cập: `http://localhost:1122/swagger-ui.html`

---

## 📏 Nguyên tắc thiết kế

1. **Composition Root:** Start module là nơi DUY NHẤT ghép nối tất cả dependency. Các module khác không biết về nhau thông qua concrete class.
2. **Configuration Externalization:** Tất cả connection strings đều support environment variables → dễ deploy.
3. **No Business Logic:** Start module KHÔNG chứa business logic, chỉ chứa bootstrap code.
4. **Cross-cutting Concerns:** OpenAPI, Observation, Logging được cấu hình tập trung ở đây.

---

## 🔗 Phụ thuộc Maven

```
xxxx-start
     ├── xxxx-controller          ← REST endpoints
     ├── xxxx-application         ← Application services
     ├── xxxx-domain              ← Domain model + services
     ├── xxxx-infrastructure      ← Repository/Cache implementations
     ├── spring-boot-starter-web       ← Embedded Tomcat
     ├── spring-boot-starter-actuator  ← /health, /prometheus, /metrics
     ├── springdoc-openapi             ← Swagger UI
     ├── micrometer-tracing            ← Distributed tracing
     └── resilience4j                  ← Circuit breaker, Rate limiter
```
