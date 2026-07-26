# xxxx-infrastructure — Tầng Infrastructure (Infrastructure Layer)

> **Vai trò:** Triển khai cụ thể các **Port/Interface** từ tầng Domain và Application — kết nối với **MySQL**, **Redis**, **Redisson** (distributed lock), **Kafka**.

---

## 📐 Vị trí trong kiến trúc DDD + Clean Architecture

```
                    ┌─────────────────────┐
                    │   xxxx-controller   │  ← Nhận HTTP request
                    └────────┬────────────┘
                             │
                    ┌────────▼────────────┐
                    │  xxxx-application   │  ← Điều phối use-case
                    └────────┬────────────┘
                             │
                    ┌────────▼────────────┐
                    │     xxxx-domain     │  ← Logic nghiệp vụ (khai báo interface)
                    └────────┬────────────┘
                             │ implements
               ╔═════════════▼═════════════╗
               ║ ★ xxxx-infrastructure ★   ║  ← BẠN ĐANG Ở ĐÂY
               ╚═══════════════════════════╝
```

**Vai trò chính:** Infrastructure là tầng **Adapter** — nó implement các interface (Port) mà Domain và Application khai báo, sử dụng công nghệ cụ thể như MySQL (JPA/Native SQL), Redis, Redisson.

---

## 📁 Cấu trúc thư mục

```
infrastructure/
├── persistence/                ← Triển khai Repository (MySQL)
│   ├── repository/             ← Implement Domain Repository interfaces
│   │   ├── OrderDeductionInfrasRepositoryImpl.java  ← ★ Native SQL cho bảng tháng
│   │   ├── TickerOrderRepositoryImpl.java           ← JPA: trừ stock (CAS, Level)
│   │   ├── TicketDetailInfrasRepositoryImpl.java    ← JPA: đọc chi tiết vé
│   │   └── HiInfrasRepositoryImpl.java              ← Demo/health-check
│   │
│   ├── mapper/                 ← JPA Repository (Spring Data)
│   │   ├── TicketOrderJPAMapper.java    ← JpaRepository + @Query cho stock
│   │   └── TicketDetailJPAMapper.java   ← JpaRepository cho ticket_detail
│   │
│   └── model/                  ← JPA Entities (dự phòng)
│
├── cache/                      ← Triển khai Cache (Redis)
│   ├── redis/                  ← Redis implementation
│   │   ├── RedisInfrasService.java          ← Interface cache operations
│   │   ├── RedisInfrasServiceImpl.java      ← ★ Implement: Lua script, get/set stock
│   │   └── RedisCacheStoreAdapter.java      ← Adapter: implement CacheStore port
│   │
│   └── local/                  ← Local cache (Caffeine — dự phòng)
│
├── distributed/                ← Distributed Lock
│   ├── redisson/               ← Redisson implementation
│   │   ├── RedisDistributedService.java              ← Interface lock
│   │   ├── RedisDistributedLocker.java               ← Interface lock helper
│   │   ├── RedisDistributedLockServiceAdapter.java   ← ★ Adapter: implement port
│   │   ├── config/                                   ← Redisson configuration
│   │   └── impl/                                     ← Redisson implementation
│   │
│   ├── caffeine/               ← (dự phòng)
│   └── hazelcast/              ← (dự phòng)
│
└── config/                     ← Infrastructure Configuration
    └── RedisConfig.java        ← RedisTemplate bean + serializer config
```

---

## 🔄 Luồng dữ liệu — Infrastructure implement Port

### 1. Repository Pattern (Domain Port → Infrastructure Adapter)

```
Domain Interface (Port)                    Infrastructure Implementation (Adapter)
─────────────────────                      ──────────────────────────────────────
OrderDeductionRepository         ──→       OrderDeductionInfrasRepositoryImpl
  .insertOrder()                              └── EntityManager.createNativeQuery()
  .ensureMonthlyOrderTable()                  └── CREATE TABLE IF NOT EXISTS
  .findAllByUser()                            └── SELECT * FROM ticket_order_yyyyMM

TickerOrderRepository            ──→       TickerOrderRepositoryImpl
  .decreaseStockLevel1()                      └── TicketOrderJPAMapper.updateStock()
  .decreaseStockLevel3CAS()                   └── TicketOrderJPAMapper.updateStockCAS()

TicketDetailRepository           ──→       TicketDetailInfrasRepositoryImpl
  .getTicketDetailById()                      └── TicketDetailJPAMapper.findById()
```

