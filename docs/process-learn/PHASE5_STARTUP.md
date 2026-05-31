# Phase 5: Real-World Optimization & Deployment (June 22-28)

**Timeline:** June 22-28, 2026 (1 week)  
**Prerequisite:** Phase 3 & 4 complete with benchmark data + specialization knowledge  
**Goal:** Take learning from Phase 3 & 4, implement concrete optimizations, deploy to production  
**Deliverable:** `docs/OPTIMIZATION_AND_DEPLOYMENT_REPORT.md`

---

## 📋 **Phase 5 Overview**

This phase bridges theory (Phase 4) and production reality. You'll:

1. **Analyze Phase 3 benchmark data** → identify bottlenecks
2. **Implement targeted optimizations** → prove improvements
3. **Deploy to Docker** → containerization & orchestration
4. **Set up monitoring** → production observability
5. **Document for portfolio** → create talking points

**Expected outcome:** 3-5x performance improvement + deployment-ready system

---

## 🎯 **Week 1: June 22-28**

### **Day 1 (June 22): Benchmark Data Analysis**

**Morning - Data Collection & Review**

- [ ] Review Phase 3 benchmark results from `benchmark/results/`
- [ ] Extract key metrics:
  - [ ] Throughput (req/ms) for each strategy
  - [ ] Latency (Avg/P95/P99) for each
  - [ ] Error rates & oversell counts
  - [ ] Redis-DB consistency drift
- [ ] Create comparison table: `Phase3_Results_Summary.csv`
- [ ] Identify slowest strategy & bottlenecks

**Afternoon - Bottleneck Identification**

- [ ] Run `jcmd <pid> VM.uptime` to see JVM heap usage during benchmarks
- [ ] Check MySQL slow query log: `SELECT * FROM mysql.slow_log;`
- [ ] Review Redis memory stats: `INFO memory` from Redis CLI
- [ ] List top 3 bottlenecks:
  1. [ ] Bottleneck #1: ________________
  2. [ ] Bottleneck #2: ________________
  3. [ ] Bottleneck #3: ________________
- [ ] Document findings in `notes/Day1-Bottleneck-Analysis.md`

**Deliverable:** `Phase3_Results_Summary.csv` + bottleneck analysis

---

### **Day 2 (June 23): MySQL Optimization**

**Morning - Query Optimization**

- [ ] Get slow query list:
  ```sql
  SELECT query_time, lock_time, query FROM mysql.slow_log 
  WHERE db='vetautet' 
  ORDER BY query_time DESC LIMIT 10;
  ```
- [ ] For top 3 slow queries:
  - [ ] Run `EXPLAIN` to understand execution plan
  - [ ] Identify missing indexes
  - [ ] Check table statistics: `ANALYZE TABLE ticket_order_*;`
- [ ] Create new indexes (if needed):
  - [ ] Index on `ticket_order_YYYYMM.ticket_id`
  - [ ] Index on `ticket_order_YYYYMM.created_at`
- [ ] Benchmark before/after: `SHOW INDEX FROM ticket_order_202606;`

**Afternoon - Index Impact Testing**

- [ ] Run simple load test with new indexes:
  ```bash
  # 1000 requests, 10 concurrent threads
  .\benchmark\run-jmeter.ps1 -Strategy "CONDITIONAL_DB" -Threads 10 -Requests 1000
  ```
- [ ] Compare latency: before vs after
- [ ] Record improvement: `___ ms` reduction
- [ ] If < 10% improvement, revert indexes

**Deliverable:** `MySQL_Optimization_Report.md` with before/after metrics

---

### **Day 3 (June 24): Redis Tuning**

**Morning - Memory & Connection Optimization**

- [ ] Check current Redis config:
  ```bash
  redis-cli CONFIG GET "*"
  ```
- [ ] Optimize settings:
  - [ ] `maxmemory`: increase to 512MB (if available)
  - [ ] `maxmemory-policy`: confirm `allkeys-lru`
  - [ ] `tcp-backlog`: increase to 1024
  - [ ] `timeout`: set to 300 (5 min idle timeout)
- [ ] Test connection pooling:
  - [ ] Update `application.yaml`: `max-pool-size: 32` (was 16)
  - [ ] Restart backend
- [ ] Monitor connection count:
  ```bash
  redis-cli MONITOR | head -100
  ```

**Afternoon - Lua Script Optimization**

- [ ] Review current Lua script (stock deduction):
  - [ ] Check script lines: `SCRIPT DEBUG NO`
  - [ ] Measure execution time: `redis-cli SCRIPT DEBUG YES`
