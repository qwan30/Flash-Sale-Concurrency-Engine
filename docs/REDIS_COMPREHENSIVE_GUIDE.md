# Redis Toàn Diện: Giới Thiệu, Vai Trò, và Ứng Dụng Trong Project

> Hướng dẫn chi tiết cho những ai **chưa từng biết** về các loại Redis, cách chúng hoạt động, và tại sao project cần chúng.

---

## **Phần 1: Redis là gì?**

### **Định Nghĩa Đơn Giản**
Redis là một **in-memory database** - một cơ sở dữ liệu lưu trữ dữ liệu **trong bộ nhớ RAM** của server thay vì trên ổ cứng.

```
Traditional Database (MySQL)
┌─────────────────┐
│   Disk (slow)   │ ← Phải đọc từ ổ cứng
│  ~1-10ms/query  │
└─────────────────┘

Redis Database
┌─────────────────┐
│   RAM (fast)    │ ← Lưu trong memory
│  ~0.1-1ms/query │ ← Nhanh 10x hơn!
└─────────────────┘
```

### **Tại Sao Cần Redis?**
- **Nhanh:** In-memory → response trong microseconds
- **Distributed:** Dùng bên ngoài application, multiple instances cùng truy cập
- **Atomic:** Lua scripts cho atomic operations
- **Locking:** Built-in support cho distributed locking

---

## **Phần 2: Bốn Loại Redis Trong Project**

### **Tổng Quan**

Project này dùng Redis theo **4 cách khác nhau**:

```
┌─────────────────────────────────────────────────────────────────────┐
│                      Flash Sale Project                              │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  ┌──────────────────┐  ┌──────────────────┐  ┌──────────────────┐  │
│  │  TYPE 1: Cache   │  │  TYPE 2: Lua     │  │  TYPE 3: Lock    │  │
│  │   (Simple ops)   │  │   (Atomic ops)   │  │ (Coordination)   │  │
│  │                  │  │                  │  │                  │  │
│  │ GET/SET/INCR     │  │ EVALSHA Script   │  │ Redisson RLock   │  │
│  │ Redis String     │  │ Redis String     │  │ Redis Hash       │  │
│  └──────────────────┘  └──────────────────┘  └──────────────────┘  │
│                                                                      │
│  ┌──────────────────────────────────────────────────────────────┐   │
│  │  TYPE 4: Idempotency (Local In-Memory Cache)                │   │
│  │  ConcurrentHashMap - NOT stored in Redis!                   │   │
│  └──────────────────────────────────────────────────────────────┘   │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

---

# **LOẠI 1: REDIS CACHE - Simple Key-Value Storage**

## **1.1 Định Nghĩa**

Redis Cache là lưu trữ **dữ liệu đơn giản** kiểu key-value trong memory để **tăng tốc độ đọc**.

```
Key              Value
────────────────────────
TICKET:4:STOCK   9999
TICKET:5:STOCK   5000
USER:101:EMAIL   john@example.com
```

## **1.2 Tại Sao Cần?**

```
Scenario: 1000 users cùng lúc mua ticket

WITHOUT Cache (MySQL Only):
  User 1: SELECT stock FROM ticket WHERE id=4 → 1-10ms
  User 2: SELECT stock FROM ticket WHERE id=4 → 1-10ms
  ...
  User 1000: SELECT stock FROM ticket WHERE id=4 → 1-10ms
  
  Total time: 1000 requests × 5ms = 5 seconds ❌

WITH Cache (Redis):
  User 1: GET TICKET:4:STOCK → 0.1ms
  User 2: GET TICKET:4:STOCK → 0.1ms
  ...
  User 1000: GET TICKET:4:STOCK → 0.1ms
  
  Total time: 1000 requests × 0.1ms = 0.1 seconds ✅
  
Speedup: 50x nhanh hơn!
```

## **1.3 Code Implementation**

### **File: RedisConfig.java**
```java
// Location: app/backend/xxxx-infrastructure/src/main/java/com/xxxx/ddd/infrastructure/config/RedisConfig.java
// Lines: 1-27

@Configuration
public class RedisConfig {
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> redisTemplate = new RedisTemplate<>();
        redisTemplate.setConnectionFactory(connectionFactory);
        
        // Cấu hình serializer (cách chuyển đổi data sang format Redis)
        Jackson2JsonRedisSerializer serializer = new Jackson2JsonRedisSerializer(Object.class);
        redisTemplate.setKeySerializer(new StringRedisSerializer());
        redisTemplate.setValueSerializer(serializer);
        
        redisTemplate.afterPropertiesSet();
        return redisTemplate;
    }
}
```

**Giải Thích:**
- `RedisTemplate` = cầu nối giữa Java code và Redis database
- `StringRedisSerializer` = chuyển String sang Redis format
- `Jackson2JsonRedisSerializer` = chuyển Object sang JSON format

### **File: RedisInfrasServiceImpl.java**
```java
// Location: app/backend/xxxx-infrastructure/src/main/java/com/xxxx/ddd/infrastructure/cache/redis/RedisInfrasServiceImpl.java
// Lines: 1-135

@Component
@Slf4j
public class RedisInfrasServiceImpl implements RedisInfrasService {
    
    @Resource
    private RedisTemplate<String, Object> redisTemplate;
    
    // Operation 1: SET - Lưu dữ liệu
    @Override
    public void setInt(String key, int value) {
        redisTemplate.opsForValue().set(key, value);
        // Ví dụ: setInt("TICKET:4:STOCK", 9999)
        // Redis: SET TICKET:4:STOCK 9999
    }
    
    // Operation 2: GET - Lấy dữ liệu
    @Override
    public int getInt(String key) {
        Integer value = getIntOrNull(key);
        return value == null ? 0 : value;
        // Ví dụ: getInt("TICKET:4:STOCK")
        // Redis: GET TICKET:4:STOCK
        // Return: 9999
    }
    
    // Operation 3: INCREMENT - Tăng giá trị
    @Override
    public Long increment(String key, long delta) {
        return redisTemplate.opsForValue().increment(key, delta);
        // Ví dụ: increment("TICKET:4:STOCK", 1)
        // Redis: INCR TICKET:4:STOCK
        // Nếu giá trị = 9998, sau INCR = 9999
    }
    
