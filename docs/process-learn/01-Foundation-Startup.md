# Phase 1: Foundation - Learning Guide

**Timeline:** 1 session (~8 hours, May 30)  
**Status:** ✅ COMPLETE  
**Goal:** Master API contracts, architecture, strategies, and Redis patterns  
**Next Phase:** [Phase 2 - Database Deep Dive](PHASE2-DATABASE-DEEP-DIVE.md)

---

## 📚 **Phase 1 Learning Path (Recommended Reading Order)**

This guide organizes the core reference documents into a structured learning journey. All materials are in the main `docs/` folder.

---

## 🎯 **Day 1 Morning: Foundation & Architecture (2-3 hours)**

### **Step 1: Understand the System at a Glance (15 min)**

**Read:** [docs/REVIEWER_GUIDE.md](../REVIEWER_GUIDE.md)

**What you'll learn:**
- [ ] Project purpose: Flash-sale concurrency backend lab
- [ ] Business domain: Ticket stock deduction under high load
- [ ] Key problem: Ensure stock never oversells while maintaining high throughput
- [ ] Solution overview: 4 strategy comparison (UNSAFE → REDIS_LUA_WITH_COMPENSATION)

**Key takeaway:** "Why does this project matter? What problem does it solve?"

---

### **Step 2: Learn the 5-Layer Architecture (45 min)**

**Read:** [docs/ARCHITECTURE.md](../ARCHITECTURE.md)

**Focus on these sections:**
- Module Layout (what each folder does)
- Runtime Stack (Java, Spring Boot, databases)
- Main Order Flow (the 10-step request path)
- Storage Model (MySQL tables)

**What you'll learn:**
- [ ] Why 5-layer architecture (separation of concerns)
- [ ] Dependencies flow (top-down)
- [ ] Where to find each piece of code
- [ ] How request flows through all layers

**Key takeaway:** "I can navigate the codebase and know where to find anything"

---

### **Step 3: Master the 21 API Endpoints (30 min)**

**Read:** [docs/API_REFERENCE.md](../API_REFERENCE.md)

**Focus on these sections:**
- Response Envelope (how all APIs respond)
- Supported Strategies (4 options)
- Create Order API (POST /orders - the main endpoint)
- List Orders & Get Order (GET endpoints)

**What you'll learn:**
- [ ] All 21 HTTP endpoints
- [ ] Request/response structure
- [ ] Success vs error codes (200, 400, 409)
- [ ] Strategy parameter meanings

**Key takeaway:** "I can call any API and understand what it does"

---

## 🎯 **Day 1 Afternoon: Strategies & Concurrency (2-3 hours)**

### **Step 4: Compare Stock Deduction Strategies (1 hour)**

**Read:** [docs/STOCK_STRATEGIES.md](../STOCK_STRATEGIES.md)

**Key sections:**
- Strategy Overview (comparison table)
- UNSAFE_DB (fast but oversells - baseline for understanding race conditions)
- CONDITIONAL_DB (slow but safe - atomic WHERE clause)
- REDIS_LUA (fast but drifts - gate without compensation)
- REDIS_LUA_WITH_COMPENSATION (fast AND safe - recommended)

**What you'll learn:**
- [ ] Why UNSAFE_DB oversells (no race condition protection)
- [ ] How CONDITIONAL_DB prevents overselling (WHERE clause atomicity)
- [ ] Why Redis faster than MySQL (in-memory)
- [ ] What compensation pattern means (restore on failure)
- [ ] Trade-offs: Speed vs Safety vs Consistency

**Key exercises:**
- [ ] Can you explain why UNSAFE_DB fails?
- [ ] Can you trace how CONDITIONAL_DB prevents overselling?
- [ ] Why is compensation important?

**Key takeaway:** "I understand distributed transaction failures and solutions"

---

### **Step 5: Learn 4 Redis Patterns (1 hour)**

**Read:** [docs/REDIS_COMPREHENSIVE_GUIDE.md](../REDIS_COMPREHENSIVE_GUIDE.md)

**Must-read sections:**
- Overview: Why Redis? (speed, atomicity, patterns)
- Type 1: REDIS CACHE (simple GET/SET stock storage)
- Type 2: REDIS LUA SCRIPT (atomic operations, test gate)
- Type 3: REDISSON LOCK (distributed coordination)
- Type 4: IDEMPOTENCY CACHE (retry deduplication)
- Comparison Table (when to use each)

**What you'll learn:**
- [ ] Why use Redis instead of MySQL directly?
- [ ] What Lua script guarantees (atomicity in single command)
- [ ] How Redisson lock prevents duplicate resets
- [ ] How idempotency cache prevents double-charging on retries
- [ ] The 4 distinct roles Redis plays in this system