- [ ] Profile memory usage:
  ```bash
  redis-cli INFO stats | grep total_commands_processed
  ```
- [ ] Optimize if needed:
  - [ ] Reduce key lookups (batch if possible)
  - [ ] Use pipelining instead of individual commands
- [ ] Before/after: measure `avg latency per request`

**Deliverable:** `Redis_Tuning_Report.md` with tuning parameters & improvements

---

### **Day 4 (June 25): JVM Performance Profiling**

**Morning - Heap & GC Analysis**

- [ ] Enable GC logging during benchmark:
  ```bash
  java -Xmx2G -Xms2G \
    -XX:+PrintGCDetails \
    -XX:+PrintGCTimeStamps \
    -Xloggc:gc.log \
    -jar app/backend/xxxx-start/target/xxxx-start-1.0-SNAPSHOT.jar
  ```
- [ ] Run benchmark & collect GC log:
  ```bash
  .\benchmark\run-jmeter.ps1 -Strategy "REDIS_LUA_WITH_COMPENSATION"
  ```
- [ ] Analyze GC log:
  - [ ] Count GC pauses (target: < 100ms per pause)
  - [ ] Check GC frequency (target: < 5 GC/minute under load)
  - [ ] Heap utilization pattern (should not exceed 80%)
- [ ] If GC > 100ms, tune:
  - [ ] Increase heap: `-Xmx4G -Xms4G`
  - [ ] Change collector: `-XX:+UseG1GC` (newer)
  - [ ] Tune pause time: `-XX:MaxGCPauseMillis=50`

**Afternoon - Thread Pool Optimization**

- [ ] Check current thread pool config (Tomcat):
  ```bash
  curl http://localhost:1122/actuator/metrics/tomcat.threads.current
  ```
- [ ] Optimize if needed:
  ```yaml
  server:
    tomcat:
      threads:
        max: 200        # increase from 100
        min-spare: 50   # increase from 10
  ```
- [ ] Rerun benchmark & measure latency improvement

**Deliverable:** `JVM_Profiling_Report.md` with GC analysis & tuning results

---

### **Day 5 (June 26): Docker & Container Optimization**

**Morning - Container Resource Limits**

- [ ] Check current `docker-compose-dev.yml`:
  - [ ] MySQL memory limit (current: 512MB → optimize to 1GB)
  - [ ] Redis memory limit (current: 256MB → optimize to 512MB)
  - [ ] Backend app CPU share (add if missing)
- [ ] Update docker-compose:
  ```yaml
  mysql:
    deploy:
      resources:
        limits:
          memory: 1G
  redis:
    deploy:
      resources:
        limits:
          memory: 512M
  backend:
    deploy:
      resources:
        limits:
          cpus: '2.0'
          memory: 2G
  ```
- [ ] Rebuild & test:
  ```bash
  docker-compose down
  docker-compose up -d
  .\benchmark\smoke-local.ps1
  ```

**Afternoon - Network & Volume Optimization**

- [ ] Add network optimization:
  - [ ] Create dedicated bridge network (vs default)
  - [ ] Set network driver to overlay if multi-host
- [ ] Optimize volumes:
  - [ ] Use named volumes (vs bind mounts) for MySQL data
  - [ ] Check volume performance: `docker volume inspect`
- [ ] Test startup time: measure `docker-compose up` to `ready` state
  - [ ] Record baseline: ___ seconds
  - [ ] Target: < 30 seconds

**Deliverable:** Updated `docker-compose-dev.yml` with optimizations

---

### **Day 6 (June 27): Monitoring & Alerting Setup**

**Morning - Prometheus Metrics Collection**

- [ ] Verify Prometheus scrape config:
  ```yaml
  scrape_configs:
    - job_name: 'backend'
      static_configs:
        - targets: ['localhost:1122']
      metrics_path: '/actuator/prometheus'
  ```
- [ ] Add custom metrics (if needed):
  - [ ] Business metrics: `orders_created_total`, `orders_failed_total`
  - [ ] Performance metrics: `stock_deduction_latency_ms`
  - [ ] System metrics: `database_connection_pool_active`
- [ ] Verify data collection: `curl http://localhost:9090/api/v1/query?query=up`

**Afternoon - Grafana Dashboard**

- [ ] Import dashboards:
  - [ ] JVM Performance (heap, GC, threads)
  - [ ] MySQL Performance (queries/sec, slow queries)
  - [ ] Redis Performance (commands/sec, memory usage)
  - [ ] Business Metrics (orders/sec, error rate)