    // Operation 4: DELETE - Xóa dữ liệu
    @Override
    public void delete(String key) {
        redisTemplate.delete(key);
        // Ví dụ: delete("TICKET:4:STOCK")
        // Redis: DEL TICKET:4:STOCK
    }
}
```

**Operations Explained:**

| Operation | Redis Command | Java Code | Ví Dụ |
|---|---|---|---|
| **SET** | `SET key value` | `setInt("TICKET:4:STOCK", 9999)` | Lưu stock hiện tại |
| **GET** | `GET key` | `getInt("TICKET:4:STOCK")` | Lấy stock hiện tại |
| **INCR** | `INCR key` | `increment("TICKET:4:STOCK", 1)` | Tăng stock +1 |
| **INCRBY** | `INCRBY key delta` | `increment("TICKET:4:STOCK", 5)` | Tăng stock +5 |
| **DELETE** | `DEL key` | `delete("TICKET:4:STOCK")` | Xóa stock |

### **File: StockOrderCacheService.java**
```java
// Location: app/backend/xxxx-application/src/main/java/com/xxxx/ddd/application/service/order/cache/StockOrderCacheService.java
// Lines: 1-85

@Service
@Slf4j
public class StockOrderCacheService {
    
    @Autowired
    private CacheStore cacheStore;  // Redis adapter
    
    // Warmup: Lấy stock từ MySQL, lưu vào Redis
    public boolean addStockAvailableToCache(Long ticketId) {
        if(ticketId == null) return false;
        
        // Bước 1: Lấy stock từ MySQL
        TicketDetailCache ticketDetailCache = 
            ticketDetailCacheServiceRefactor.getTicketDetail(ticketId, null);
        if(ticketDetailCache == null) return false;
        
        // Bước 2: Tạo key
        String keyStockItemCache = getKeyStockItemCache(ticketId);
        // keyStockItemCache = "TICKET:4:STOCK"
        
        // Bước 3: Lưu vào Redis
        cacheStore.setInt(keyStockItemCache, 
            ticketDetailCache.getTicketDetail().getStockAvailable());
        // Sau dòng này: Redis có TICKET:4:STOCK = 10000
        
        return true;
    }
    
    // Đọc stock từ Redis
    public int getStockCache(Long ticketId) {
        Integer stock = cacheStore.getIntOrNull(getKeyStockItemCache(ticketId));
        return stock == null ? -1 : stock;
        // Ví dụ: GET TICKET:4:STOCK → return 9999
    }
    
    // Tăng stock (dùng cho compensation)
    public void restoreStockCache(Long ticketId, int quantity) {
        cacheStore.increment(getKeyStockItemCache(ticketId), quantity);
        // Ví dụ: INCR TICKET:4:STOCK +1
        // Nếu trước = 9998, sau = 9999
    }
    
    // Key naming convention
    private String getKeyStockItemCache(Long ticketId) {
        return "TICKET:" + ticketId + ":STOCK";
        // ticketId=4 → "TICKET:4:STOCK"
    }
}
```

## **1.4 Flow - Cách Cache Hoạt Động**

```
POST /orders?quantity=1&strategy=REDIS_LUA_WITH_COMPENSATION

┌─────────────────────────────────────────────────────────────┐
│ TicketOrderController.createOrder()                          │
└─────────────────────────────────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────────┐
│ OrderCreationService.doCreateOrder()                         │
└─────────────────────────────────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────────┐
│ StockOrderCacheService.getStockCache(4)                      │
│                                                              │
│  ┌──────────────────────────────────────────────────────┐   │
│  │ cacheStore.getInt("TICKET:4:STOCK")                 │   │
│  │                                                       │   │
│  │ Redis:                                               │   │
│  │ ├─ GET TICKET:4:STOCK                               │   │
│  │ └─ Return: 9999                                      │   │
│  └──────────────────────────────────────────────────────┘   │
│                                                              │
│  Return to application: stock = 9999                        │
└─────────────────────────────────────────────────────────────┘
                           │
                           ▼
                    (Kiểm tra: 9999 >= 1? YES)
                           │
                           ▼
┌─────────────────────────────────────────────────────────────┐
│ Proceed với REDIS_LUA strategy                              │
└─────────────────────────────────────────────────────────────┘
```

## **1.5 Thực Tế: Khi Nào Sử Dụng**

**✅ Dùng Cache khi:**
- Dữ liệu đọc nhiều, thay đổi ít (stock thay đổi hiếm khi)
- Cần tốc độ cao (1000+ requests/sec)
- Có thể chấp nhận dữ liệu cũ khoảng thời gian (30 seconds lag ok)

**❌ Không dùng Cache khi:**
- Dữ liệu phải luôn chính xác 100% (không có tolerance)
- Write-heavy (cập nhật liên tục)

---

# **LOẠI 2: REDIS LUA SCRIPT - Atomic Operations**

## **2.1 Định Nghĩa**

Redis Lua Script là chương trình **nhỏ** (viết bằng ngôn ngữ Lua) mà **Redis execute nguyên tử** - tất cả lệnh chạy xong mới cho thread khác can thiệp.

```
Normal Redis Commands:
┌─────────────────────────────────────────┐
│  GET key         ← Lệnh 1 (có thể bị cut)
│  CHECK value     ← Lệnh 2 (có thể bị cut)
│  SET key new     ← Lệnh 3 (có thể bị cut)
└─────────────────────────────────────────┘
Problem: Thread khác có thể xen giữa!

Redis Lua Script:
┌─────────────────────────────────────────┐
│  ╔════════════════════════════════════╗ │
│  ║ local stock = GET key              ║ │ ATOMIC BLOCK
│  ║ if stock >= qty: SET new_stock ... ║ │ (No interruption!)
│  ║ return stock                       ║ │
│  ╚════════════════════════════════════╝ │
└─────────────────────────────────────────┘
Guarantee: Toàn bộ chạy xong, không ai xen ngang!
```

## **2.2 Tại Sao Cần Lua Script?**

### **Ví Dụ Race Condition (Vấn Đề)**

```
Scenario: 2 users cùng lúc mua 1 ticket, stock = 1

WITHOUT Lua Script (Normal Redis):
┌─────────────────────────────────────┐
│ User A              │ User B          │
├─────────────────────┼─────────────────┤
│ GET stock=1         │                 │
│                     │ GET stock=1 (!)  │ ← Both see 1!
│ CHECK: 1 >= 1? YES  │                 │
│                     │ CHECK: 1 >= 1? YES
│ SET stock=0         │                 │
│                     │ SET stock=0 (!) │ ← Both set to 0!
│                     │                 │
│ ✓ Order SUCCESS     │ ✓ Order SUCCESS │ ← Both succeed!
│   stock becomes 0   │   stock=0       │
└─────────────────────┴─────────────────┘

Result: 2 orders placed nhưng stock chỉ 1!
        Oversell xảy ra! 🔴