**Key exercises:**
- [ ] Which Redis type is used in each strategy?
- [ ] Why is Lua atomic when normal Redis commands aren't?
- [ ] How would the system break without idempotency cache?

**Key takeaway:** "I know 4 ways to use Redis and when each is appropriate"

---

## 🎯 **Day 2 Morning: Code Tracing (2-3 hours)**

### **Step 6: Trace Request-to-Response Flow (1.5 hours)**

**Read:** [docs/REQUEST_RESPONSE_TRACING.md](../REQUEST_RESPONSE_TRACING.md)

**Read these examples:**
- [ ] POST /orders (UNSAFE_DB) - trace all 10 steps
- [ ] POST /orders (REDIS_LUA_WITH_COMPENSATION) - understand compensation
- Pick 2-3 more traces and follow them

**What you'll learn:**
- [ ] Every method call in the request path
- [ ] Parameters passed between layers
- [ ] Where Redis is called, where MySQL is called
- [ ] How response is built and returned

**Exercise:**
- [ ] Pick one trace (e.g., CONDITIONAL_DB)
- [ ] Follow it line-by-line in the code
- [ ] Verify it matches the trace document
- [ ] Note where atomicity checks happen

**Key takeaway:** "I can follow any request through the entire codebase"

---

### **Step 7: Visualize with Sequence Diagrams (45 min)**

**Read:** [docs/SEQUENCE_DIAGRAMS.md](../SEQUENCE_DIAGRAMS.md)

**Read these diagrams:**
- [ ] POST /orders (UNSAFE_DB) - see race condition visually
- [ ] POST /orders (CONDITIONAL_DB) - see locking behavior
- [ ] POST /orders (REDIS_LUA_WITH_COMPENSATION) - see compensation flow
- [ ] GET /orders/{orderNumber} - simple query flow

**What you'll learn:**
- [ ] Visual representation of parallel requests
- [ ] Where race conditions occur (UNSAFE_DB)
- [ ] How locking prevents race conditions (CONDITIONAL_DB)
- [ ] How compensation works (failure path recovery)

**Key takeaway:** "I can visualize concurrent request behavior"

---

## 🎯 **Day 2 Afternoon: Business Flow & Edge Cases (2-3 hours)**

### **Step 8: Understand Business Context (45 min)**

**Read:** [docs/BUSINESS_FLOW.md](../BUSINESS_FLOW.md)

**Focus on:**
- Order lifecycle (created → success/failed)
- Stock inventory model (tickets, items, available stock)
- User/Idempotency context (same user, same key = same order)

**What you'll learn:**
- [ ] Why tickets have monthly tables (performance)
- [ ] How order numbering works (uniqueness)
- [ ] Business rules for stock validation

**Key takeaway:** "I understand the business problem, not just the technical solution"

---

### **Step 9: Master Error Handling (30 min)**

**Read:** [docs/ERRORS_AND_EDGE_CASES.md](../ERRORS_AND_EDGE_CASES.md)

**Focus on:**
- [ ] Race condition example (what happens, why)
- [ ] Stock depletion handling
- [ ] Distributed transaction failures
- [ ] Idempotency preventing double orders
- [ ] Consistency drift between Redis and MySQL

