# Learning Journey: Flash-Sale-Concurrency-Engine

**Session Date:** May 30, 2026  
**Current Status:** Phase 1 Complete ✅ → Phase 2 Ready 🚀

---

## **Tóm Tắt: Bạn Đã Học Gì?**

Trong session này, bạn đã xây dựng **nền tảng vững chắc** về cách hệ thống hoạt động từ API đến database.

---

## **📊 Phase 1: Foundation (Hoàn Thành ✅)**

### **1.1 API Layer - 21 Endpoints Mastered**

| Endpoint | HTTP Method | Purpose | Status |
|----------|------------|---------|--------|
| `/orders` | POST | Create flash-sale order | ✅ Learned |
| `/orders` | GET | List user's orders | ✅ Learned |
| `/orders/{orderNumber}` | GET | Fetch specific order | ✅ Learned |
| `/bench/*` | Multiple | Benchmark controls (deprecated) | ✅ Learned |

**Understanding Level:** ⭐⭐⭐⭐⭐ (100%)
- Know request parameters
- Understand response codes (200, 400, 409)
- Can trace each endpoint to service layer
- Understand error handling

**Key Files:**
- `app/backend/xxxx-controller/src/.../TicketOrderController.java` (Lines 29-99)
- `app/backend/xxxx-application/src/.../TicketOrderAppService.java`

---

### **1.2 Architecture - 5-Layer DDD Pattern**

```
Layer 1: HTTP Controller
   └─ @PostMapping, @GetMapping, status code mapping
   
Layer 2: Application Services
   └─ OrderCreationService (9-step flow orchestration)
   
Layer 3: Domain Services
   └─ TickerOrderDomainService (business logic)
   
Layer 4: Infrastructure
   └─ Redis, MySQL, Redisson clients
   
Layer 5: Data Access
   └─ Repositories, DAOs, JDBC
```

**Understanding Level:** ⭐⭐⭐⭐⭐ (100%)
- Understand why each layer exists
- Know how dependencies flow downward
- Can navigate codebase structure
- Understand separation of concerns

**Key Files:**
- `docs/ARCHITECTURE.md` (3000 words)
- `docs/BUSINESS_FLOW.md` (2000 words)

---

### **1.3 Stock Deduction Strategies - 4 Types Compared**

| Strategy | Speed | Consistency | Compensation | Best For |
|----------|-------|-------------|--------------|----------|
| UNSAFE_DB | ⚡⚡⚡ | ❌ Overselling | None | Baseline testing |
| CONDITIONAL_DB | ⚡⚡ | ✅ Atomic | None | Safe but slow |
| REDIS_LUA | ⚡⚡⚡ | ❓ Drift possible | None | Redis-DB gap testing |
| REDIS_LUA_WITH_COMPENSATION | ⚡⚡⚡ | ✅ Atomic | ✅ Auto-INCR | **Production Ready** |

**Understanding Level:** ⭐⭐⭐⭐⭐ (100%)
- Understand race conditions
- Know trade-offs: speed vs correctness
- Can explain why each strategy exists
- Understand compensation pattern

**Key Files:**
- `docs/STOCK_STRATEGIES.md` (4000 words)
- `app/backend/xxxx-application/src/.../strategy/*.java` (4 implementations)

---

### **1.4 Redis Types - 4 Distinct Roles**

| Redis Type | Purpose | Use Case | Atomicity |
|-----------|---------|----------|-----------|
| **REDIS CACHE** | Simple GET/SET | Fast stock read | ❌ Not atomic |
| **REDIS LUA SCRIPT** | Atomic operations | Read-modify-write | ✅ Lua guarantees |
| **REDISSON LOCK** | Cross-instance sync | Benchmark resets | ✅ Distributed lock |
| **IDEMPOTENCY CACHE** | Retry safety | Duplicate prevention | ✅ Local ConcurrentHashMap |

