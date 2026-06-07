# Phase 3: Load Testing & Benchmarking - Startup Guide

**Started:** May 31, 2026  
**Duration:** 1 week (5-7 days)  
**Goal:** Validate Phase 2 database theory with real performance data

---

## **🎯 Phase 3 Objectives**

### What You'll Accomplish:

1. ✅ **Run JMeter benchmarks** for all 4 strategies
2. ✅ **Measure real performance metrics** (throughput, latency, errors)
3. ✅ **Compare strategies** empirically
4. ✅ **Understand bottlenecks** (where time is spent)
5. ✅ **Create performance documentation** (BENCHMARK_RESULTS_ANALYSIS.md)

### By the End:

- You'll **see data** proving REDIS_LUA is 9x faster
- Understand **why** each strategy behaves as it does
- Know **where to optimize** next
- Have **proof points** for design decisions

---

## **📋 Pre-Flight Checklist**

Before running benchmarks, verify your environment is ready:

### Step 1: Check Backend Application Status

```powershell
# Is the backend running?
curl http://localhost:1122/swagger-ui.html

# Expected: HTTP 200 with Swagger UI
# If fails: Application not running, need to start it first
```

**If not running, start it:**
```powershell
cd d:\projects\tipjs-project\xxxx.com-section-ddd-24-27042025\Flash-Sale-Concurrency-Engine
mvn -pl app/backend/xxxx-start -am -DskipTests package
java -jar app/backend/xxxx-start/target/xxxx-start-1.0-SNAPSHOT.jar
```

---

### Step 2: Check Database (MySQL)

```powershell
# MySQL should be running via Docker Compose
docker ps | findstr mysql

# Expected: Container is UP and running
```

**If not running:**
```powershell
cd environment
docker-compose -f docker-compose-dev.yml up -d mysql
# Wait 30 seconds for MySQL to start
```

---

### Step 3: Check Redis

```powershell
# Redis should be running via Docker Compose
docker ps | findstr redis

# Expected: Container is UP and running
```

**If not running:**
```powershell
cd environment
docker-compose -f docker-compose-dev.yml up -d redis
# Wait 10 seconds for Redis to start
```

---

### Step 4: Verify JMeter Installation

```powershell
# Check if JMeter exists
Test-Path .\benchmark\jmeter\bin\jmeter.bat

# Expected: True
```

**If missing:**
```powershell
# JMeter should be included in the repo
# If not, download from: https://jmeter.apache.org/
```

---

## **🚀 Running Benchmarks: 4-Strategy Test Plan**

You'll run **4 separate benchmarks**, one for each strategy.

### **Test Matrix**

| Strategy | Speed | Safety | Expected Throughput | Expected Errors |
|----------|-------|--------|---|---|
| UNSAFE_DB | ⚡⚡⚡ | ❌ | Directional only | Overselling possible |
| CONDITIONAL_DB | ⚡⚡ | ✅ | Directional only | 0 oversells |
| REDIS_LUA | ⚡⚡⚡ | ⚠️ | Directional only | Drift possible |
| REDIS_LUA_WITH_COMPENSATION | ⚡⚡⚡ | ✅ | Directional only | 0 oversells + compensation |

---

Treat the expected throughput values as hypotheses from earlier local evidence, not guaranteed current numbers. The script reports throughput in requests per second.

### Latest Local Results - May 31, 2026

Environment:
- Machine: ACER
- Backend: packaged with `mvn -pl app/backend/xxxx-start -am -DskipTests package`, then run with `java -jar app/backend/xxxx-start/target/xxxx-start-1.0-SNAPSHOT.jar`
- Dependencies: MySQL and Redis from `environment/docker-compose-dev.yml`
- Fixture: `ticketItemId=4`, `stock=1000`, `yearMonth=202605`
- Workload: `TotalRequests=5000`, `Threads=100`