**What you'll learn:**
- [ ] Common failure modes in each strategy
- [ ] How system recovers (or doesn't)
- [ ] When HTTP 409 Conflict is returned
- [ ] Reconciliation to fix drift

**Key exercises:**
- [ ] If MySQL crashes mid-transaction, what happens?
- [ ] If Redis deduct succeeds but MySQL fails, how to recover?
- [ ] What prevents duplicate orders on client retry?

**Key takeaway:** "I understand failure modes and recovery mechanisms"

---

## 📋 **Learning Checklist - Can You Explain These?**

After completing Phase 1, you should be able to explain:

### **Architecture & Design**
- [ ] 5-layer DDD pattern and why each layer exists
- [ ] How controller passes to service to domain to infrastructure
- [ ] Folder structure and where to find code

### **Strategies & Trade-offs**
- [ ] All 4 strategies (UNSAFE, CONDITIONAL, REDIS_LUA, REDIS_LUA_WITH_COMPENSATION)
- [ ] Why UNSAFE_DB oversells
- [ ] Why CONDITIONAL_DB is slow
- [ ] What compensation pattern means
- [ ] Trade-offs: speed vs safety vs consistency

### **APIs & Contracts**
- [ ] All 21 endpoints (or at least the main ones)
- [ ] Request envelope (what parameters are needed)
- [ ] Response envelope (success/error structure)
- [ ] HTTP status codes (200, 400, 409)

### **Redis & Caching**
- [ ] 4 distinct Redis patterns and their purpose
- [ ] Why Lua script is atomic
- [ ] How idempotency prevents duplicate processing
- [ ] Cache vs database consistency

### **Request Flow**
- [ ] 10 steps from HTTP request to response
- [ ] Which layer calls which
- [ ] Where Redis is involved
- [ ] Where MySQL is involved
- [ ] How response is built

### **Failure Scenarios**
- [ ] Race conditions in UNSAFE_DB
- [ ] Locking in CONDITIONAL_DB
- [ ] Redis-DB drift in REDIS_LUA
- [ ] Compensation in REDIS_LUA_WITH_COMPENSATION
- [ ] How to recover from failures

---

## 🎯 **Next Steps**

### **If you complete Phase 1 successfully:**

1. **Take a break** (you've learned a lot!)
2. **Review what confused you** (re-read those sections)
3. **Ask questions** if anything is unclear
4. **Proceed to Phase 2:** [Database Deep Dive](PHASE2-DATABASE-DEEP-DIVE.md)

### **Phase 2 will teach:**
- Isolation levels in MySQL
- Why CONDITIONAL_DB uses WHERE clause
- Index strategies for performance
- Table partitioning for scalability

### **Then Phase 3:**
- JMeter benchmarking
- Measuring throughput, latency, safety
- Comparing 4 strategies empirically
- Creating reproducible results

---

## 📚 **Reference: All Phase 1 Documents**

**Main Reference Materials:**

| Document | Size | Purpose | Read Time |
|----------|------|---------|-----------|
| [REVIEWER_GUIDE.md](../REVIEWER_GUIDE.md) | 2500w | Project overview, CV-safe story | 15 min |
| [ARCHITECTURE.md](../ARCHITECTURE.md) | 3000w | Technical design, 5-layer pattern | 45 min |
| [API_REFERENCE.md](../API_REFERENCE.md) | 2500w | 21 endpoints, contracts | 30 min |
| [STOCK_STRATEGIES.md](../STOCK_STRATEGIES.md) | 4000w | 4 strategies, trade-offs | 1 hour |
| [REDIS_COMPREHENSIVE_GUIDE.md](../REDIS_COMPREHENSIVE_GUIDE.md) | 5586w | 4 Redis patterns, usage | 1.5 hours |
| [REQUEST_RESPONSE_TRACING.md](../REQUEST_RESPONSE_TRACING.md) | 3000w | Code traces, execution paths | 1.5 hours |
| [SEQUENCE_DIAGRAMS.md](../SEQUENCE_DIAGRAMS.md) | 2000w | Visual flow diagrams | 45 min |
| [BUSINESS_FLOW.md](../BUSINESS_FLOW.md) | 2000w | Domain context, business rules | 30 min |
| [ERRORS_AND_EDGE_CASES.md](../ERRORS_AND_EDGE_CASES.md) | 2500w | Failure modes, recovery | 45 min |
| **Total** | **~27,000 words** | Complete Phase 1 coverage | **~7-8 hours** |

---

## 💡 **Pro Tips**

**🎯 Learning Strategy:**
1. Read in recommended order (top to bottom)
2. Don't try to memorize everything on first read
3. Take notes on confusing parts
4. Later phases will reinforce concepts

**🔍 Code Verification:**
- After each section, try to find the code in the IDE
- Open `TicketOrderController.java`, follow method calls
- Verify request paths match the traces

**❓ If You Get Stuck:**
- Re-read ARCHITECTURE.md (most foundational)
- Look at SEQUENCE_DIAGRAMS.md (visual helps)
- Read REQUEST_RESPONSE_TRACING.md (concrete examples)
- These three docs explain 80% of understanding

**⏱️ Time Management:**
- Morning: Architecture + Strategies (3 hours)
- Afternoon: Code Tracing + Business (3 hours)
- Break between: 1 hour
- Total: ~6-8 hours for complete Phase 1

---

## ✅ **Success Criteria**

After Phase 1, you can confidently:

- [ ] **Explain the system** to someone who's never seen it (5 min elevator pitch)
- [ ] **Navigate the code** (find any file/method in 30 seconds)
- [ ] **Trace a request** (follow from API to database)
- [ ] **Compare strategies** (explain trade-offs)
- [ ] **Discuss trade-offs** (speed vs safety vs consistency)
- [ ] **Identify failure modes** (what breaks and why)

---

## 🚀 **Ready for Phase 2?**

When you're ready, proceed to: **[Phase 2: Database Deep Dive](PHASE2-DATABASE-DEEP-DIVE.md)**

Phase 2 dives deeper into the theory that Phase 1 introduced, with focus on:
- Why CONDITIONAL_DB uses WHERE clause
- MySQL isolation levels
- Index strategies
- Partitioning for scalability

---

**Status:** ✅ Phase 1 COMPLETE  
**Duration:** ~8 hours (May 30, 2026)  
**Difficulty:** ⭐⭐ (foundational reading)  
**Next:** Phase 2 - Database Deep Dive