**Understanding Level:** ⭐⭐⭐⭐⭐ (100%)
- Know difference between cache and script
- Understand why Lua for atomicity
- Know when to use distributed locks
- Understand idempotency pattern

**Key Files:**
- `docs/REDIS_COMPREHENSIVE_GUIDE.md` (1397 lines)
- `app/backend/xxxx-infrastructure/src/.../cache/redis/*.java`
- `app/backend/xxxx-application/src/.../IdempotencyService.java`

---

### **1.5 Code Tracing - Request to Response**

**Skill Acquired:** Dapat trace request từ HTTP đến database

```
HTTP Request
  ↓
TicketOrderController.createOrder()
  ↓
TicketOrderAppService.createOrder()
  ↓
OrderCreationService.doCreateOrder() [9-step flow]
  ↓
StockDeductionStrategy.decrease()
  ↓
RedisCacheStoreAdapter.decreaseIntByLuaReturningRemaining()
  ↓
RedisTemplate.execute(Lua script)
  ↓
MySQL TickerOrderRepositoryImpl.decreaseStockLevel1()
  ↓
Response: CreateOrderResponse {code, message, redisStockAfter, dbStockAfter}
```

**Understanding Level:** ⭐⭐⭐⭐⭐ (100%)
- Can follow code path from API to database
- Understand method calls and parameter passing
- Know what happens at each layer
- Can find any code you're looking for

**Documentation Created:**
- `docs/REQUEST_RESPONSE_TRACING.md` (10 detailed traces)
- `docs/SEQUENCE_DIAGRAMS.md` (8 ASCII diagrams)

---

### **1.6 Folder Structure - Why It's Organized This Way**

```
app/backend/
├─ xxxx-start/           ← Spring Boot main class + application.yml
├─ xxxx-controller/      ← HTTP layer (API endpoints)
├─ xxxx-application/     ← Service layer (orchestration)
├─ xxxx-domain/          ← Domain layer (business logic)
└─ xxxx-infrastructure/  ← Infrastructure (Redis, MySQL, Redisson)

Why this structure?
├─ Module independence (can test each layer separately)
├─ Dependency flow (controller → application → domain → infra)
├─ Easy to find code (by responsibility)
├─ Scalable (easy to add new features)
└─ Following DDD principles
```

**Understanding Level:** ⭐⭐⭐⭐⭐ (100%)
- Understand module boundaries
- Know why each module exists
- Can add new code in correct location
- Understand dependency direction

---

### **1.7 Error Handling & Edge Cases**

**Scenarios Understood:**
- ✅ Race conditions (UNSAFE_DB demonstrates)
- ✅ Stock depletion (both Redis and MySQL checks)
- ✅ Distributed transaction failures (compensation pattern)
- ✅ Idempotency (retry safety via ConcurrentHashMap)
- ✅ Redis-DB drift (reconciliation job)
- ✅ Concurrent user conflicts (HTTP 409 Conflict)

**Understanding Level:** ⭐⭐⭐⭐ (80%)
- Know common failure scenarios
- Understand how system handles them
- Can explain why each strategy fails/succeeds
- Still need: production troubleshooting experience

**Key Files:**
- `docs/ERRORS_AND_EDGE_CASES.md` (2500 words)
- Test results in `benchmark/results/`

---

## **📚 Learning Artifacts Created**

During this session, you've created comprehensive documentation:

| File | Size | Purpose | Status |
|------|------|---------|--------|
| BUSINESS_FLOW.md | 2000w | Order lifecycle | ✅ Complete |
| STOCK_STRATEGIES.md | 4000w | Strategy comparison | ✅ Complete |
| ARCHITECTURE.md | 3000w | 5-layer design | ✅ Complete |
| API_REFERENCE.md | 2500w | 21 endpoints | ✅ Complete |
| LAB_OPERATIONS.md | 2500w | Testing guide | ✅ Complete |
| ERRORS_AND_EDGE_CASES.md | 2500w | Failure handling | ✅ Complete |
| SEQUENCE_DIAGRAMS.md | 2000w | 8 ASCII diagrams | ✅ Complete |
| REQUEST_RESPONSE_TRACING.md | 3000w | Code traces | ✅ Complete |
| REDIS_COMPREHENSIVE_GUIDE.md | 5586w | Redis deep dive | ✅ Complete |
| **Total:** | **~27,000 words** | Full documentation | ✅ Complete |

