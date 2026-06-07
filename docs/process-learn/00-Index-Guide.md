# 🎯 Complete 7-Phase Learning Plan Index

**Project:** Flash-Sale-Concurrency-Engine  
**Duration:** May 30 - July 19, 2026 (7 weeks)  
**Total Effort:** ~200+ hours  
**Career Outcome:** Interview-ready portfolio + backend engineer fundamentals

---

## 📚 **Quick Reference: All Phase Guides**

| Phase | Timeline | Focus | Deliverable | Status | Guide |
|-------|----------|-------|------------|--------|-------|
| **Phase 1** | May 30 | Foundation | API + Architecture | ✅ Complete | [LEARNING_JOURNEY.md](LEARNING_JOURNEY.md#phase-1-foundation) |
| **Phase 2** | May 30 | Database | Concurrency & Consistency | ✅ Complete | [PHASE2-DATABASE-DEEP-DIVE.md](PHASE2-DATABASE-DEEP-DIVE.md) |
| **Phase 3** | May 31 | Benchmarking | Benchmark Results Analysis | ✅ Complete | [BENCHMARK_RESULTS_ANALYSIS.md](../BENCHMARK_RESULTS_ANALYSIS.md) |
| **Phase 4** | Jun 8-25 | Specialization | Path-Specific Expertise | 📋 Ready | [PHASE4_STARTUP.md](../PHASE4_STARTUP.md) |
| **Phase 5** | Jun 22-28 | Optimization | Optimization Report | 📋 Ready | [PHASE5_LEARNING_GUIDE.md](PHASE5_LEARNING_GUIDE.md) / [Startup](../PHASE5_STARTUP.md) |
| **Phase 6** | Jun 29-Jul 12 | Architecture | Advanced Patterns Doc | 📋 Ready | [PHASE6_LEARNING_GUIDE.md](PHASE6_LEARNING_GUIDE.md) / [Startup](../PHASE6_STARTUP.md) |
| **Phase 7** | Jul 13-19 | Portfolio | GitHub + Interview Stories | 📋 Ready | [PHASE7_LEARNING_GUIDE.md](PHASE7_LEARNING_GUIDE.md) / [Startup](../PHASE7_STARTUP.md) |

---

## 🗺️ **Learning Progression Map**

```
FOUNDATIONS (May 30)
┌─ Phase 1: Core Concepts
│  ├─ 21 API endpoints
│  ├─ 5-layer architecture
│  ├─ 4 stock deduction strategies
│  ├─ 4 Redis usage patterns
│  └─ Code navigation & tracing
│
├─ Phase 2: Database Theory
│  ├─ Isolation levels
│  ├─ Atomic operations
│  ├─ Index optimization
│  ├─ Table partitioning
│  └─ Consistency guarantees
│
└─ Goal: 100% Foundation Understanding
   Status: ✅ COMPLETE

VALIDATION & SPECIALIZATION (May 31 - Jun 21)
┌─ Phase 3: Load Testing
│  ├─ JMeter benchmarking
│  ├─ 4-strategy comparison
│  ├─ Throughput & latency metrics
│  ├─ Safety & consistency validation
│  └─ Empirical data collection
│
├─ Phase 4: Choose Your Path (Select ONE)
│  ├─ Path A: Operations & DevOps (2-3 weeks)
│  ├─ Path B: Distributed Systems Architecture (2-3 weeks)
│  └─ Path C: Performance Optimization (2-3 weeks)
│
└─ Goal: Data-Driven Specialization
   Status: ⏳ IN PROGRESS (Phase 3 running)

PRODUCTION ENGINEERING (Jun 22 - Jul 12)
┌─ Phase 5: Optimization & Deployment
│  ├─ Bottleneck analysis
│  ├─ MySQL optimization
│  ├─ Redis tuning
│  ├─ JVM profiling
│  ├─ Docker optimization
│  └─ Monitoring setup
│
├─ Phase 6: Advanced Architecture
│  ├─ Event-driven patterns
│  ├─ CQRS (read/write separation)
│  ├─ Saga pattern (distributed transactions)
│  ├─ Microservices extraction
│  └─ Distributed consensus
│
└─ Goal: Production-Grade System + Architectural Thinking
   Status: 📋 PLANNED (starts Jun 22)

PORTFOLIO & INTERVIEWS (Jul 13-19)
┌─ Phase 7: Career Readiness
│  ├─ GitHub polish & optimization
│  ├─ CV bullets with metrics
│  ├─ Interview stories (STAR format)
│  ├─ Demo video walkthrough
│  ├─ Release v1.0 & publishing
│  ├─ LinkedIn profile update
│  └─ Interview preparation
│
└─ Goal: Interview-Ready Portfolio Project
   Status: 📋 PLANNED (starts Jul 13)
```

---

## 📖 **What Each Phase Teaches**

### **Phase 1: Foundation (API, Architecture, Strategies)**

**Learning Objectives:**
- [ ] Understand all 21 API endpoints
- [ ] Navigate 5-layer DDD architecture
- [ ] Compare 4 stock deduction strategies (UNSAFE → REDIS_LUA_WITH_COMPENSATION)
- [ ] Learn 4 distinct Redis patterns (cache, Lua, locks, idempotency)
- [ ] Trace requests from HTTP to database
- [ ] Identify code locations for any feature

**Key Questions You Can Answer:**
- "Why use Redis instead of MySQL?" → Atomicity + speed
- "How does REDIS_LUA_WITH_COMPENSATION prevent overselling?" → Compensation pattern
- "What's idempotency key for?" → Duplicate request prevention
- "How are the 4 strategies different?" → Can explain trade-offs

**Time:** 1 session (~8 hours)  
**Difficulty:** ⭐⭐ (foundational reading)  
**Status:** ✅ COMPLETE

---

### **Phase 2: Database Theory (Isolation, Atomicity, Partitioning)**

**Learning Objectives:**
- [ ] Understand MySQL isolation levels (READ UNCOMMITTED → SERIALIZABLE)
- [ ] Know how atomic operations prevent race conditions
- [ ] Learn index types and optimization strategies
- [ ] Understand table partitioning for scalability
- [ ] Explain consistency guarantees in each strategy

**Key Questions You Can Answer:**
- "Why is CONDITIONAL_DB slow?" → WHERE clause forces sequential check-and-deduct
- "How does index help?" → Reduces table scans from O(n) to O(log n)
- "Why partition tables monthly?" → Smaller partitions = faster queries
- "What's the trade-off between safety and speed?" → ACID vs performance

**Time:** 1 session (~8 hours)  
**Difficulty:** ⭐⭐⭐ (theoretical concepts)  
**Status:** ✅ COMPLETE

---

### **Phase 3: Load Testing & Validation (Benchmarking)**

**Learning Objectives:**
- [ ] Set up and run JMeter benchmarks
- [ ] Compare 4 strategies empirically
- [ ] Measure throughput (req/ms), latency (P95/P99), error rates
- [ ] Validate safety: zero oversells, consistent Redis-DB
- [ ] Create reproducible benchmark results
- [ ] Interpret benchmark data meaningfully

**Key Metrics You'll Collect:**
```
Strategy               Throughput  Latency(P95)  Oversells  Redis-DB Drift
UNSAFE_DB             350 req/ms   2.8ms         YES        N/A
CONDITIONAL_DB        38 req/ms    26ms          NO         N/A
REDIS_LUA             354 req/ms   2.8ms         NO         2-5
REDIS_LUA_WITH_...    354 req/ms   2.8ms         NO         0
```

**Time:** 1 week (May 31 - June 7)  
**Difficulty:** ⭐⭐⭐⭐ (hands-on execution)  
**Status:** ⏳ IN PROGRESS

---

### **Phase 4: Strategic Specialization (Choose 1 Path)**

**Three Career Paths - Choose One:**

#### **Path A: Operations & Deployment → DevOps/SRE**
- Master Docker, Kubernetes (optional)
- Set up Prometheus + Grafana monitoring
- Create operational runbooks
- Implement health checks & alerting
- **Deliverable:** `docs/OPERATIONS_RUNBOOK.md`
- **Duration:** 2-3 weeks
- **Career:** Backend Engineer → DevOps/SRE
- **Interview Value:** ⭐⭐⭐⭐

#### **Path B: Distributed Systems & Architecture → Architect**
- Understand distributed transaction patterns
- Master CAP theorem & consistency models
- Study saga pattern with compensation
- Learn eventual consistency strategies
- **Deliverable:** `docs/DISTRIBUTED_SYSTEMS_PATTERNS.md`
- **Duration:** 2-3 weeks
- **Career:** Backend Engineer → Architect
- **Interview Value:** ⭐⭐⭐⭐⭐

#### **Path C: Performance Optimization → Performance Specialist**
- Database query optimization (EXPLAIN, indexes)
- Redis optimization (memory, connection pooling)
- JVM tuning (GC, heap management)
- Benchmark improvements & profiling
- **Goal:** Optimize from 354 → 1000+ req/ms (3-5x improvement)
- **Deliverable:** `docs/PERFORMANCE_OPTIMIZATION_REPORT.md`
- **Duration:** 2-3 weeks
- **Career:** Backend Engineer → Performance Specialist
- **Interview Value:** ⭐⭐⭐⭐

**Time:** 2-3 weeks (Jun 8-21)  
**Difficulty:** ⭐⭐⭐⭐ (deep technical dives)  
**Status:** 📋 PLANNED

---

### **Phase 5: Real-World Optimization & Deployment**

**Learning Objectives:**
- [ ] Analyze benchmark data to identify bottlenecks
- [ ] Optimize MySQL (add indexes, improve queries)
- [ ] Tune Redis (connection pooling, memory settings)
- [ ] Profile JVM (garbage collection, heap)
- [ ] Optimize Docker (resource limits, networking)
- [ ] Implement monitoring (Prometheus + Grafana)
- [ ] Measure cumulative improvement (target: 3-5x)

**Day-by-Day Tasks:**
- Day 1: Bottleneck analysis
- Day 2: MySQL optimization (+30%)
- Day 3: Redis tuning (+25%)
- Day 4: JVM profiling (+40%)
- Day 5: Docker & container optimization
- Day 6: Monitoring & dashboards
- Day 7: Documentation & synthesis

**Time:** 1 week (Jun 22-28)  
**Difficulty:** ⭐⭐⭐⭐ (applied engineering)  
**Status:** 📋 PLANNED

---

### **Phase 6: Advanced Patterns & Scaling**

**Learning Objectives:**
- [ ] Implement event-driven architecture (loose coupling)
- [ ] Apply CQRS pattern (read model optimization)
- [ ] Design saga pattern (distributed transactions)
- [ ] Extract microservices (Payment, Notification)
- [ ] Implement distributed consensus (leader election)
- [ ] Handle network partitions & split-brain

**Architecture Evolution:**
```
Monolith
  ↓ (Phase 6)
Event-Driven Monolith
  ├─ Publishers: OrderCreatedEvent
  └─ Subscribers: Analytics, Email, Notifications
  ↓
CQRS Pattern
  ├─ Write Model: MySQL (order_write)
  └─ Read Model: order_read_model (optimized)
  ↓
Microservices
  ├─ Stock Service
  ├─ Payment Service (NEW)
  ├─ Notification Service (NEW)
  └─ Analytics Service
  ↓
Distributed Consensus
  └─ Leader election for batch jobs
```

**Time:** 2 weeks (Jun 29 - Jul 12)  
**Difficulty:** ⭐⭐⭐⭐⭐ (advanced architecture)  
**Status:** 📋 PLANNED

---

### **Phase 7: Portfolio & Interview Mastery**

**Learning Objectives:**
- [ ] Polish GitHub repository (README, documentation, code)
- [ ] Write 6-8 CV bullets with quantified metrics
- [ ] Prepare 4 interview stories (5-10 minutes each)
- [ ] Record demo video (system walkthrough)
- [ ] Create v1.0 release with release notes
- [ ] Update LinkedIn & portfolio website
- [ ] Build interview preparation checklist

**Interview Stories You'll Tell:**

1. **The Challenge** (System Design)
   - Problem: Ensure stock never oversells under high concurrency
   - Action: Evaluated 4 strategies, chose REDIS_LUA_WITH_COMPENSATION
   - Result: 354 req/ms + zero oversells + auto-compensation
   - Learning: Trade-offs matter, data > guessing

2. **The Optimization** (Performance Tuning)
   - Problem: Optimize from 354 to 1000+ req/ms
   - Action: Profiled system, identified 3 bottlenecks, fixed systematically
   - Result: 3-5x improvement, documented tuning guide
   - Learning: Measure everything, focus on bottlenecks first

3. **The Architecture** (Distributed Systems)
   - Problem: Scale monolith to independent services
   - Action: Implemented events, CQRS, sagas, consensus
   - Result: Decoupled services, reliable distributed transactions
   - Learning: Event-driven powerful but requires eventual consistency thinking

4. **The Learning** (Growth Mindset)
   - Problem: Need to understand end-to-end system design
   - Action: Structured 7-phase learning (theory → validation → optimization → architecture)
   - Result: Comprehensive understanding, portfolio-ready project
   - Learning: Structured learning > random learning, documentation is key

**Time:** 1 week (Jul 13-19)  
**Difficulty:** ⭐⭐⭐ (soft skills + polish)  
**Status:** 📋 PLANNED

---

## 🎯 **Success Criteria by Phase**

### **Phase 1: ✅ Foundation Complete**
- [x] Can explain all 21 API flows
- [x] Understand 5-layer architecture
- [x] Know 4 stock strategies + trade-offs
- [x] Know 4 Redis patterns + usage
- [x] Can navigate codebase efficiently

### **Phase 2: ✅ Database Theory Complete**
- [x] Understand isolation levels
- [x] Know why WHERE clause prevents race conditions
- [x] Understand index optimization
- [x] Know table partitioning benefits
- [x] Can explain consistency guarantees

### **Phase 3: ⏳ Benchmarking In Progress**
- [ ] Run JMeter for all 4 strategies
- [ ] Collect throughput, latency, safety metrics
- [ ] Validate REDIS_LUA_WITH_COMPENSATION superiority
- [ ] Create BENCHMARK_RESULTS_ANALYSIS.md
- [ ] Have empirical data for Phase 5 optimizations

### **Phase 4: 📋 Specialization Planned**
- [ ] Choose ONE path (Ops/Architecture/Performance)
- [ ] Complete 2-3 week deep dive
- [ ] Create path-specific deliverable
- [ ] Develop specialization expertise
- [ ] Prepare talking points for interviews

### **Phase 5: 📋 Optimization Planned**
- [ ] Identify 3 key bottlenecks
- [ ] Implement MySQL, Redis, JVM optimizations
- [ ] Measure before/after for each
- [ ] Achieve 3-5x cumulative improvement
- [ ] Set up monitoring & dashboards

### **Phase 6: 📋 Architecture Planned**
- [ ] Implement event-driven architecture
- [ ] Apply CQRS pattern with read model
- [ ] Design saga with compensation
- [ ] Extract 2 microservices
- [ ] Implement distributed consensus

### **Phase 7: 📋 Portfolio Planned**
- [ ] GitHub repo is polished & professional
- [ ] 6-8 CV bullets with quantified results
- [ ] 4 interview stories practiced & documented
- [ ] Demo video recorded & embedded
- [ ] v1.0 released with documentation
- [ ] LinkedIn profile highlights project
- [ ] Ready for interviews

---

## 📈 **Cumulative Learning Path**

```
Day 1: Fundamentals (Phase 1)
  Knowledge: What is the system? How does it work?
  Confidence: ⭐⭐ (Lots to learn)
  
Day 2: Theory (Phase 2)
  Knowledge: Why does it work? What are trade-offs?
  Confidence: ⭐⭐⭐ (Starting to understand)
  
Days 3-10: Validation (Phase 3)
  Knowledge: Does it actually work? What's the real performance?
  Confidence: ⭐⭐⭐⭐ (Data validates theory)
  
Days 11-21: Specialization (Phase 4)
  Knowledge: Deep expertise in chosen area
  Confidence: ⭐⭐⭐⭐⭐ (Expert in one domain)
  
Days 22-28: Production Engineering (Phase 5)
  Knowledge: How to operate and optimize at scale
  Confidence: ⭐⭐⭐⭐⭐ (Achieved 3-5x improvement)
  
Days 29-43: Advanced Architecture (Phase 6)
  Knowledge: Distributed systems patterns, scalability
  Confidence: ⭐⭐⭐⭐⭐ (Can design complex systems)
  
Days 44-50: Portfolio Polish (Phase 7)
  Knowledge: How to tell your story effectively
  Confidence: ⭐⭐⭐⭐⭐⭐ (Interview-ready!)
```

---

## 💼 **Career Trajectory**

```
Before Phase 1: "I know some Java and REST APIs"
  ↓
After Phase 2: "I understand concurrent systems"
  ↓
After Phase 3: "I can measure and validate systems"
  ↓
After Phase 4: "I'm an expert in [Ops/Architecture/Performance]"
  ↓
After Phase 5: "I can optimize systems at scale"
  ↓
After Phase 6: "I can design distributed systems"
  ↓
After Phase 7: "I'm ready to interview for backend engineer roles"
  ↓
Potential Roles:
  ✅ Backend Engineer (Entry-level to Mid)
  ✅ Platform Engineer
  ✅ DevOps/SRE Engineer (if Path A)
  ✅ Solutions Architect (if Path B)
  ✅ Performance Engineer (if Path C)
```

---

## 🗂️ **File Organization**

```
docs/process-learn/
├─ LEARNING_JOURNEY.md              ← Main tracker (this phase system)
├─ PHASE1_STARTUP.md                ← [Completed]
├─ PHASE2-DATABASE-DEEP-DIVE.md     ← [Completed]
├─ PHASE3_STARTUP.md                ← [7-day benchmark guide]
├─ PHASE4_STARTUP.md                ← [3-path specialization guide]
├─ PHASE5_STARTUP.md                ← [1-week optimization guide]
├─ PHASE6_STARTUP.md                ← [2-week architecture guide]
└─ PHASE7_STARTUP.md                ← [1-week portfolio guide]

docs/
├─ REVIEWER_GUIDE.md                ← CV-safe project story
├─ ARCHITECTURE.md                  ← Technical design
├─ API_REFERENCE.md                 ← Endpoints
├─ CONCURRENCY_AND_CONSISTENCY.md   ← Patterns & strategies
├─ BENCHMARKING.md                  ← Testing procedures
├─ REDIS_COMPREHENSIVE_GUIDE.md     ← Redis deep dive
├─ OPTIMIZATION_AND_DEPLOYMENT_REPORT.md     ← [Phase 5 output]
├─ ADVANCED_PATTERNS_AND_ARCHITECTURE.md     ← [Phase 6 output]
└─ [And others...]
```

---

## 🎓 **How to Use This Index**

### **If you're starting Phase 3:**
1. Read this document for context (5 min)
2. Open [PHASE3_STARTUP.md](PHASE3_STARTUP.md) (30 min)
3. Follow Day 1 checklist (2-3 hours)

### **If you're in the middle of Phase 3:**
1. Check your current day in [PHASE3_STARTUP.md](PHASE3_STARTUP.md)
2. Follow the checklist for that day
3. Update task status (checkboxes)

### **If Phase 3 is complete, starting Phase 4:**
1. Review Phase 3 results in `benchmark/results/`
2. Choose your path (A, B, or C) in [PHASE4_STARTUP.md](PHASE4_STARTUP.md)
3. Follow chosen path day-by-day

### **If preparing for interviews (Phase 7):**
1. Open [PHASE7_STARTUP.md](PHASE7_STARTUP.md)
2. Follow Day 2-3 (write CV bullets + interview stories)
3. Practice your 4 stories out loud
4. Record demo video (Day 4)

---

## 📊 **Progress at a Glance**

```
May 30:  Phase 1-2 ✅ COMPLETE (Foundation + Database Theory)
May 31:  Phase 3 ⏳ STARTS (Benchmarking)
Jun 7:   Phase 3 ⏳ CONTINUE → Complete & analyze results
Jun 8:   Phase 4 📋 STARTS (Choose specialization path)
Jun 21:  Phase 4 ⏳ CONTINUE → Complete path-specific work
Jun 22:  Phase 5 📋 STARTS (Optimization & Deployment)
Jun 28:  Phase 5 ⏳ CONTINUE → Achieve 3-5x improvement
Jun 29:  Phase 6 📋 STARTS (Advanced Architecture)
Jul 12:  Phase 6 ⏳ CONTINUE → Master distributed patterns
Jul 13:  Phase 7 📋 STARTS (Portfolio & Interview Prep)
Jul 19:  Phase 7 ⏳ COMPLETE → INTERVIEW READY! 🎉
```

---

## ❓ **Common Questions**

**Q: Can I skip phases?**  
A: No. Each phase builds on previous. Phase 1-2 are mandatory. Phase 3 validates learning. Phase 4-7 build on Phase 3 results.

**Q: How much time per day?**  
A: 2-4 hours per day (varies by phase). Phase 3 is most time-intensive (benchmarking runs).

**Q: What if I fall behind?**  
A: Phases are suggestions, not deadlines. Extend as needed. Quality > speed.

**Q: Should I do all of Phase 4 paths?**  
A: No, choose ONE path (A, B, or C). You can do others after, but start with one deep dive.

**Q: Can I do Phase 6 & 7 without Phase 5?**  
A: Technically yes, but Phase 5 optimization is valuable portfolio addition. Recommended to include.

**Q: After Phase 7, what next?**  
A: Interview process! Use this project as primary talking point. Or extend with Phase 8+ features.

---

This index is the navigation entrypoint for the learning program. Use [00-Progress-Tracker.md](00-Progress-Tracker.md) for current learning progress and session notes.