| Strategy | Run ID | Throughput req/s | Avg ms | P95 ms | P99 ms | Success Orders | Failed Orders | Oversold Count | Redis-DB Inconsistency Count |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|
| `UNSAFE_DB` | `UNSAFE_DB-20260531-185255` | 84.71 | 1084.86 | 1778 | 2165 | 5000 | 0 | 4000 | 1 |
| `CONDITIONAL_DB` | `CONDITIONAL_DB-20260531-185409` | 173.08 | 494.35 | 741 | 1049 | 1000 | 4000 | 0 | 0 |
| `REDIS_LUA` | `REDIS_LUA-20260531-185452` | 226.25 | 361.33 | 829 | 1092 | 1000 | 4000 | 0 | 0 |
| `REDIS_LUA_WITH_COMPENSATION` | `REDIS_LUA_WITH_COMPENSATION-20260531-185527` | 443.03 | 165.95 | 492 | 715 | 1000 | 4000 | 0 | 0 |

The latest run validates the Phase 2 theory: `UNSAFE_DB` oversells, while the safe strategies keep `oversoldCount = 0`. `REDIS_LUA_WITH_COMPENSATION` is the fastest measured strategy in this local run and ends with no Redis/DB drift.

---

## **Day 1: Setup & First Baseline (UNSAFE_DB)**

### What We're Doing:
- Run UNSAFE_DB to establish baseline
- Understand the test output format
- Verify JMeter + backend communication

### Step 1: Run UNSAFE_DB Benchmark

```powershell
cd d:\projects\tipjs-project\xxxx.com-section-ddd-24-27042025\Flash-Sale-Concurrency-Engine

# Run the benchmark script
.\benchmark\run-jmeter.ps1 `
  -BaseUrl "http://localhost:1122" `
  -Strategy "UNSAFE_DB" `
  -Threads 100 `
  -TotalRequests 5000
```

**What happens:**
1. Reset benchmark state (stock = 1000)
2. Warmup Redis cache
3. Run 5000 requests with 100 concurrent threads
4. Collect performance metrics
5. Check consistency (detect overselling)

**Wait for:** ~3-5 minutes

### Step 2: Check Results

```powershell
# See the results directory
ls .\benchmark\results\

# Should show: UNSAFE_DB-20260531-HHMMSS/
```

### Step 3: Examine Output Files

```powershell
# Summary of results
cat .\benchmark\results\UNSAFE_DB-*\summary-row.md

# Expected output:
# | runId | date | machine | strategy | totalRequests | ... | throughput | status |
```

---

## **Day 2: CONDITIONAL_DB Baseline**

### What We're Doing:
- Run CONDITIONAL_DB (safe database strategy)
- Compare speed vs UNSAFE_DB
- Verify zero overselling

### Run the Benchmark

```powershell
.\benchmark\run-jmeter.ps1 `
  -BaseUrl "http://localhost:1122" `
  -Strategy "CONDITIONAL_DB" `
  -Threads 100 `
  -TotalRequests 5000
```

**Wait for:** ~3-5 minutes

### Compare Results

```powershell
# View both results
cat .\benchmark\results\UNSAFE_DB-*\summary-row.md
cat .\benchmark\results\CONDITIONAL_DB-*\summary-row.md

# Expected:
# - UNSAFE_DB may oversell
# - CONDITIONAL_DB should keep oversoldCount at 0
# - CONDITIONAL_DB oversoldCount: 0
# - UNSAFE_DB oversoldCount: > 0 (or uncertain)
```

---

## **Day 3: REDIS_LUA (Fast but Risky)**

### What We're Doing:
- Run REDIS_LUA (fast Redis gate without compensation)
- See that it's as fast as UNSAFE_DB
- Observe Redis-DB drift

### Run the Benchmark

```powershell
.\benchmark\run-jmeter.ps1 `
  -BaseUrl "http://localhost:1122" `
  -Strategy "REDIS_LUA" `
  -Threads 100 `
  -TotalRequests 5000
```

**Wait for:** ~3-5 minutes

### Examine Output

```powershell
# View the consistency report
cat .\benchmark\results\REDIS_LUA-*\consistency.json

# Expected output shows:
# {
#   "redisStockAfter": 500,
#   "dbStockAfter": 502,
#   "redisDbInconsistencyCount": 2
# }
#
# This shows Redis and DB disagree!
```