---

## **🎓 Knowledge Map: What You Can Explain Now**

### **Can Explain (Fluently)**
- [ ] Complete order creation flow
- [ ] Why REDIS_LUA_WITH_COMPENSATION works
- [ ] How Lua scripts guarantee atomicity
- [ ] What idempotency key prevents
- [ ] Why Redis cache can drift from MySQL
- [ ] 5-layer architecture and dependencies
- [ ] All 21 API endpoints
- [ ] Compensation pattern for distributed transactions

### **Partially Understand**
- [ ] MySQL transaction isolation levels (need deeper dive)
- [ ] Query performance optimization (need benchmarking)
- [ ] JMeter benchmark interpretation (need hands-on)
- [ ] Distributed systems CAP theorem (need theory)

### **Not Yet Covered**
- [ ] Database indexing strategy
- [ ] Query execution plans (EXPLAIN)
- [ ] Load testing and performance tuning
- [ ] Deployment procedures (Docker, K8s)
- [ ] Monitoring and alerting (Prometheus, Grafana)
- [ ] Production incident response

---

## **✅ Skills You've Acquired**

### **Technical Skills**
- ✅ Code navigation (find any file in codebase)
- ✅ Request tracing (from HTTP to DB)
- ✅ Architecture understanding (5 layers, dependencies)
- ✅ Pattern recognition (strategy pattern, compensation pattern)
- ✅ Concurrent system analysis (race conditions, atomicity)
- ✅ Redis mechanics (cache, Lua, locks, idempotency)

### **Learning Skills**
- ✅ Reading unfamiliar code systematically
- ✅ Creating documentation from analysis
- ✅ Building conceptual models
- ✅ Making comparisons (4 strategies, 4 Redis types)

---

## **🚀 Phase 2: Deep Dives (Next Steps)**

### **Recommended Order:**

#### **Option A: Database Deep Dive (2 weeks)**
- MySQL transaction isolation levels
- Query optimization (EXPLAIN plans)
- Index design for concurrent writes
- Partitioned table strategy
- **Outcome:** Understand why CONDITIONAL_DB works

**Start by reading:**
```
app/backend/xxxx-domain/src/.../TickerOrderDomainService.java
app/backend/xxxx-application/.../ConditionalDbStockDeductionStrategy.java
```

#### **Option B: Load Testing (1-2 weeks)** ⭐ Recommended
- JMeter benchmark setup
- Running load tests
- Analyzing results
- Comparing 4 strategies
- **Outcome:** See systems behavior under load

**Start by running:**
```bash
cd benchmark
./run-jmeter.ps1
cat results/*/aggregate.csv
```

#### **Option C: Distributed Systems Theory (2-3 weeks)**
- Saga pattern deep dive
- CAP theorem
- Eventual consistency
- Distributed transactions
- **Outcome:** Understand why design choices work

**Start by reading:**
```
docs/REDIS_COMPREHENSIVE_GUIDE.md (Compensation section)
docs/STOCK_STRATEGIES.md (REDIS_LUA_WITH_COMPENSATION strategy)
```

#### **Option D: Deployment & Operations (2-3 weeks)**
- Docker Compose setup
- Multi-container networking
- Monitoring (Prometheus, Grafana)
- Health checks
- **Outcome:** Able to operate the system

**Start by exploring:**
```
environment/docker-compose-dev.yml
environment/docker-compose-nginx.yml
environment/prometheus/prometheus.yml
```

