# 📚 Documentation Hub - Flash-Sale-Concurrency-Engine

Welcome to the documentation for Flash-Sale-Concurrency-Engine, a high-performance backend system demonstrating concurrent stock deduction with Redis/MySQL consistency, benchmarking, optimization, and distributed architecture patterns.

---

## 🎯 Quick Navigation by Role

### **For Interviewers & Reviewers** 👔
Start here → [Reference: Reviewer Guide](reference/REVIEWER_GUIDE.md)
- 5-10 minute project overview
- Key achievements with metrics
- Architecture summary
- Proof points for interviews

**Then read:**
- [Performance: Benchmark Results](performance/BENCHMARK_RESULTS_ANALYSIS.md)
- [Architecture: System Design](architecture/ARCHITECTURE.md)

---

### **For Backend Developers** 💻
Start here → [Architecture: System Design](architecture/ARCHITECTURE.md)
- 5-layer DDD architecture
- Module breakdown
- Request flow diagrams
- Data storage & cache boundaries

**Then read:**
- [Reference: API Reference](reference/API_REFERENCE.md) - All endpoints
- [Performance: Concurrency & Consistency](performance/CONCURRENCY_AND_CONSISTENCY.md) - Stock deduction strategies

---

### **For DevOps/SRE Engineers** 🚀
Start here → [Operations: Lab Operations](operations/LAB_OPERATIONS.md)
- Local environment setup
- Docker Compose orchestration
- Monitoring dashboards
- Deployment checklist

**Then read:**
- [Operations: Dashboard Guide](operations/DASHBOARD_GUIDE.md) - Metrics & alerts
- [Operations: Release Checklist](operations/RELEASE_CHECKLIST.md) - Verification

---

### **For Learning & Self-Study** 📖
Start here → [Learning Path Index](process-learn/PHASE_INDEX.md)
- 7-phase structured learning program
- 250+ hours of guided material
- Day-by-day checklists
- Progressive skill building

**Key guides:**
- [Phase 1: Foundation](process-learn/PHASE1_STARTUP.md)
- [Phase 5: Optimization](process-learn/PHASE5_LEARNING_GUIDE.md)
- [Phase 6: Distributed Systems](process-learn/PHASE6_LEARNING_GUIDE.md)
- [Phase 7: Portfolio & Interviews](process-learn/PHASE7_LEARNING_GUIDE.md)

---

## 📂 Documentation Organization

### **Architecture & Design** 🏛️
Deep understanding of system design and data flow

| Document | Purpose | Time |
|----------|---------|------|
| [ARCHITECTURE.md](architecture/ARCHITECTURE.md) | 5-layer DDD, modules, dependencies | 45 min |
| [BUSINESS_FLOW.md](architecture/BUSINESS_FLOW.md) | Business context and use cases | 30 min |
| [SEQUENCE_DIAGRAMS.md](architecture/SEQUENCE_DIAGRAMS.md) | Request flow visualizations | 30 min |
| [REQUEST_RESPONSE_TRACING.md](architecture/REQUEST_RESPONSE_TRACING.md) | Step-by-step code tracing | 1 hour |

**Use this folder to understand:** System structure, module relationships, request flow, API contracts

---

### **Performance & Optimization** ⚡
Understanding performance, benchmarking, and optimization

| Document | Purpose | Time |
|----------|---------|------|
| [BENCHMARKING.md](performance/BENCHMARKING.md) | JMeter setup, test execution, interpretation | 1.5 hours |
| [BENCHMARK_RESULTS_ANALYSIS.md](performance/BENCHMARK_RESULTS_ANALYSIS.md) | Phase 3 results, 4-strategy comparison | 45 min |
| [STOCK_STRATEGIES.md](performance/STOCK_STRATEGIES.md) | UNSAFE_DB vs CONDITIONAL_DB vs REDIS patterns | 1 hour |
| [CONCURRENCY_AND_CONSISTENCY.md](performance/CONCURRENCY_AND_CONSISTENCY.md) | Race conditions, compensation, reconciliation | 1.5 hours |
| [ERRORS_AND_EDGE_CASES.md](performance/ERRORS_AND_EDGE_CASES.md) | Failure modes and recovery | 45 min |

**Use this folder to understand:** Performance testing, optimization strategies, consistency guarantees, failure handling

---

### **Operations & Deployment** 🛠️
Deploying, monitoring, and running the system in production

| Document | Purpose | Time |
|----------|---------|------|
| [LAB_OPERATIONS.md](operations/LAB_OPERATIONS.md) | Local environment, Docker setup | 30 min |
| [DASHBOARD_GUIDE.md](operations/DASHBOARD_GUIDE.md) | Metrics dashboard, observability | 30 min |
| [RELEASE_CHECKLIST.md](operations/RELEASE_CHECKLIST.md) | Pre-release verification | 20 min |

**Use this folder to understand:** Deployment, monitoring, operations, readiness

---

### **Reference & API** 📖
Quick lookups and technical specifications

| Document | Purpose | Time |
|----------|---------|------|
| [API_REFERENCE.md](reference/API_REFERENCE.md) | HTTP endpoints, request/response examples | 30 min |
| [REVIEWER_GUIDE.md](reference/REVIEWER_GUIDE.md) | Project story, CV-safe framing, proof points | 10 min |

**Use this folder to understand:** API contracts, quick references, hiring narratives

---

### **Learning Program** 🎓
Complete 7-phase structured learning journey (250+ hours)