WITH Lua Script (Atomic):
┌─────────────────────────────────────┐
│ User A              │ User B          │
├─────────────────────┼─────────────────┤
│ EVALSHA luaScript   │                 │
│ ┌─────────────────┐ │                 │
│ │ GET stock=1     │ │ ⏳ WAITING       │
│ │ CHECK: YES      │ │ (Lua running)   │
│ │ SET stock=0     │ │                 │
│ │ RETURN 0        │ │                 │
│ └─────────────────┘ │                 │
│ ✓ Order SUCCESS     │                 │
│   stock becomes 0   │                 │
│                     │ EVALSHA luaScript
│                     │ ┌─────────────────┐
│                     │ │ GET stock=0     │
│                     │ │ CHECK: 0 >= 1?  │
│                     │ │ NO! RETURN -1   │
│                     │ │ (not enough)    │
│                     │ └─────────────────┘
│                     │ ❌ Order FAILED
│                     │ (INSUFFICIENT_STOCK)
└─────────────────────┴─────────────────┘

Result: Chỉ 1 order succeeded, không oversell! ✅
```

## **2.3 Code Implementation**

### **File: RedisCacheStoreAdapter.java**
```java
// Location: app/backend/xxxx-infrastructure/src/main/java/com/xxxx/ddd/infrastructure/cache/redis/RedisCacheStoreAdapter.java
// Lines: 54-64

@Override
public long decreaseIntByLuaReturningRemaining(String key, int quantity) {
    // Lua script định nghĩa 1 transaction nguyên tử
    String luaScript = 
        "local stock = tonumber(redis.call('GET', KEYS[1])); " +  // ← Dòng 1
        "if (stock == nil) then return -2; end; " +              // ← Dòng 2
        "if (stock >= tonumber(ARGV[1])) then " +               // ← Dòng 3
        "   redis.call('SET', KEYS[1], stock - tonumber(ARGV[1])); " +  // ← Dòng 4
        "   return stock - tonumber(ARGV[1]); " +               // ← Dòng 5
        "end; " +                                               // ← Dòng 6
        "return -1; ";                                          // ← Dòng 7
    
    // Wrap script thành Redis command
    DefaultRedisScript<Long> redisScript = 
        new DefaultRedisScript<>(luaScript, Long.class);
    
    // Execute script nguyên tử
    Long result = redisInfrasService.getRedisTemplate()
            .execute(redisScript, 
                Collections.singletonList(key),    // KEYS[1] = key
                quantity);                          // ARGV[1] = quantity
    
    return result == null ? -2 : result;
}
```

### **Lua Script Giải Thích Chi Tiết**

```lua
-- Dòng 1: Lấy giá trị stock từ Redis key, convert sang number
local stock = tonumber(redis.call('GET', KEYS[1]));
--         Lưu vào       ↑ Redis command ↑ Key từ Java

-- Dòng 2: Nếu key không tồn tại (nil = null)
if (stock == nil) then 
    return -2;          -- Signal: KEY_NOT_FOUND
end;

-- Dòng 3-5: Nếu stock đủ để giảm
if (stock >= tonumber(ARGV[1])) then 
    -- Dòng 4: Cập nhật stock mới = stock cũ - quantity
    redis.call('SET', KEYS[1], stock - tonumber(ARGV[1]));
    -- Dòng 5: Return phần stock còn lại
    return stock - tonumber(ARGV[1]);
end;

-- Dòng 7: Nếu không có condition nào match (stock < quantity)
return -1;              -- Signal: INSUFFICIENT_STOCK
```

**Return Value Meanings:**

| Giá Trị | Ý Nghĩa | Xử Lý |
|---|---|---|
| `≥ 0` | SUCCESS | Giá trị là stock còn lại (9998, 9999, ...) |
| `-1` | INSUFFICIENT_STOCK | Không đủ stock để deduct |
| `-2` | KEY_NOT_FOUND | Key không tồn tại trong Redis |

**Parameters Mapping:**

```
Java:
decreaseIntByLuaReturningRemaining("TICKET:4:STOCK", 1)

↓ Maps to Lua:

KEYS[1]  = "TICKET:4:STOCK"
ARGV[1]  = 1
```

### **File: StockOrderCacheService.java**
```java
// Location: app/backend/xxxx-application/src/main/java/com/xxxx/ddd/application/service/order/cache/StockOrderCacheService.java
// Lines: 73-80

public int decreaseStockCacheByLUA(Long ticketId, Integer quantity) {
    return (int) decreaseStockCacheByLuaReturningRemaining(ticketId, quantity);
}

public long decreaseStockCacheByLuaReturningRemaining(Long ticketId, Integer quantity) {
    // Tạo key
    String keyStockLUA = getKeyStockItemCache(ticketId);
    // keyStockLUA = "TICKET:4:STOCK"
    
    // Gọi Lua script (nguyên tử)
    return cacheStore.decreaseIntByLuaReturningRemaining(keyStockLUA, quantity);
    // Redis execute: EVALSHA luaScript keys[0]="TICKET:4:STOCK" args[0]=1
}
```

## **2.4 Flow - Cách Lua Script Hoạt Động**

```
POST /orders?quantity=1&strategy=REDIS_LUA_WITH_COMPENSATION

┌───────────────────────────────────────────────────────────────┐
│ RedisLuaCompensatingStockDeductionStrategy.decrease()         │
└───────────────────────────────────────────────────────────────┘
                             │
                             ▼
┌───────────────────────────────────────────────────────────────┐
│ StockOrderCacheService.decreaseStockCacheByLuaReturningRemaining()
│                                                               │
│  String key = "TICKET:4:STOCK"                               │
│  int quantity = 1                                            │
│                                                              │
│  cacheStore.decreaseIntByLuaReturningRemaining(key, qty)     │
└───────────────────────────────────────────────────────────────┘
                             │
                             ▼
┌───────────────────────────────────────────────────────────────┐
│ RedisCacheStoreAdapter.decreaseIntByLuaReturningRemaining()   │
│                                                               │
│  redisTemplate.execute(luaScript, keys, args)                │
│                       ↓                                       │
│  Redis EVALSHA:                                              │
│  ┌─────────────────────────────────────────────────────────┐ │
│  │ ATOMIC BLOCK (lock hết):                                │ │
│  │  local stock = GET "TICKET:4:STOCK"  → 9999             │ │
│  │  if stock == nil? NO                                    │ │
│  │  if stock >= 1? YES (9999 >= 1)                         │ │
│  │    SET "TICKET:4:STOCK" = 9999 - 1 = 9998              │ │
│  │    return 9998                                          │ │
│  └─────────────────────────────────────────────────────────┘ │
│                                                              │
│  return 9998                                                 │
└───────────────────────────────────────────────────────────────┘
                             │
                             ▼