---

## **📈 Learning Progress Tracker**

### **Phase 1 Completion: 100% ✅**

```
Foundation Knowledge
├─ API Contracts                          ✅ 100%
├─ Architecture Patterns                  ✅ 100%
├─ Stock Deduction Strategies             ✅ 100%
├─ Redis Types & Usage                    ✅ 100%
├─ Code Navigation & Tracing              ✅ 100%
├─ Error Handling                         ✅ 80%
└─ Documentation Reading                  ✅ 100%

Overall: ✅✅✅✅✅ EXCELLENT FOUNDATION
```

### **Readiness for Phase 2: ✅ Ready**

```
Prerequisites Met?
├─ Understand architecture                ✅ YES
├─ Know code structure                    ✅ YES
├─ Can trace requests                     ✅ YES
├─ Understand strategies                  ✅ YES
└─ Ready for deep technical topics        ✅ YES

Recommendation: Proceed to Phase 2 Deep Dives
```

---

## **💡 Key Learnings (Memorable Insights)**

### **Insight #1: Atomicity Matters**
```
UNSAFE_DB:     No WHERE clause → Race condition
CONDITIONAL_DB: WHERE stock >= qty → Atomic check-and-deduct
REDIS_LUA:     Lua script → Atomic in single Redis command
```
**Lesson:** Without atomic operations, concurrent writes destroy consistency.

---

### **Insight #2: Compensation Is Critical**
```
Step 1: Redis deduct ✓
Step 2: MySQL update ❌
  →  Undo Step 1: Redis INCR (compensation)
  
Without compensation → Redis and MySQL disagree forever!
```
**Lesson:** In distributed transactions, always plan your backout strategy.

---

### **Insight #3: Cache Creates Drift**
```
Redis stock: 5000 (fast read)
MySQL stock: 5001 (authoritative, but slow)

30-second reconciliation fixes drift automatically.
```
**Lesson:** Eventual consistency acceptable if reconciliation exists.

---

### **Insight #4: Idempotency Prevents Disasters**
```
Client: POST /orders (timeout, so retry)
Server: First request succeeds, stored in idempotency cache
Client: Second request returns same response (no duplicate order!)
```
**Lesson:** Idempotency key prevents duplicate processing on retries.

---

### **Insight #5: Partitioning Scales Queries**
```
ticket_order_202606: Only Jan 2026 orders (smaller table)
ticket_order_202607: Only Feb 2026 orders (smaller table)

Query Jan orders: Only scans Jan partition (10x faster!)
```
**Lesson:** Partitioning by time enables scalable queries.

---

## **📚 Recommended Reading Order (Next Session)**

1. **MySQL Isolation Levels** (30 min read)
   - File: None yet, need to create
   - Understanding: Why CONDITIONAL_DB uses WHERE clause

2. **Partitioned Tables Deep Dive** (30 min read)
   - File: None yet, need to research
   - Understanding: Why monthly tables help concurrency

3. **JMeter Basics** (1 hour hands-on)
   - File: `benchmark/flash-sale-order.jmx`
   - Activity: Run benchmark, analyze results

4. **Load Test Results Analysis** (1 hour)
   - File: `benchmark/results/*/`
   - Activity: Compare 4 strategies

5. **Saga Pattern Deep Dive** (1 hour read)
   - File: `docs/REDIS_COMPREHENSIVE_GUIDE.md` (Compensation section)
   - Understanding: Multi-step distributed transactions

---

## **❓ Questions You Can Answer Now**

### **Easy (After Phase 1)**
- [ ] "What are the 4 stock deduction strategies?" → Can explain all 4
- [ ] "Why use Redis instead of MySQL?" → Speed + cache layer
- [ ] "How does Lua guarantee atomicity?" → Single Redis command
- [ ] "What's idempotency?" → Duplicate request handling
- [ ] "Why partition tables monthly?" → Better query performance

