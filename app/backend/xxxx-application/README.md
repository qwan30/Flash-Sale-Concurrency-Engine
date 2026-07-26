# xxxx-application — Tầng Application (Application Layer)

> **Vai trò:** **Điều phối** các use-case — nhận yêu cầu từ Controller, phối hợp Domain Services, quản lý transaction, xử lý cache, và phát sự kiện.

---

## 📐 Vị trí trong kiến trúc DDD + Clean Architecture

```
                    ┌─────────────────────┐
                    │   xxxx-controller   │  ← Nhận HTTP request
                    └────────┬────────────┘
                             │ gọi
               ╔═════════════▼═════════════╗
               ║  ★ xxxx-application ★     ║  ← BẠN ĐANG Ở ĐÂY
               ╚═════════════╤═════════════╝
                             │ gọi
                    ┌────────▼────────────┐
                    │     xxxx-domain     │  ← Logic nghiệp vụ thuần túy
                    └─────────────────────┘
                             ▲
                    ┌────────┴────────────┐
                    │ xxxx-infrastructure  │  ← Implement Port
                    └─────────────────────┘
```

**Vai trò chính:** Application Layer là "bộ não điều phối" — nó **KHÔNG** chứa business rule, mà **phối hợp** các thành phần Domain để thực hiện một use-case hoàn chỉnh.

---

## 📁 Cấu trúc thư mục

```
application/
├── service/                    ← Application Services — Điều phối use-case
│   ├── order/                  ← Nhóm: Đặt hàng (core use-case)
│   │   ├── TicketOrderAppService.java         ← Interface chính cho tầng Controller
│   │   ├── OrderCreationService.java          ← ★ Luồng tạo đơn hàng chính
│   │   ├── OrderQueryService.java             ← Truy vấn đơn hàng
│   │   ├── OrderReconciliationService.java    ← Đối soát Redis ↔ MySQL
│   │   ├── ConsistencyCheckService.java       ← Kiểm tra tính nhất quán stock
│   │   ├── IdempotencyService.java            ← Chống đặt hàng trùng lặp
│   │   ├── BenchmarkFixtureService.java       ← Setup/reset data benchmark
│   │   ├── impl/                              ← Implement TicketOrderAppService
│   │   ├── strategy/                          ← Strategy Pattern: trừ stock
│   │   │   ├── StockDeductionStrategy.java            ← Interface chiến lược
│   │   │   ├── StockDeductionStrategyRegistry.java    ← Đăng ký + chọn chiến lược
│   │   │   ├── RedisLuaStockDeductionStrategy.java    ← Redis Lua script (nhanh)
│   │   │   ├── RedisLuaCompensatingStockDeductionStrategy.java  ← Redis + compensation
│   │   │   ├── ConditionalDbStockDeductionStrategy.java         ← DB có điều kiện
│   │   │   ├── UnsafeDbStockDeductionStrategy.java              ← DB không an toàn (demo)
│   │   │   └── StockDeductionResult.java              ← Kết quả trừ stock
│   │   ├── cache/                             ← Quản lý cache stock
│   │   │   └── StockOrderCacheService.java    ← Warmup + đọc stock từ Redis/DB
│   │   ├── support/                           ← Utility
│   │   │   └── OrderDateSupport.java          ← Tính yearMonth từ timestamp
│   │   └── model/                             ← (dự phòng)
│   │
│   ├── ticket/                 ← Nhóm: Truy vấn vé
│   │   ├── TicketDetailAppService.java        ← Interface: đọc chi tiết vé
│   │   ├── impl/                              ← Implement
│   │   └── cache/                             ← Cache chi tiết vé
│   │
│   ├── event/                  ← Nhóm: Sự kiện
│   │   ├── impl/                              ← Implement
│   │   └── cached/                            ← Cache event
│   │
│   ├── benchmark/              ← Nhóm: Benchmark
│   │   └── BenchmarkRunService.java           ← Lưu/đọc kết quả benchmark JMeter
│   │
│   ├── payment/                ← Nhóm: Thanh toán (dự phòng)
│   │   └── impl/
│   │
│   └── user/                   ← Nhóm: Người dùng (dự phòng)
│
├── MQ/                         ← Transactional Outbox Pattern
│   ├── OutboxService.java             ← ★ Ghi event vào DB + publish lên Kafka
│   ├── OutboxEvent.java               ← Entity: outbox row
│   ├── OutboxEnvelope.java            ← Kafka message envelope
│   ├── OutboxRepository.java          ← Spring Data JPA cho outbox
│   ├── OutboxPublishScheduler.java    ← Scheduler: quét + gửi pending events
│   └── OutboxStatus.java              ← Enum: PENDING, PUBLISHED, FAILED
│
├── port/                       ← Port — Interface cho infrastructure services
│   └── cache/
│       ├── CacheStore.java                    ← Interface: cache operations (get/set)
│       ├── ApplicationDistributedLock.java    ← Interface: distributed lock
│       └── DistributedLockService.java        ← Interface: lock service
│
├── model/                      ← DTO — Dữ liệu truyền giữa các tầng
│   ├── TicketDetailDTO.java           ← DTO chi tiết vé
│   ├── TicketOrderDTO.java            ← DTO đơn hàng
│   ├── benchmark/                     ← DTO kết quả benchmark
│   ├── cache/                         ← DTO cache
│   ├── order/                         ← DTO request/response tạo đơn
│   └── payment/                       ← DTO thanh toán (dự phòng)
│
├── mapper/                     ← Object Mapping
│   └── TicketDetailMapper.java        ← Chuyển đổi Entity ↔ DTO
│
├── cronjob/                    ← Cron Jobs / Startup Tasks
│
├── config/                     ← Configuration (dự phòng)
├── exception/                  ← Custom Exceptions (dự phòng)
└── scheduler/                  ← Scheduled Tasks (dự phòng)
```