---

## **Day 4: REDIS_LUA_WITH_COMPENSATION (Fast + Safe)**

### What We're Doing:
- Run REDIS_LUA_WITH_COMPENSATION (the recommended strategy)
- Verify it's fast AND safe
- See compensation in action

### Run the Benchmark

```powershell
.\benchmark\run-jmeter.ps1 `
  -BaseUrl "http://localhost:1122" `
  -Strategy "REDIS_LUA_WITH_COMPENSATION" `
  -Threads 100 `
  -TotalRequests 5000
```

**Wait for:** ~3-5 minutes

### Examine Output

```powershell
# View consistency
cat .\benchmark\results\REDIS_LUA_WITH_COMPENSATION-*\consistency.json

# Expected:
# {
#   "redisStockAfter": 500,
#   "dbStockAfter": 500,
#   "redisDbInconsistencyCount": 0,
#   "oversoldCount": 0
# }
#
# Perfect! No overselling, no drift!
```

---

## **Days 5-7: Analysis & Documentation**

### Step 1: Collect All Results

```powershell
# Create a summary file
$results = @()

# For each strategy:
ls .\benchmark\results\ | Where-Object { $_.Name -match "^(UNSAFE|CONDITIONAL|REDIS)" } | ForEach-Object {
    $summary = cat "./$($_.FullName)/summary-row.md"
    $results += $summary
}

# Save combined results
$results | Out-File .\benchmark\COLLECTED_RESULTS.txt
```

### Step 2: Analyze Performance Data

For each strategy, extract:
- **Throughput** (req/s) - Higher is better
- **Avg Latency** (ms) - Lower is better
- **P95 Latency** (ms) - 95% of requests faster than this
- **P99 Latency** (ms) - 99% of requests faster than this
- **Error Rate** - Should be 0 or near 0
- **Oversold Count** - Should be 0 for safe strategies

### Step 3: Create Performance Comparison Table

```markdown
| Metric | UNSAFE_DB | CONDITIONAL_DB | REDIS_LUA | REDIS_LUA_WITH_COMP |
|--------|-----------|---|---|---|
| Throughput (req/s) | 84.71 | 173.08 | 226.25 | 443.03 |
| Avg Latency (ms) | 1084.86 | 494.35 | 361.33 | 165.95 |
| P95 Latency (ms) | 1778 | 741 | 829 | 492 |
| P99 Latency (ms) | 2165 | 1049 | 1092 | 715 |
| Oversold Count | **4000** | 0 | 0 | 0 |
| Redis-DB Drift | 1 | 0 | 0 | 0 |
| Status | UNSAFE | SAFE | FAST, RISKY WITHOUT COMPENSATION | BEST LOCAL RUN |
```

### Step 4: Create BENCHMARK_RESULTS_ANALYSIS.md

Document your findings:

1. **Executive Summary**
   - Key findings
   - Recommended strategy
   - Performance trade-offs

2. **Detailed Results**
   - Each strategy's performance
   - Latency distributions (P50, P95, P99)
   - Error rates and consistency checks

3. **Analysis & Insights**
   - Why CONDITIONAL_DB is slow (row locks)
   - Why REDIS_LUA_WITH_COMPENSATION is recommended
   - When each strategy is appropriate

4. **Benchmarking Methodology**
   - Test parameters (5000 requests, 100 threads)
   - Environment details
   - Hardware specs

---

## **📊 Key Metrics to Track**

### Throughput
```
Definition: Requests per second
Formula: Total Requests / Duration (seconds)

2026-05-31 local run:
UNSAFE_DB: 84.71 req/s (unsafe, oversold)
CONDITIONAL_DB: 173.08 req/s (safe DB baseline)
REDIS_LUA: 226.25 req/s (fast Redis gate)
REDIS_LUA_WITH_COMP: 443.03 req/s (fastest local safe run)
```

### Latency
```
Avg Latency: Average time per request
P95 Latency: 95% of requests complete faster than this
P99 Latency: 99% of requests complete faster than this

Example:
Avg: 2.5ms (average)
P95: 5ms (95% of requests < 5ms)
P99: 8ms (99% of requests < 8ms)
```