┌───────────────────────────────────────────────────────────────┐
│ Back to decrease() method:                                    │
│                                                               │
│  long remainingStock = 9998                                   │
│                                                              │
│  if (remainingStock < 0) {                                   │
│      // remainingStock = -1 or -2 case                       │
│      return FAILURE                                          │
│  }                                                            │
│                                                               │
│  // remainingStock = 9998 (> 0) = SUCCESS                   │
│  // Proceed to MySQL                                         │
└───────────────────────────────────────────────────────────────┘
                             │
                             ▼
┌───────────────────────────────────────────────────────────────┐
│ MySQL Double-Check:                                           │
│ UPDATE ticket_4_202605                                       │
│ SET stock = stock - 1                                        │
│ WHERE stock >= 1                                             │
│ (MySQL also atomic via WHERE clause)                         │
└───────────────────────────────────────────────────────────────┘
```

## **2.5 Lua vs Normal Redis Comparison**

| Aspek | Normal Redis | Lua Script |
|---|---|---|
| **Atomicity** | ❌ Non-atomic | ✅ Atomic |
| **Race Condition** | ⚠️ Possible | ✅ Impossible |
| **Execution** | 3+ round trips | 1 round trip |
| **Speed** | Slower (multiple commands) | Faster (single EVALSHA) |
| **Consistency** | Weak | Strong |
| **Use Case** | Simple get/set | Complex read-modify-write |

---

# **LOẠI 3: REDISSON DISTRIBUTED LOCK - Distributed Coordination**

## **3.1 Định Nghĩa**

Redisson Distributed Lock là **cơ chế locking** cho **multiple instances** (máy servers khác nhau) để **phối hợp truy cập dùng chung**.

```
Single Instance (App on 1 Machine):
┌──────────────────────────┐
│  Machine 1               │
│  ┌────────────────────┐  │
│  │  Spring Boot App   │  │
│  │  ┌──────────────┐  │  │
│  │  │ Lock (Java)  │  │  │
│  │  └──────────────┘  │  │
│  └────────────────────┘  │
└──────────────────────────┘


Distributed (Apps on 2+ Machines):
┌──────────────────────────┐    ┌──────────────────────────┐
│  Machine 1               │    │  Machine 2               │
│  ┌────────────────────┐  │    │  ┌────────────────────┐  │
│  │  Spring Boot App A │  │    │  │  Spring Boot App B │  │
│  │  ┌──────────────┐  │  │    │  │  ┌──────────────┐  │  │
│  │  │ Lock (Java)  │  │  │    │  │  │ Lock (Java)  │  │  │
│  │  └──────────────┘  │  │    │  │  └──────────────┘  │  │
│  └────────────────────┘  │    │  └────────────────────┘  │
└──────────────────────────┘    └──────────────────────────┘
        │                                │
        └────────────────────┬───────────┘
                             │
                    ┌────────▼─────────┐
                    │  Redis (Lock)    │ ← Redisson Lock!
                    │                  │
                    │ benchmark:locked │
                    └──────────────────┘
```

## **3.2 Tại Sao Cần Distributed Lock?**

### **Ví Dụ: Benchmark Reset (Vấn Đề)**

```
Scenario: 2 instances, cùng lúc call /admin/benchmarks/reset

WITHOUT Distributed Lock:
┌──────────────────────────┐    ┌──────────────────────────┐
│  Instance 1              │    │  Instance 2              │
├──────────────────────────┼────┼──────────────────────────┤
│ reset() start            │    │                          │
│ ├─ DELETE stock data     │    │                          │
│ │                        │    │ reset() start            │
│ │                        │    │ ├─ DELETE stock data     │
│ ├─ Reload from MySQL ❌  │    │ │                        │
│ │ (Instance 2 also       │    │ ├─ Reload from MySQL ❌  │
│ │  deleting same data)   │    │ │ (Race condition!)     │
│ │                        │    │ │                        │
│ └─ DONE                  │    │ └─ DONE                  │
│                          │    │                          │
│ Result: Data corrupted!  │    │ Result: Data corrupted!  │
└──────────────────────────┴────┴──────────────────────────┘

Problem: Both instances reset simultaneously → Data inconsistency!


WITH Distributed Lock (Redisson):
┌──────────────────────────┐    ┌──────────────────────────┐
│  Instance 1              │    │  Instance 2              │
├──────────────────────────┼────┼──────────────────────────┤
│ Try acquire lock         │    │ Try acquire lock         │
│ ✓ SUCCESS (got lock)     │    │ ⏳ WAITING...            │
│ ┌──────────────────────┐ │    │ (Instance 1 holds lock)  │
│ │ reset() execute      │ │    │                          │
│ │ ├─ DELETE stock data │ │    │                          │
│ │ ├─ Reload from MySQL │ │    │ ⏳ WAITING...            │
│ │ └─ Done              │ │    │                          │
│ │ release lock         │ │    │                          │
│ └──────────────────────┘ │    │                          │
│ DONE                     │    │ ✓ Now got lock           │
│                          │    │ ┌──────────────────────┐ │
│                          │    │ │ reset() execute      │ │
│                          │    │ │ ├─ DELETE stock data │ │
│                          │    │ │ ├─ Reload from MySQL │ │
│                          │    │ │ └─ Done              │ │
│                          │    │ │ release lock         │ │
│                          │    │ └──────────────────────┘ │
│                          │    │ DONE                     │
└──────────────────────────┴────┴──────────────────────────┘

Result: Sequential execution, data consistency guaranteed! ✅
```

## **3.3 Code Implementation**

### **File: RedissonConfig.java**
```java
// Location: app/backend/xxxx-infrastructure/src/main/java/com/xxxx/ddd/infrastructure/distributed/redisson/config/RedissonConfig.java
// Lines: 1-60

@Configuration
public class RedissonConfig {
    
    @Value("${app.redisson.mode:single}")
    private String mode;  // SINGLE or SENTINEL
    
    @Value("${app.redisson.single-address:redis://127.0.0.1:6319}")
    private String singleAddress;  // Single instance: localhost:6319
    
    @Value("${app.redisson.sentinel.master:mymaster}")
    private String sentinelMaster;
    
    @Value("${app.redisson.sentinel.nodes:...}")
    private String sentinelNodes;  // Sentinel nodes: port 26379, 26380, 26381
    