---

## 🔄 Luồng dữ liệu chính

### 1. Luồng tạo đơn hàng (POST /orders)

```
Controller
   │
   │  createOrder(CreateOrderRequest)
   ▼
TicketOrderAppService (interface)
   │
   ▼
TicketOrderAppServiceImpl
   │
   ▼
OrderCreationService                     ← ★ TRUNG TÂM ĐIỀU PHỐI
   │
   ├── IdempotencyService                ← Check idempotency key
   │
   ├── StockDeductionStrategyRegistry    ← Chọn strategy trừ stock
   │   ├── RedisLuaStockDeductionStrategy
   │   ├── RedisLuaCompensatingStockDeductionStrategy
   │   ├── ConditionalDbStockDeductionStrategy
   │   └── UnsafeDbStockDeductionStrategy
   │
   ├── OrderDeductionDomainService       ← Tạo bảng tháng + insert order
   │
   ├── OutboxService.record()            ← Ghi event vào outbox (trong transaction)
   │
   └── StockOrderCacheService            ← Compensation nếu thất bại
```

### 2. Luồng publish event (Background Scheduler)

```
OutboxPublishScheduler (@Scheduled)
   │
   ▼
OutboxService.publishPendingEvents()
   │
   ├── Đọc pending events từ DB
   ├── Gửi lên Kafka topic
   ├── Mark PUBLISHED nếu thành công
   └── Mark FAILED + schedule retry nếu thất bại
```

### 3. Luồng đối soát (POST /admin/benchmarks/reconcile)

```
AdminBenchmarkController
   │
   ▼
OrderReconciliationService
   │
   ├── Đọc stock từ Redis
   ├── Đọc stock từ MySQL
   ├── So sánh → phát hiện drift
   └── OutboxService.record(ReconciliationEvent)
```

---

## 🧩 Giải thích các thành phần quan trọng

### Strategy Pattern — Trừ Stock

Hệ thống hỗ trợ **4 chiến lược** trừ stock, chọn qua `CreateOrderRequest.strategy`:

| Strategy | Mô tả | Độ an toàn |
|----------|--------|-----------|
| `REDIS_LUA` | Trừ stock bằng Lua script trên Redis | ⚡ Nhanh, atomic |
| `REDIS_LUA_COMPENSATING` | Redis Lua + rollback nếu DB fail | ⚡🛡️ Nhanh + an toàn |
| `CONDITIONAL_DB` | UPDATE ... WHERE stock >= quantity | 🛡️ An toàn (CAS) |
| `UNSAFE_DB` | UPDATE stock = stock - quantity (không check) | ⚠️ Oversell risk |

### Transactional Outbox

Đảm bảo **at-least-once delivery** cho events:

```
Transaction {
    1. INSERT đơn hàng vào ticket_order_yyyyMM
    2. INSERT event vào outbox table        ← cùng 1 transaction
}
// Sau khi commit:
Scheduler → đọc outbox → gửi Kafka → mark PUBLISHED
```

### Port (Interface cho Infrastructure)

| Port | Mô tả | Implemented bởi |
|------|--------|-----------------|
| `CacheStore` | get/set cache | `RedisCacheStoreAdapter` (Infrastructure) |
| `DistributedLockService` | Distributed lock | `RedisDistributedLockServiceAdapter` (Infrastructure) |
| `ApplicationDistributedLock` | Lock API đơn giản | `RedisDistributedLockServiceAdapter` (Infrastructure) |

---

## 📏 Nguyên tắc thiết kế

1. **Orchestration, not Implementation:** Application Service phối hợp các Domain Service, KHÔNG tự viết business logic.
2. **Transaction Boundary:** `@Transactional` được khai báo ở tầng này, bao trùm cả domain operations.
3. **Port/Adapter cho Infrastructure:** Sử dụng interface (`CacheStore`, `DistributedLockService`) để không phụ thuộc trực tiếp vào Redis/Hazelcast.
4. **Strategy Pattern:** Cho phép chuyển đổi chiến lược trừ stock mà không sửa code điều phối.

---

## 🔗 Phụ thuộc Maven

```
xxxx-application
     ├── xxxx-domain          ← Gọi Domain Service + Repository interface
     ├── spring-boot-starter  ← @Service, @Transactional, @Scheduled
     ├── spring-kafka         ← KafkaTemplate (Outbox → Kafka)
     ├── spring-data-jpa      ← OutboxRepository
     └── micrometer           ← Metrics (Timer, Counter, Gauge)
```