| Document | Purpose | Duration | Status |
|----------|---------|----------|--------|
| [COMPLETE_PROGRAM_SUMMARY.md](process-learn/COMPLETE_PROGRAM_SUMMARY.md) | Overview of all 7 phases | 30 min | ✅ Ready |
| [PHASE_INDEX.md](process-learn/PHASE_INDEX.md) | Master phase index & timeline | 20 min | ✅ Ready |
| [LEARNING_JOURNEY.md](process-learn/LEARNING_JOURNEY.md) | Progress tracker & session notes | 15 min | ✅ Ready |
| [PHASE1_STARTUP.md](process-learn/PHASE1_STARTUP.md) | Foundation learning (May 30) | 2 days | ✅ Complete |
| [PHASE2-DATABASE-DEEP-DIVE.md](process-learn/PHASE2-DATABASE-DEEP-DIVE.md) | Database theory (June 1-7) | 1 week | 📋 Planned |
| [PHASE4_STARTUP.md](process-learn/PHASE4_STARTUP.md) | Specialization path selection | 2-3 weeks | 📋 Planned |
| [PHASE5_LEARNING_GUIDE.md](process-learn/PHASE5_LEARNING_GUIDE.md) | Optimization & deployment (June 22-28) | 1 week | 📋 Ready |
| [PHASE5_STARTUP.md](process-learn/PHASE5_STARTUP.md) | Phase 5 daily checklist | 1 week | 📋 Ready |
| [PHASE6_LEARNING_GUIDE.md](process-learn/PHASE6_LEARNING_GUIDE.md) | Advanced patterns (June 29-July 12) | 2 weeks | 📋 Ready |
| [PHASE6_STARTUP.md](process-learn/PHASE6_STARTUP.md) | Phase 6 daily checklist | 2 weeks | 📋 Ready |
| [PHASE7_LEARNING_GUIDE.md](process-learn/PHASE7_LEARNING_GUIDE.md) | Portfolio mastery (July 13-19) | 1 week | 📋 Ready |
| [PHASE7_STARTUP.md](process-learn/PHASE7_STARTUP.md) | Phase 7 daily checklist | 1 week | 📋 Ready |

**Use this folder to:** Follow structured learning program, track progress, build portfolio

---

### **Supporting Assets** 📸
Images, diagrams, and visual references

| Folder | Content |
|--------|---------|
| [learn/](learn/) | Learning images & visual notes |
| [screenshots/](screenshots/) | Dashboard screenshots & diagrams |

---

## 🌐 Runtime Access

When running the system locally:

| Service | URL |
|---------|-----|
| **Backend API** | http://localhost:1122 |
| **Swagger UI** | http://localhost:1122/swagger-ui.html |
| **OpenAPI JSON** | http://localhost:1122/v3/api-docs |
| **Health Check** | http://localhost:1122/actuator/health |
| **Prometheus Metrics** | http://localhost:1122/actuator/prometheus |
| **Frontend Dashboard** | http://localhost:3000 |

---

## 🎯 Reading Recommendations

### **5-Minute Quick Start**
1. [REVIEWER_GUIDE.md](reference/REVIEWER_GUIDE.md) - Project overview
2. [BENCHMARK_RESULTS_ANALYSIS.md](performance/BENCHMARK_RESULTS_ANALYSIS.md) - Key numbers

### **1-Hour Deep Dive**
1. [ARCHITECTURE.md](architecture/ARCHITECTURE.md) - System design
2. [CONCURRENCY_AND_CONSISTENCY.md](performance/CONCURRENCY_AND_CONSISTENCY.md) - Consistency guarantees
3. [API_REFERENCE.md](reference/API_REFERENCE.md) - Endpoints

### **Full System Mastery (250+ hours)**
Follow the [7-Phase Learning Program](process-learn/PHASE_INDEX.md) with structured guides and daily checklists.

---

## 📊 Key Metrics at a Glance

| Metric | Value | Status |
|--------|-------|--------|
| **Baseline Throughput** | 84.71 req/sec | ❌ UNSAFE_DB |
| **Optimized Throughput** | 443.03 req/sec | ✅ REDIS_LUA_WITH_COMPENSATION |
| **Improvement** | 5.2x faster | 🎯 Achieved |
| **Latency (Optimized)** | 165.95 ms | ✅ P50 |
| **Latency P95** | 492 ms | ✅ Consistent |
| **Oversells** | 0 | ✅ Guaranteed |
| **Redis-DB Drift** | 0 | ✅ Self-healing |

---

## ✅ Documentation Standards

- **Source of Truth:** Java source, Maven files, benchmark scripts, and runtime config
- **Style:** Present-state, evergreen (avoid changelog language)
- **Scope:** Backend reliability lab (not a complete ticket-sales product)
- **Updates:** Run benchmarks on target machine before publishing numbers

---

## 🔗 External Links

- **GitHub Repository:** [Flash-Sale-Concurrency-Engine](https://github.com/...)
- **Swagger/OpenAPI:** Available at runtime
- **JMeter Benchmarks:** `benchmark/` folder

---

## 💡 Need Help?

**For understanding the system:**
- Start with [ARCHITECTURE.md](architecture/ARCHITECTURE.md)
- Trace a request through [REQUEST_RESPONSE_TRACING.md](architecture/REQUEST_RESPONSE_TRACING.md)

**For running benchmarks:**
- See [BENCHMARKING.md](performance/BENCHMARKING.md)
- Check [PHASE5_STARTUP.md](process-learn/PHASE5_STARTUP.md) for day-by-day optimization

**For learning distributed systems:**
- Follow [PHASE6_LEARNING_GUIDE.md](process-learn/PHASE6_LEARNING_GUIDE.md)

**For interviews:**
- Read [PHASE7_LEARNING_GUIDE.md](process-learn/PHASE7_LEARNING_GUIDE.md)

---

**Status:** ✅ Complete & Organized  
**Last Updated:** May 31, 2026  
**Total Documentation:** 250+ hours of learning materials + complete system docs