### **Medium (Need Phase 2)**
- [ ] "Why is CONDITIONAL_DB slow?" → MySQL lock overhead
- [ ] "How do you scale this to 100K users?" → Partitioning, clustering
- [ ] "What happens if Redis crashes?" → Fallback to MySQL
- [ ] "How fast is the system?" → Need JMeter benchmarks

### **Advanced (Need Phase 2+)**
- [ ] "Design a distributed cache system" → Apply learned patterns
- [ ] "Fix a race condition in production" → Use compensation pattern
- [ ] "Optimize slow queries" → Need EXPLAIN analysis
- [ ] "Handle Redis cluster failover" → Need Sentinel knowledge

---

## **🎯 Your Learning Objectives Achieved**

✅ **Session Goal:** "Hiểu rõ flow của API, architecture, Redis types"

**Deliverables:**
- ✅ Understood all 21 API flows
- ✅ Mastered 5-layer architecture
- ✅ Learned 4 Redis types with distinct roles
- ✅ Created 10 documentation files (27,000 words)
- ✅ Built mental model of entire system
- ✅ Can trace any request through codebase
- ✅ Understand trade-offs (speed vs correctness)

**Confidence Level:** ⭐⭐⭐⭐⭐ (Ready for deep dives)

---

## **📝 Next Session: What to Focus On**

### **Option 1: Database Optimization (2 weeks)**
- Learn MySQL isolation levels
- Understand index strategy
- Optimize query performance
- **Then:** Benchmark CONDITIONAL_DB improvements

### **Option 2: Load Testing (1 week)** ⭐ **RECOMMENDED START**
- Run JMeter benchmarks
- Compare all 4 strategies
- Identify bottlenecks
- **Then:** Understand results

### **Option 3: Deployment (2 weeks)**
- Docker Compose deep dive
- Set up monitoring
- Create runbook
- **Then:** Deploy to cloud

### **Option 4: Advanced Patterns (2-3 weeks)**
- Study Saga pattern
- Learn CAP theorem
- Design similar system
- **Then:** Build new features

---

## **Summary Score Card**

| Category | Understanding | Hands-On | Ready for Phase 2? |
|----------|---------------|-----------|--------------------|
| Architecture | ⭐⭐⭐⭐⭐ | ⭐⭐⭐⭐ | ✅ YES |
| API Design | ⭐⭐⭐⭐⭐ | ⭐⭐⭐⭐ | ✅ YES |
| Redis Concepts | ⭐⭐⭐⭐⭐ | ⭐⭐⭐⭐ | ✅ YES |
| Strategies | ⭐⭐⭐⭐⭐ | ⭐⭐⭐⭐ | ✅ YES |
| Code Navigation | ⭐⭐⭐⭐⭐ | ⭐⭐⭐⭐⭐ | ✅ YES |
| Concurrency | ⭐⭐⭐⭐ | ⭐⭐⭐ | ✅ YES (need hands-on) |
| Database | ⭐⭐⭐ | ⭐⭐ | ⚠️ NEED (Phase 2) |
| Performance | ⭐⭐⭐ | ⭐ | ⚠️ NEED (Phase 2) |
| Deployment | ⭐⭐ | ⚪ | ⚠️ NEED (Phase 2) |

---

## **🏆 Conclusion**

You've completed **Phase 1: Foundation** with excellent understanding.

**You Now Know:**
1. How the system works (end-to-end)
2. Why each component exists
3. Trade-offs between design choices
4. How to navigate the codebase
5. Why compensation pattern matters

**You're Ready For:**
- Deep technical dives
- Performance optimization
- Production operations
- Building similar systems

**Next Step:** Choose one of Phase 2 deep dives and commit 1-2 weeks!

---

**Session Complete:** May 30, 2026 ✅  
**Next Review:** After Phase 2 completion (2-3 weeks)