    @Bean
    public RedissonClient redissonClient() {
        Config config = new Config();
        
        // Mode 1: Single Server (simple setup)
        if ("sentinel".equalsIgnoreCase(mode)) {
            // Mode 2: Sentinel (HA - High Availability)
            var sentinelConfig = config.useSentinelServers()
                    .setMasterName(sentinelMaster)
                    .addSentinelAddress(sentinelNodes.split(","))
                    .setCheckSentinelsList(false)
                    .setDatabase(0)
                    .setMasterConnectionPoolSize(50)     // Connection pool size
                    .setMasterConnectionMinimumIdleSize(10);
            if (StringUtils.hasText(password)) {
                sentinelConfig.setPassword(password);
            }
        } else {
            // Default: Single server
            var singleConfig = config.useSingleServer()
                    .setAddress(singleAddress)           // redis://127.0.0.1:6319
                    .setConnectionPoolSize(50)           // Max connections
                    .setConnectionMinimumIdleSize(10)    // Min idle connections
                    .setDatabase(0);
            if (StringUtils.hasText(password)) {
                singleConfig.setPassword(password);
            }
        }
        
        return Redisson.create(config);
    }
}
```

**Configuration Modes:**

| Mode | Topology | Failover | Use Case |
|---|---|---|---|
| **SINGLE** | 1 Redis server | ❌ No | Development, testing |
| **SENTINEL** | 1 master + N slaves | ✅ Yes | Production, HA |
| **CLUSTER** | 16K slots, multiple masters | ✅ Yes | High scalability |

### **File: RedisDistributedLockerImpl.java**
```java
// Location: app/backend/xxxx-infrastructure/src/main/java/com/xxxx/ddd/infrastructure/distributed/redisson/impl/RedisDistributedLockerImpl.java
// Lines: 1-60

@Service
@Slf4j
public class RedisDistributedLockerImpl implements RedisDistributedService {
    
    @Resource
    private RedissonClient redissonClient;  // Redisson client
    
    @Override
    public RedisDistributedLocker getDistributedLock(String lockKey) {
        // Lấy RLock object từ Redisson client
        RLock rLock = redissonClient.getLock(lockKey);
        // lockKey = "benchmark:locked"
        
        // Return wrapper object với methods
        return new RedisDistributedLocker() {
            
            // Method 1: Try lock with timeout
            @Override
            public boolean tryLock(long waitTime, long leaseTime, TimeUnit unit) 
                    throws InterruptedException {
                // Wait up to `waitTime` to acquire lock
                // Hold lock for `leaseTime` if acquired
                boolean isLockSuccess = rLock.tryLock(waitTime, leaseTime, unit);
                
                // Ví dụ: tryLock(5 seconds wait, 10 seconds lease, SECONDS)
                // ├─ Wait 5 seconds để acquire lock
                // ├─ If acquired: hold for 10 seconds
                // └─ If not: return false
                
                return isLockSuccess;
            }
            
            // Method 2: Blocking lock (wait forever)
            @Override
            public void lock(long leaseTime, TimeUnit unit) {
                rLock.lock(leaseTime, unit);
                // Block cho đến khi acquire lock
                // Ví dụ: lock(10 seconds, SECONDS)
                // ├─ Block indefinitely
                // ├─ When acquired: hold for 10 seconds
                // └─ Then auto-release
            }
            
            // Method 3: Release lock
            @Override
            public void unlock() {
                if (isLocked() && isHeldByCurrentThread()) {
                    rLock.unlock();
                }
                // Chỉ unlock nếu:
                // ├─ Lock đang active
                // └─ Current thread holds it
            }
            
            // Method 4: Check if locked
            @Override
            public boolean isLocked() {
                return rLock.isLocked();
                // return true nếu lock đang held bởi ai đó
            }
            
            // Method 5: Check if held by specific thread
            @Override
            public boolean isHeldByCurrentThread() {
                return rLock.isHeldByCurrentThread();
                // return true nếu current thread holds lock
            }
        };
    }
}
```

## **3.4 Usage Example**

```java
// Somewhere in BenchmarkService.java
@Service
public class BenchmarkService {
    
    @Autowired
    private RedisDistributedService redisDistributedService;
    
    public void resetBenchmark() {
        // Lấy lock object
        RedisDistributedLocker locker = 
            redisDistributedService.getDistributedLock("benchmark:locked");
        
        try {
            // Try acquire lock trong 30 seconds, hold for 60 seconds
            boolean acquired = locker.tryLock(30, 60, TimeUnit.SECONDS);
            
            if (!acquired) {
                throw new RuntimeException("Could not acquire benchmark lock");
            }
            
            // Critical section - chỉ 1 instance chạy cùng lúc
            deleteAllOrderData();      // DELETE từ database
            reloadStockFromDatabase(); // Reload stock từ MySQL
            warmupRedisCache();        // Warmup Redis cache
            
        } finally {
            // Always release lock
            locker.unlock();
        }
    }
}
```

## **3.5 Flow - Distributed Lock Hoạt Động**

```
POST /admin/benchmarks/reset (từ Load Balancer)

     ┌────────────────────────────────────┐
     │  Request đến Instance #1 hoặc #2   │
     │  (Round-robin load balancing)      │
     └────────┬───────────────────────────┘
              │
              ▼
     ┌────────────────────────────────────┐
     │  BenchmarkService.resetBenchmark() │
     └────────┬───────────────────────────┘
              │
              ▼
     ┌────────────────────────────────────────────────────────┐
     │ redisDistributedService.getDistributedLock(           │
     │     "benchmark:locked"                                 │
     │ )                                                      │
     │                                                         │
     │ ┌──────────────────────────────────────────────────┐   │
     │ │ Redis Distributed Lock Object Created            │   │
     │ │ (Lock stored in Redis server)                    │   │
     │ │                                                  │   │
     │ │ Lock structure:                                  │   │
     │ │ ├─ Key: "benchmark:locked"                       │   │
     │ │ ├─ Owner: instance-1 (or instance-2)             │   │
     │ │ ├─ TTL: 60 seconds                               │   │
     │ │ └─ Status: LOCKED                                │   │
     │ └──────────────────────────────────────────────────┘   │
     └────────┬───────────────────────────────────────────────┘
              │
              ▼
     ┌────────────────────────────────────────────────────────┐
     │ locker.tryLock(30 seconds wait, 60 seconds lease)     │
     │                                                         │
     │ Case 1: Lock available                                │
     │ ├─ Acquire lock immediately                           │
     │ ├─ Hold for 60 seconds                                │
     │ └─ return true                                        │
     │                                                         │
     │ Case 2: Lock held by other instance                  │
     │ ├─ Wait up to 30 seconds                              │
     │ ├─ If released before 30s: acquire                   │
     │ ├─ If not released after 30s: timeout               │
     │ └─ return false                                       │
     └────────┬───────────────────────────────────────────────┘
              │
         ✓ acquired
         (TRUE)
              │
              ▼
     ┌────────────────────────────────────────────────────────┐
     │ Critical Section (Protected):                         │
     │                                                         │
     │ deleteAllOrderData()      // DELETE orders            │
     │ reloadStockFromDatabase() // SELECT from MySQL        │
     │ warmupRedisCache()        // SET stock into Redis     │
     │                                                         │
     │ Only 1 instance executes this at a time!             │
     └────────┬───────────────────────────────────────────────┘
              │
              ▼
     ┌────────────────────────────────────────────────────────┐
     │ finally: locker.unlock()                              │
     │                                                         │
     │ Release lock in Redis                                 │
     │ Other instance waiting can now acquire                │
     └────────┬───────────────────────────────────────────────┘
              │
              ▼
     ┌────────────────────────────────────────────────────────┐
     │ HTTP Response: 200 OK                                 │
     │ {                                                      │
     │   "success": true,                                     │
     │   "message": "Benchmark reset completed"              │
     │ }                                                      │
     └────────────────────────────────────────────────────────┘