### 2. Cache Pattern (Application Port → Infrastructure Adapter)

```
Application Interface (Port)               Infrastructure Implementation (Adapter)
────────────────────────                    ──────────────────────────────────────
CacheStore                       ──→       RedisCacheStoreAdapter
  .get(key)                                   └── RedisTemplate.opsForValue().get()
  .set(key, value, ttl)                       └── RedisTemplate.opsForValue().set()
  .delete(key)                                └── RedisTemplate.delete()
```

### 3. Distributed Lock Pattern

```
Application Interface (Port)               Infrastructure Implementation (Adapter)
────────────────────────                    ──────────────────────────────────────
DistributedLockService           ──→       RedisDistributedLockServiceAdapter
  .tryLock(key, timeout)                      └── RedissonClient.getLock().tryLock()
  .unlock(key)                                └── RedissonClient.getLock().unlock()
```

---

## 🧩 Giải thích các thành phần quan trọng

### `OrderDeductionInfrasRepositoryImpl` — Native SQL Repository

Đây là thành phần phức tạp nhất, sử dụng **Native SQL** thay vì JPA vì:
- Bảng order được **chia theo tháng** (`ticket_order_202506`, `ticket_order_202507`)
- JPA không hỗ trợ dynamic table name → phải dùng `EntityManager.createNativeQuery()`

```java
// Tạo bảng tháng nếu chưa tồn tại
public void ensureMonthlyOrderTable(String yearMonth) {
    String tableName = getTableName(yearMonth); // "ticket_order_202506"
    entityManager.createNativeQuery("CREATE TABLE IF NOT EXISTS " + tableName + " (...)");
}

// Insert order vào bảng tháng
public void insertOrder(String yearMonth, TickerOrder order) {
    String tableName = getTableName(yearMonth);
    entityManager.createNativeQuery("INSERT INTO " + tableName + " (...) VALUES (...)");
}
```

> **Bảo vệ SQL Injection:** `yearMonth` được validate bằng regex `\d{6}` trước khi ghép vào tên bảng.

### `TicketOrderJPAMapper` — Spring Data JPA

Sử dụng `@Query` cho các thao tác stock:

| Method | Query | Mô tả |
|--------|-------|--------|
| `updateStock()` | `UPDATE ticket_detail SET stock = stock - ?` | Trừ stock (không an toàn) |
| `updateStockCAS()` | `UPDATE ... WHERE stock >= ? AND version = ?` | CAS — Optimistic Locking |

### `RedisInfrasServiceImpl` — Redis Operations

Cung cấp các thao tác Redis cho stock management:

| Method | Mô tả |
|--------|--------|
| `addStockToCache(ticketId, stock)` | Warmup: load stock từ DB vào Redis |
| `decrementStock(ticketId, qty)` | Lua script: atomic decrement |
| `getStock(ticketId)` | Đọc stock hiện tại |
| `incrementStock(ticketId, qty)` | Compensation: rollback stock |

### `RedisCacheStoreAdapter` — Adapter Pattern

Implement `CacheStore` port từ Application layer, bridge giữa Application và Redis:

```
Application Code
    │ gọi CacheStore.get("stock:4")
    ▼
RedisCacheStoreAdapter (Infrastructure)
    │ delegate
    ▼
RedisTemplate.opsForValue().get("stock:4")
    │
    ▼
Redis Server
```

---

## 📏 Nguyên tắc thiết kế

1. **Adapter Pattern:** Mỗi implementation là một Adapter cho Port từ Domain/Application.
2. **Dependency Inversion:** Infrastructure phụ thuộc vào Domain (implements interface), KHÔNG phải ngược lại.
3. **Technology-specific:** Đây là tầng DUY NHẤT biết về MySQL, Redis, Redisson, Kafka.
4. **Swappable:** Có thể thay Redis bằng Hazelcast hoặc Caffeine bằng cách tạo Adapter mới mà không sửa Domain/Application.

---

## 🔗 Phụ thuộc Maven

```
xxxx-infrastructure
     ├── xxxx-domain              ← Implement Repository interfaces
     ├── xxxx-application         ← Implement Port interfaces (CacheStore, Lock)
     ├── spring-boot-starter-data-jpa   ← JPA, EntityManager
     ├── spring-boot-starter-data-redis ← RedisTemplate
     ├── redisson-spring-boot-starter   ← Distributed Lock
     └── mysql-connector-j             ← MySQL driver
```