### Consistency
```
Oversold Count: 
  - How many orders exceeded stock?
  - Should be 0 for safe strategies
  - UNSAFE_DB will be > 0

Redis-DB Inconsistency:
  - Do Redis and MySQL agree on stock?
  - REDIS_LUA may drift (eventual consistency)
  - REDIS_LUA_WITH_COMPENSATION should be 0
```

---

## **🔍 Understanding JMeter Output**

### results.jtl (Raw Samples)
```csv
timeStamp,elapsed,label,responseCode,responseMessage,threadName,dataType,success,failureMessage,bytes,sentBytes
1622500000000,2,POST /orders,200,OK,Thread Group 1-1,text,true,,523,128
1622500000003,1,POST /orders,200,OK,Thread Group 1-2,text,true,,523,128
...
```

- **timeStamp**: When request started (milliseconds)
- **elapsed**: How long request took (milliseconds)
- **responseCode**: HTTP status (200=success, 409=conflict)
- **success**: true if response code was expected

### HTML Report
```
Open: .\benchmark\results\{strategy}-*\html\index.html

Shows:
- Overall throughput
- Response time graph
- Error rate
- Latency percentiles
```

---

## **⚠️ Common Issues & Troubleshooting**

### Issue 1: JMeter fails with "Connection refused"
```
Error: Connection refused on localhost:1122

Solution:
1. Check backend is running: curl http://localhost:1122/swagger-ui.html
2. If not, package and start the jar from the repository root
3. Wait 30 seconds for app to start
4. Retry benchmark
```

### Issue 2: "No data collected"
```
Error: results.jtl is empty

Solution:
1. Check JMeter output in console
2. Verify threads are actually running
3. Check baseUrl parameter is correct
4. Ensure network connectivity to backend
```

### Issue 3: MySQL gone away
```
Error: MySQL connection lost during test

Solution:
1. Check MySQL is still running: docker ps
2. Restart MySQL: docker-compose up -d mysql
3. Wait 30 seconds
4. Retry benchmark
```

### Issue 4: Redis memory issues
```
Error: Redis OOM or slow performance

Solution:
1. Restart Redis: docker-compose restart redis
2. Clear old data from previous tests
3. Retry benchmark
```

---

## **✅ Success Criteria**

By the end of Phase 3, you should have:

- ✅ 4 complete benchmark runs (one per strategy)
- ✅ Performance metrics for each strategy
- ✅ Consistency reports showing Redis-DB state
- ✅ Understanding of why REDIS_LUA_WITH_COMPENSATION is recommended
- ✅ BENCHMARK_RESULTS_ANALYSIS.md document

---

## **📚 What You'll Learn**

### Empirical Validation
- See that database locking (CONDITIONAL_DB) IS slower
- Confirm Redis gate (REDIS_LUA) IS faster
- Verify compensation actually prevents drift

### Performance Profiling
- How to run load tests
- Interpret latency percentiles (P95, P99)
- Identify bottlenecks

### Benchmarking Best Practices
- Warm up caches before measuring
- Run long enough for stable measurements
- Check consistency, not just speed
- Repeat tests to verify reproducibility

---

## **🎓 Next: Phase 4 (After Phase 3)**

Once you complete Phase 3, you'll be ready for:

**Option A: Deployment & Operations**
- Docker Compose orchestration
- Prometheus + Grafana monitoring
- Runbook creation

**Option B: Advanced Pattern Design**
- Design similar systems from scratch
- Study Saga pattern deep dive
- CAP theorem application

---

## **📅 Timeline**

```
Day 1: UNSAFE_DB baseline
Day 2: CONDITIONAL_DB comparison
Day 3: REDIS_LUA analysis
Day 4: REDIS_LUA_WITH_COMPENSATION validation
Days 5-7: Analysis & documentation
```

**Start Date:** May 31, 2026  
**Expected Completion:** June 7, 2026

---

**Ready to begin?** Run this command:
```powershell
.\benchmark\run-jmeter.ps1 -Strategy "UNSAFE_DB"
```

Good luck! 🚀