```

---

# **LOẠI 4: IDEMPOTENCY CACHE - Local In-Memory Cache**

## **4.1 Định Nghĩa**

Idempotency Cache lưu **kết quả request** trong **memory của instance** để nếu client **retry** request cùng lúc, response sẽ từ cache chứ không execute lại.

```
⚠️ QUAN TRỌNG: Không phải Redis! Đây là Java ConcurrentHashMap!

┌─────────────────────────────────────────────────┐
│  Instance 1 Memory                              │
│  ┌───────────────────────────────────────────┐  │
│  │ IdempotencyService                        │  │
│  │ ┌─────────────────────────────────────┐   │  │
│  │ │ Map: {userId}:{key} → Response      │   │  │
│  │ ├─────────────────────────────────────┤   │  │
│  │ │ "john:req-123" → CreateOrderResponse│   │  │
│  │ │ "mary:req-456" → CreateOrderResponse│   │  │
│  │ └─────────────────────────────────────┘   │  │
│  └───────────────────────────────────────────┘  │
│                                                  │
│  ⚠️ Only in this instance's memory              │
│  ⚠️ NOT in Redis                                │
│  ⚠️ Lost when instance restarts                 │
└─────────────────────────────────────────────────┘
```

## **4.2 Tại Sao Cần Idempotency Cache?**

### **Ví Dụ: Duplicate Request (Vấn Đề)**

```
Scenario: Client sends POST /orders, network timeout, retries

WITHOUT Idempotency Cache:
┌──────────────────────────────────────────────────┐
│ Client:                                          │
│                                                  │
│ Request 1: POST /orders {quantity: 1, key:123}  │
│ └─ Server processes...                         │
│ └─ ✓ Order placed: order-ABC                   │
│ └─ Response being sent...                      │
│ └─ ❌ Network timeout!                          │
│                                                  │
│ Request 2 (Retry): POST /orders {quantity:1, key:123}
│ └─ Server processes...                         │
│ └─ ✓ Order placed AGAIN: order-XYZ ❌          │
│ └─ 200 OK response                             │
│                                                  │
│ Result: 2 orders placed from 1 user action!   │
└──────────────────────────────────────────────────┘

Problem: Duplicate orders! Double charging! 🔴


WITH Idempotency Cache:
┌──────────────────────────────────────────────────┐
│ Client:                                          │
│                                                  │
│ Request 1: POST /orders {quantity: 1, key:123}  │
│ └─ Server processes...                         │
│ └─ Check cache["john:123"] → NOT FOUND         │
│ └─ Create order: order-ABC                     │
│ └─ Cache["john:123"] = order-ABC response      │
│ └─ Response being sent...                      │
│ └─ ❌ Network timeout!                          │
│                                                  │
│ Request 2 (Retry): POST /orders {quantity:1, key:123}
│ └─ Server processes...                         │
│ └─ Check cache["john:123"] → FOUND! ✓          │
│ └─ Return cached response (no new order)       │
│ └─ 200 OK response with same order: order-ABC  │
│                                                  │
│ Result: Same order returned, idempotent! ✅    │
└──────────────────────────────────────────────────┘
```

## **4.3 Code Implementation**

### **File: IdempotencyService.java**
```java
// Location: app/backend/xxxx-application/src/main/java/com/xxxx/ddd/application/service/order/IdempotencyService.java
// Lines: 1-30

@Service
public class IdempotencyService {
    
    // In-memory cache (NOT Redis!)
    private final Map<String, CreateOrderResponse> responses = 
        new ConcurrentHashMap<>();
    
    /**
     * Get or create: getOrCreate pattern
     * 
     * If key exists: return cached response (no execution)
     * If key not exists: execute supplier, cache result, return
     */
    public CreateOrderResponse getOrCreate(String key, 
        Supplier<CreateOrderResponse> supplier) {
        // ConcurrentHashMap.computeIfAbsent:
        // ├─ Atomic operation (thread-safe)
        // ├─ If key present: return value
        // └─ If key absent: compute & cache value
        
        return responses.computeIfAbsent(key, ignored -> supplier.get());
        
        // Ví dụ:
        // ├─ First call: key="john:req-123" not in cache
        // │  ├─ Execute supplier (createOrder logic)
        // │  ├─ Cache result
        // │  └─ Return result
        //
        // ├─ Second call: key="john:req-123" in cache
        // │  ├─ Return cached result (no execution!)
        // │  └─ Result returned
    }
    
    // Clear cache (dùng cho test/reset)
    public void clear() {
        responses.clear();
    }
}
```

### **File: OrderCreationService.java**
```java
// Location: app/backend/xxxx-application/src/main/java/com/xxxx/ddd/application/service/order/OrderCreationService.java
// (Giả sử, based on structure)

@Service
public class OrderCreationService {
    
    @Autowired
    private IdempotencyService idempotencyService;
    
    public CreateOrderResponse createOrder(CreateOrderRequest request) {
        // Bước 1: Build idempotency key
        String idempotencyKey = buildIdempotencyKey(
            request.getUserId(), 
            request.getIdempotencyKey()
        );
        // idempotencyKey = "john:req-123"
        
        // Bước 2: Get or create (idempotent)
        return idempotencyService.getOrCreate(
            idempotencyKey,
            () -> doCreateOrder(request)  // Supplier - chỉ execute nếu cache miss
        );
        
        // Execution:
        // ├─ If cache hit: return cached response
        // └─ If cache miss: execute doCreateOrder(), cache result, return
    }
    