- [ ] Create custom dashboard: "Optimization Baseline"
  - [ ] Panel 1: Throughput (req/sec) - before/after
  - [ ] Panel 2: Latency (P95) - before/after
  - [ ] Panel 3: Error Rate - before/after
  - [ ] Panel 4: Resource Utilization (CPU, memory, network)
- [ ] Save dashboard: `optimization-baseline.json`

**Deliverable:** Grafana dashboards + screenshots

---

### **Day 7 (June 28): Synthesis & Portfolio Document**

**Morning - Results Compilation**

- [ ] Gather all optimization results:
  - [ ] MySQL optimization: ___ % improvement
  - [ ] Redis tuning: ___ % improvement
  - [ ] JVM profiling: ___ % improvement
  - [ ] Container optimization: ___ % improvement
- [ ] Cumulative throughput improvement:
  - [ ] Baseline (Phase 3): ___ req/ms
  - [ ] After optimizations: ___ req/ms
  - [ ] Total improvement: ___ % (target: 3-5x)
- [ ] Create before/after comparison table
- [ ] Prepare screenshots:
  - [ ] Grafana baseline vs optimized
  - [ ] JVM metrics improvement
  - [ ] Docker stats comparison

**Afternoon - Document & Finalize**

- [ ] Create `docs/OPTIMIZATION_AND_DEPLOYMENT_REPORT.md`:
  - [ ] Executive summary (2 paragraphs)
  - [ ] Optimization breakdown (MySQL, Redis, JVM, Docker)
  - [ ] Metrics comparison table (before/after)
  - [ ] Deployment instructions
  - [ ] Monitoring setup guide
  - [ ] Key learnings & talking points for interviews
- [ ] Add to Git:
  ```bash
  git add docs/OPTIMIZATION_AND_DEPLOYMENT_REPORT.md
  git commit -m "Phase 5 complete: 3-5x performance improvement"
  ```
- [ ] Update `LEARNING_JOURNEY.md`:
  - [ ] Mark Phase 5 as ✅ COMPLETE
  - [ ] Add link to optimization report

**Deliverable:** `docs/OPTIMIZATION_AND_DEPLOYMENT_REPORT.md` + Git commit

---

## 💼 **Interview Talking Points**

After Phase 5, you can discuss:

1. **Performance Analysis**
   - "I analyzed Phase 3 benchmark data to identify bottlenecks"
   - "Optimized MySQL queries by adding strategic indexes → 30% latency reduction"
   - "Tuned Redis connection pooling → 25% throughput improvement"

2. **Systems Thinking**
   - "Applied profiling tools (JMeter, Prometheus, GC logs) to understand system behavior"
   - "Recognized that optimization requires multiple layers: DB, cache, JVM, containers"
   - "Achieved 3-5x cumulative improvement through iterative optimization"

3. **Production Readiness**
   - "Containerized the system with proper resource limits"
   - "Set up monitoring dashboards for observability"
   - "Documented deployment procedures for consistency"

4. **Data-Driven Decisions**
   - "Used benchmark data to prioritize optimization efforts"
   - "Measured before/after for every change to validate impact"
   - "Applied scientific method: hypothesis → test → measure → iterate"

---

## 🎓 **Skills Achieved**

- ✅ Performance analysis & benchmarking
- ✅ Database query optimization
- ✅ Cache tuning & memory management
- ✅ JVM profiling & GC tuning
- ✅ Container orchestration & resource limits
- ✅ Monitoring & observability
- ✅ Data-driven optimization

---

## 📍 **Next Steps (Phase 6)**

After completing Phase 5, you'll be ready for:

**Option A:** Phase 6 - Advanced Patterns & Scaling
- Event-driven architecture
- CQRS pattern
- Microservices decomposition
- Distributed consensus algorithms

**Option B:** Go directly to Phase 7 - Portfolio & Interview Prep
- Polish GitHub repository
- Write CV bullets with proof points
- Prepare interview stories
- Create demo video walkthrough

---

## 📚 **Reference Materials**

- MySQL Optimization: See `docs/ARCHITECTURE.md` (Storage Model section)
- Redis Tuning: See `docs/REDIS_COMPREHENSIVE_GUIDE.md`
- JVM Profiling: Spring Boot Actuator docs (metrics endpoint)
- Docker: `environment/docker-compose-dev.yml`
- Monitoring: Prometheus + Grafana documentation

---

**Status:** 📋 PLANNED  
**Estimated Duration:** 1 week (intensive)  
**Difficulty:** ⭐⭐⭐⭐ (moderate-advanced)  
**Portfolio Value:** ⭐⭐⭐⭐⭐ (high - shows systems thinking)