    // Private method: actual order creation logic
    private CreateOrderResponse doCreateOrder(CreateOrderRequest request) {
        // Actual business logic:
        // ├─ Select strategy (UNSAFE_DB, CONDITIONAL_DB, REDIS_LUA, etc.)
        // ├─ Execute strategy
        // ├─ Call MySQL
        // ├─ Read Redis & DB stock
        // └─ Build response
        
        // ... implementation ...
        
        return response;
    }
    
    // Helper: build idempotency key
    private String buildIdempotencyKey(String userId, String requestKey) {
        return userId + ":" + requestKey;
        // "john" + ":" + "req-123" = "john:req-123"
    }
}
```

## **4.4 Flow - Idempotency Cache Hoạt Động**

```
POST /orders
{
  "userId": "john",
  "quantity": 1,
  "idempotencyKey": "req-123"
}

┌────────────────────────────────────────────────────────┐
│ TicketOrderController.createOrder()                    │
└────────────┬───────────────────────────────────────────┘
             │
             ▼
┌────────────────────────────────────────────────────────┐
│ OrderCreationService.createOrder()                     │
│                                                         │
│ String key = buildIdempotencyKey("john", "req-123")   │
│            = "john:req-123"                            │
└────────────┬───────────────────────────────────────────┘
             │
             ▼
┌────────────────────────────────────────────────────────┐
│ idempotencyService.getOrCreate(key, supplier)         │
│                                                         │
│ responses.computeIfAbsent("john:req-123", ...)        │
│                                                         │
│ Case 1: Key NOT in cache (First request)              │
│ ├─ Execute supplier: doCreateOrder()                  │
│ │  ├─ Select strategy                                │
│ │  ├─ Execute strategy (Redis Lua)                    │
│ │  ├─ MySQL UPDATE                                   │
│ │  ├─ Read stock                                      │
│ │  └─ Build response                                  │
│ ├─ Cache["john:req-123"] = response                   │
│ └─ return response ✓                                  │
│                                                         │
│ Case 2: Key in cache (Retry request)                  │
│ ├─ Found in cache!                                    │
│ ├─ return cached response ✓ (no execution)            │
│ └─ Done                                               │
└────────────┬───────────────────────────────────────────┘
             │
             ▼
┌────────────────────────────────────────────────────────┐
│ HTTP Response:                                         │
│ {                                                      │
│   "success": true,                                     │
│   "orderNumber": "OKX-SGN-john-1234567890",           │
│   "redisStockAfter": 9999,                            │
│   "dbStockAfter": 9999                                │
│ }                                                      │
└────────────────────────────────────────────────────────┘
```

## **4.5 Limitations**

```
⚠️ Local Idempotency Cache Limitations:

1. NOT Distributed
   ├─ Only works for retries on SAME instance
   ├─ If request hits different instance, cache miss!
   └─ Solution: Use Redis for distributed idempotency

2. Lost on Restart
   ├─ Stored in Java memory (heap)
   ├─ Cleared when instance restarts
   └─ Solution: Use Redis for persistence

3. Memory Overhead
   ├─ Stores entire response objects
   ├─ Can grow large with many requests
   └─ Solution: Use TTL + cleanup

4. Unbounded Size
   ├─ No automatic cleanup
   ├─ Can lead to memory leak
   └─ Solution: Clear manually or use guava MapMaker with expiration
```

---

# **Bảng So Sánh Toàn Diện: 4 Loại Redis**

## **Comparison Table**

| Feature | Cache | Lua Script | Distributed Lock | Idempotency |
|---|---|---|---|---|
| **Storage** | Redis String | Redis String | Redis Hash | Java Map |
| **Data Persistence** | Temporary (TTL) | Temporary | Temporary | Lost on restart |
| **Distributed?** | ✅ Yes (all instances) | ✅ Yes (atomic) | ✅ Yes (cross-instance) | ❌ No (local only) |
| **Atomicity** | ❌ Non-atomic | ✅ Atomic | ✅ Atomic locks | ✅ Thread-safe map |
| **Use Case** | Fast read | Atomic operations | Coordination | Retry handling |
| **Performance** | Very fast | Fast | Medium | Fastest (in-memory) |
| **Race Condition** | ⚠️ Possible | ✅ Prevented | ✅ Prevented | ✅ Prevented |
| **Key Pattern** | `TICKET:4:STOCK` | `TICKET:4:STOCK` | `benchmark:locked` | `{userId}:{key}` |
| **Operations** | GET, SET, INCR | EVALSHA | lock, unlock | get, put |
| **TTL/Expiration** | Optional | Optional | Auto (lease) | Manual clear |
| **Consistency** | Eventual | Strong | Strong | Strong (local) |

---

# **Complete Flow: How All 4 Types Work Together**

## **Full Request Flow: POST /orders**

```
POST /orders
{
  "userId": "john",
  "quantity": 1,
  "strategy": "REDIS_LUA_WITH_COMPENSATION"
}

┌─────────────────────────────────────────────────────────────────┐
│ STEP 0: Load Balancer                                           │
│ Route to Instance #1 (or #2 if #1 down)                        │
└──────────┬──────────────────────────────────────────────────────┘
           │
           ▼
┌─────────────────────────────────────────────────────────────────┐
│ STEP 1: TicketOrderController.createOrder()                    │
│ Extract: userId="john", quantity=1                             │
└──────────┬──────────────────────────────────────────────────────┘
           │
           ▼
┌─────────────────────────────────────────────────────────────────┐
│ TYPE 4: IDEMPOTENCY CACHE                                      │
│ ─────────────────────────────────────────────────────────────── │
│                                                                  │
│ Build key: "john:req-123" (userId + idempotencyKey)            │
│                                                                  │
│ Check: responses.containsKey("john:req-123")?                  │
│ ├─ YES: Return cached response ✓ (no further execution)       │
│ └─ NO: Continue...                                             │
└──────────┬──────────────────────────────────────────────────────┘
           │
           ▼
┌─────────────────────────────────────────────────────────────────┐
│ STEP 2: OrderCreationService.doCreateOrder()                   │
│ Execute main order creation logic                              │
└──────────┬──────────────────────────────────────────────────────┘
           │
           ▼
┌─────────────────────────────────────────────────────────────────┐
│ TYPE 3: DISTRIBUTED LOCK (if benchmarking)                     │
│ ─────────────────────────────────────────────────────────────── │
│                                                                  │
│ redissonClient.getLock("benchmark:locked")                     │
│                                                                  │
│ // Optional: only if in benchmark mode                         │
│ if (benchmarkMode) {                                           │
│   locker.tryLock(30 seconds, 60 seconds, SECONDS);            │
│ }                                                               │
│                                                                  │
│ Purpose: Prevent multiple instances from resetting simultaneously
└──────────┬──────────────────────────────────────────────────────┘
           │
           ▼
┌─────────────────────────────────────────────────────────────────┐
│ STEP 3: RedisLuaCompensatingStockDeductionStrategy.decrease()  │
│                                                                  │
│ TYPE 2: REDIS LUA SCRIPT (Atomic Stock Deduction)              │
│ ─────────────────────────────────────────────────────────────── │
│                                                                  │
│ Call: stockOrderCacheService.decreaseStockCacheByLua()         │
│                                                                  │
│ ┌──────────────────────────────────────────────────────────┐   │
│ │ Redis EVALSHA(luaScript)                                 │   │
│ │                                                          │   │
│ │ ATOMIC BLOCK:                                           │   │
│ │  local stock = GET TICKET:4:STOCK  → 9999              │   │
│ │  if stock == nil? NO                                   │   │
│ │  if stock >= 1? YES                                    │   │
│ │    SET TICKET:4:STOCK = 9998                           │   │
│ │    return 9998                                         │   │
│ │                                                          │   │
│ │ Result: 9998 (remaining stock)                          │   │
│ └──────────────────────────────────────────────────────────┘   │
│                                                                  │
│ return 9998 ✓ (SUCCESS)                                        │
└──────────┬──────────────────────────────────────────────────────┘
           │
           ▼
┌─────────────────────────────────────────────────────────────────┐
│ STEP 4: MySQL Double-Check (Source of Truth)                   │
│                                                                  │
│ UPDATE ticket_4_202605                                         │
│ SET stock = stock - 1                                          │
│ WHERE stock >= 1 AND ticket_id = 4                             │
│                                                                  │
│ Result: 1 row updated ✓ (SUCCESS)                              │
└──────────┬──────────────────────────────────────────────────────┘
           │
           ▼
┌─────────────────────────────────────────────────────────────────┐
│ STEP 5: TYPE 1: REDIS CACHE (Read Current State)               │
│ ─────────────────────────────────────────────────────────────── │
│                                                                  │
│ GET TICKET:4:STOCK                                             │
│ │                                                              │
│ ├─ From Redis in-memory: ~0.1ms                               │
│ ├─ Return: 9998 (current stock)                               │
│ └─ (Not re-reading from MySQL, so fast!)                      │
│                                                                  │
│ Also read: MySQL stock (source of truth)                       │
│ SELECT stock FROM ticket_4_202605 WHERE ticket_id=4           │
│ └─ Return: 9998 (matches Redis)                               │
└──────────┬──────────────────────────────────────────────────────┘
           │
           ▼
┌─────────────────────────────────────────────────────────────────┐
│ STEP 6: Build Response & Cache (TYPE 4)                        │
│                                                                  │
│ response = CreateOrderResponse {                               │
│   orderNumber: "OKX-SGN-john-1234567890",                     │
│   redisStockAfter: 9998,                                       │
│   dbStockAfter: 9998,                                          │
│   success: true                                                │
│ }                                                               │
│                                                                  │
│ Cache result:                                                   │
│ responses["john:req-123"] = response                           │
└──────────┬──────────────────────────────────────────────────────┘
           │
           ▼
┌─────────────────────────────────────────────────────────────────┐
│ STEP 7: Release Distributed Lock (TYPE 3)                      │
│                                                                  │
│ locker.unlock()  // If locked in step 2                        │
└──────────┬──────────────────────────────────────────────────────┘
           │
           ▼
┌─────────────────────────────────────────────────────────────────┐
│ HTTP Response: 200 OK                                           │
│ {                                                               │
│   "success": true,                                              │
│   "code": 200,                                                  │
│   "result": {                                                   │
│     "orderNumber": "OKX-SGN-john-1234567890",                 │
│     "redisStockAfter": 9998,                                   │
│     "dbStockAfter": 9998                                       │
│   }                                                             │
│ }                                                               │
└─────────────────────────────────────────────────────────────────┘

SUMMARY:
├─ TYPE 1 (CACHE): Fast read of current stock
├─ TYPE 2 (LUA): Atomic deduction from Redis
├─ TYPE 3 (LOCK): Cross-instance coordination (if benchmark)
└─ TYPE 4 (IDEMPOTENCY): Prevent duplicate processing on retry
```

---

# **Why All 4 Are Needed**

| Type | Problem It Solves |
|---|---|
| **CACHE** | Reduce database load, speed up reads (10x faster) |
| **LUA SCRIPT** | Prevent race conditions in concurrent environment |
| **DISTRIBUTED LOCK** | Coordinate multiple server instances |
| **IDEMPOTENCY** | Handle retries safely, prevent double charging |

**Without any one:**
- Without CACHE: Too slow, database overloaded
- Without LUA: Race conditions, overselling tickets
- Without LOCK: Multiple resets corrupting data
- Without IDEMPOTENCY: Client retries = duplicate orders

**With all four:**
- ✅ Fast performance (CACHE)
- ✅ Correct under load (LUA)
- ✅ Scalable to multiple servers (LOCK)
- ✅ Safe for retries (IDEMPOTENCY)

---

# **Key Files Reference**

```
├─ Configuration:
│  ├─ RedisConfig.java (Line 1-27)
│  └─ RedissonConfig.java (Line 1-60)
│
├─ TYPE 1 - Cache:
│  ├─ RedisInfrasServiceImpl.java (Line 1-135)
│  ├─ RedisCacheStoreAdapter.java (Line 1-80)
│  └─ StockOrderCacheService.java (Line 1-85)
│
├─ TYPE 2 - Lua:
│  ├─ RedisCacheStoreAdapter.java (Line 54-64) ← Lua script
│  ├─ StockOrderCacheService.java (Line 73-80)
│  ├─ RedisLuaStockDeductionStrategy.java
│  └─ RedisLuaCompensatingStockDeductionStrategy.java
│
├─ TYPE 3 - Lock:
│  ├─ RedisDistributedService.java
│  ├─ RedisDistributedLockerImpl.java (Line 1-60)
│  └─ BenchmarkService.java (usage)
│
└─ TYPE 4 - Idempotency:
   └─ IdempotencyService.java (Line 1-30)
```

---

## **Conclusion**

Project này sử dụng **4 loại Redis** để giải quyết **4 vấn đề khác nhau**:

1. **CACHE** → Tốc độ
2. **LUA SCRIPT** → Atomicity
3. **DISTRIBUTED LOCK** → Coordination
4. **IDEMPOTENCY** → Retry Safety

Mỗi loại có vai trò riêng, và cùng nhau tạo thành một **distributed stock management system** an toàn, nhanh, và scalable.

Bạn hiểu rõ chưa? 😊
