# Phase 2: Database Deep Dive - Theoretical Foundation

**Created:** May 31, 2026  
**Learning Level:** Foundation → Advanced  
**Duration:** 2 weeks  
**Focus:** Understanding CONDITIONAL_DB Strategy through MySQL Fundamentals

---

## Current Benchmark Evidence

The throughput numbers in this theory note are illustrative and historical. For the latest measured local results, use [../BENCHMARKING.md](../BENCHMARKING.md#latest-local-benchmark-evidence) and [PHASE3_STARTUP.md](./PHASE3_STARTUP.md#latest-local-results---may-31-2026).

The May 31, 2026 local run uses `5000` requests, `100` threads, stock `1000`, and reports throughput in requests per second from `benchmark/run-jmeter.ps1`.

---

## Table of Contents

1. [MySQL Transaction Isolation Levels](#mysql-transaction-isolation-levels)
2. [Race Conditions & How CONDITIONAL_DB Prevents Them](#race-conditions--how-conditional_db-prevents-them)
3. [Atomic Operations & Check-And-Deduct Pattern](#atomic-operations--check-and-deduct-pattern)
4. [Index Strategy for Performance](#index-strategy-for-performance)
5. [Partitioned Tables for Scalability](#partitioned-tables-for-scalability)
6. [CONDITIONAL_DB vs REDIS_LUA Trade-offs](#conditional_db-vs-redis_lua-trade-offs)

---

# MySQL Transaction Isolation Levels

## Overview: 4 Levels of Consistency

MySQL provides 4 transaction isolation levels, each solving different concurrency problems:

```
Isolation Level           Safety        Speed      Main Use Case
─────────────────────────────────────────────────────────────────
1. READ UNCOMMITTED       ⚠️ ⚠️ ⚠️      ⚡⚡⚡      (Rarely used - too dangerous)
2. READ COMMITTED         ⚠️ ⚠️          ⚡⚡       (Some use cases)
3. REPEATABLE READ        ✅ ⚠️          ⚡        (MySQL DEFAULT)
4. SERIALIZABLE           ✅ ✅ ✅      ❌❌❌     (Very safe but very slow)
```

### Level 1: READ UNCOMMITTED (Dirty Reads Possible)

**Definition:** Transactions can read **uncommitted changes** from other transactions.

**SQL Command:**
```sql
SET TRANSACTION ISOLATION LEVEL READ UNCOMMITTED;
```

**Example: The Dirty Read Problem**

```
Timeline  │ Transaction A (User 1)           │ Transaction B (User 2)
──────────┼──────────────────────────────────┼──────────────────────────
T1        │ BEGIN;                           │
T2        │ UPDATE stock SET qty = qty - 100 │
T3        │ (not committed yet)              │
T4        │                                  │ BEGIN;
T5        │                                  │ SELECT stock;
T6        │                                  │ Result: 900 (from T2!)
T7        │ ROLLBACK; ← Transaction A fails! │
T8        │                                  │ ← User 2 now has WRONG data!
```

**Problem:** User 2 read data that never existed (A rolled back).

**When to use:** Almost never. Too dangerous for financial/stock systems.

---

### Level 2: READ COMMITTED (Non-Repeatable Reads Possible)

**Definition:** Transactions can only read **committed changes**, but data can change between reads within the same transaction.

**SQL Command:**
```sql
SET TRANSACTION ISOLATION LEVEL READ COMMITTED;
```

**Example: The Non-Repeatable Read Problem**

```
Timeline  │ Transaction A (Inventory Report) │ Transaction B (Customer Order)
──────────┼──────────────────────────────────┼──────────────────────────────
T1        │ BEGIN;                           │
T2        │ SELECT stock WHERE id = 4;       │
T3        │ Result: 500 tickets              │
T4        │                                  │ BEGIN;
T5        │                                  │ UPDATE stock SET qty = 400
T6        │                                  │ WHERE id = 4;
T7        │                                  │ COMMIT;
T8        │ SELECT stock WHERE id = 4;       │
T9        │ Result: 400 tickets ← DIFFERENT! │
T10       │ Same report shows 2 different    │
          │ answers for same question!       │
```

**Problem:** Running same query twice in one transaction gives different results. Bad for reconciliation.

**When to use:** Some databases use this as default (PostgreSQL). Works for non-critical data.

---

### Level 3: REPEATABLE READ (MySQL DEFAULT) - Phantom Reads Possible

**Definition:** If you read data once, it will show the same value if you read it again within the same transaction. **No dirty reads, no non-repeatable reads.**

**SQL Command:**
```sql
SET TRANSACTION ISOLATION LEVEL REPEATABLE READ;
```

**Implementation in MySQL:** Uses **snapshot** - each transaction sees data as it was when transaction started.

**Example: The Phantom Read Problem**

```
Timeline  │ Transaction A (Report Query)     │ Transaction B (New Order)
──────────┼──────────────────────────────────┼─────────────────────────
T1        │ BEGIN;                           │
T2        │ SELECT * FROM orders             │
T3        │ WHERE status = 'pending';        │
T4        │ Result: 3 orders found           │
T5        │ (Starts summing money...)        │
T6        │                                  │ BEGIN;
T7        │                                  │ INSERT INTO orders VALUES (...);
T8        │                                  │ COMMIT;
T9        │ SELECT * FROM orders             │
T10       │ WHERE status = 'pending';        │
T11       │ Result: 4 orders! ← NEW ROW!    │
T12       │ Total money changed!             │
```

**Problem:** New rows appear (phantom rows) even though same SELECT.

**Why MySQL uses this:** Good balance between safety and performance.

**In Flash-Sale system:** REPEATABLE_READ prevents race conditions on the **same row**, which is what we care about for stock deduction.

---

### Level 4: SERIALIZABLE (Strictest - Slowest)

**Definition:** Transactions run as if they were **completely isolated** from each other. No dirty reads, no non-repeatable reads, no phantom reads.

**SQL Command:**
```sql
SET TRANSACTION ISOLATION LEVEL SERIALIZABLE;
```

**How it works:** Acquires locks that prevent other transactions from reading or writing affected data.

**Example: What Happens**

```
Timeline  │ Transaction A (Decrease Stock)   │ Transaction B (Check Stock)
──────────┼──────────────────────────────────┼─────────────────────────────
T1        │ BEGIN;                           │
T2        │ SELECT * FROM stock WHERE id=4;  │
T3        │ (Lock acquired)                  │
T4        │                                  │ BEGIN;
T5        │                                  │ SELECT * FROM stock WHERE id=4;
T6        │                                  │ ← WAITING... locked by A!
T7        │ UPDATE stock SET qty = 400;      │
T8        │ COMMIT; ← Release lock           │
T9        │                                  │ ← Now B can read (T9)
T10       │                                  │ Result: 400
```

**Problem:** Everything is locked, so only ONE transaction can run at a time!

```
Throughput with SERIALIZABLE:
- 100 concurrent requests
- Wait queue forms
- Effective throughput: ~1 req/ms (very slow!)

Throughput with REPEATABLE_READ:
- 100 concurrent requests
- Can process in parallel
- Effective throughput: ~100 req/ms (much faster!)
```

**When to use:** Critical financial systems where every transaction must be absolutely isolated. But prepare for slow performance.

---

## MySQL Default: Why REPEATABLE READ?

```sql
-- Check your MySQL server default
SELECT @@transaction_isolation;
-- Result: REPEATABLE-READ
```

**Why not SERIALIZABLE?**
- Performance hit is massive
- For most use cases, REPEATABLE_READ is safe enough
- The check-and-deduct pattern handles race conditions at application level

**For Flash-Sale:**
- Using REPEATABLE_READ (default)
- But we implement **Atomic Check-And-Deduct** at SQL level
- This gives us SERIALIZABLE-like safety WITHOUT the performance cost!

---

# Race Conditions & How CONDITIONAL_DB Prevents Them

## What is a Race Condition?

**Simple definition:** Two or more threads/processes try to modify the same data at the same time, and the **order matters**.

### The Classic Example: Inventory Management

**Initial state:**
```
ticket_item table:
├─ id: 4
├─ stock_available: 100
└─ name: "VIP Ticket"
```

**User 1 buys 50 tickets**  
**User 2 buys 60 tickets**

**Expected result:** One succeeds, one fails (not enough stock)  
**Actual result with UNSAFE_DB:** BOTH succeed! (stock goes to -10) ❌

---

### UNSAFE_DB: The Race Condition Baseline

**SQL Query:**
```sql
UPDATE ticket_item 
SET stock_available = stock_available - :quantity
WHERE id = :ticketId;
```

**Problem:** No WHERE clause checking stock!

**Race Condition Timeline:**

```
Time  │ Thread 1 (User 1, -50)      │ Thread 2 (User 2, -60)
──────┼─────────────────────────────┼────────────────────────
T0    │ Initial: stock = 100        │
T1    │ Reads: stock = 100          │
T2    │                             │ Reads: stock = 100
T3    │ Calculates: 100 - 50 = 50   │
T4    │                             │ Calculates: 100 - 60 = 40
T5    │ UPDATE: stock = 50          │
T6    │ DB CONFIRM: ✓               │
T7    │                             │ UPDATE: stock = 40
T8    │                             │ DB CONFIRM: ✓ ← WRONG!
T9    │ Final: stock = 40           │
      │ Expected: 50 or 40 (not both!) │
      │ OVERSOLD! ❌                │
```

**Why it happens:**
1. Both threads read the SAME value (100) at nearly the same time
2. Both calculate their own deductions independently
3. Both write back, but the later write **overwrites** the earlier one
4. First deduction is lost!

**MySQL doesn't prevent this because:**
- Each UPDATE is atomic INDIVIDUALLY
- But the **SELECT-then-UPDATE** pattern is NOT atomic
- Time between reading and writing leaves a gap

---

### CONDITIONAL_DB: Atomic Check-And-Deduct

**SQL Query:**
```sql
UPDATE ticket_item 
SET stock_available = stock_available - :quantity
WHERE id = :ticketId 
  AND stock_available >= :quantity;
```

**Key insight:** The WHERE clause is checked **as part of the same UPDATE statement**.

**Atomic Race Condition Timeline:**

```
Time  │ Thread 1 (User 1, -50)           │ Thread 2 (User 2, -60)
──────┼──────────────────────────────────┼────────────────────────
T0    │ Initial: stock = 100             │
T1    │ UPDATE ... WHERE stock >= 50 ✓   │
T2    │ DB evaluates condition:          │
      │ - Current stock = 100            │
      │ - 100 >= 50? YES ✓               │
T3    │ - Deduct: 100 - 50 = 50          │
T4    │ - DB CONFIRM: 1 row affected ✓   │
T5    │ - Return to Java: SUCCESS ✓      │
T6    │                                  │ UPDATE ... WHERE stock >= 60 ✓
T7    │                                  │ DB evaluates condition:
      │                                  │ - Current stock = 50
      │                                  │ - 50 >= 60? NO ❌
T8    │                                  │ - Return to Java: 0 rows affected ❌
T9    │                                  │ - Return to Java: FAILURE ❌
      │                                  │ Thread 2 knows it failed!
      │ Final: stock = 50                │
      │ ✅ NO OVERSELL! Expected result! │
```

**Why it's atomic:**
- The WHERE clause and UPDATE happen in ONE database command
- MySQL locks the row while evaluating the condition
- Other threads MUST wait for the lock to release
- Then they see the new (lower) stock value

---

## Why Lock-Based Approach?

```
Without WHERE clause:
├─ Thread A: SET stock = 50
├─ Thread B: SET stock = 40 ← Overwrites A's change!
└─ Result: Lost update!

With WHERE clause:
├─ Thread A: Lock row 4
│  └─ Evaluate: stock (100) >= 50? YES
│  └─ UPDATE: stock = 50
│  └─ Unlock row 4
├─ Thread B: Try to lock row 4 (wait...)
│  └─ Now can lock
│  └─ Evaluate: stock (50) >= 60? NO
│  └─ 0 rows affected
│  └─ Unlock row 4
└─ Result: Correct!
```

---

# Atomic Operations & Check-And-Deduct Pattern

## What is Atomicity?

**Definition:** An operation either **completely succeeds or completely fails**. No partial state.

```
NOT atomic:
├─ SELECT stock ← Can fail at T1
├─ Check stock >= qty ← Can fail at T2
├─ Calculate new stock ← Can fail at T3
└─ UPDATE stock ← Can fail at T4
Result: Could fail at any step, leaving inconsistent state!

ATOMIC (single operation):
├─ UPDATE ... WHERE ...
  └─ All of this happens as ONE indivisible unit
Result: Either all succeeds or all fails!
```

---

## The Check-And-Deduct Pattern

This is the **fundamental pattern** that makes CONDITIONAL_DB work:

```sql
-- Pattern: Atomically check a condition and make a change
UPDATE table_name
SET column = new_value
WHERE row_identifier = value
  AND condition_check = true;
```

**Real example from your code:**

```sql
UPDATE ticket_item
SET stock_available = stock_available - :quantity,
    updated_at = CURRENT_TIMESTAMP
WHERE id = :ticketId 
  AND stock_available >= :quantity;
```

**MySQL's guarantee:** This executes atomically on the matching row.

**Java's interpretation:**
```java
int affectedRows = ticketOrderJPAMapper.decreaseStockLevel1(ticketId, quantity);

if (affectedRows > 0) {
    // The WHERE condition was satisfied
    // Stock was deducted
    return StockDeductionResult.success();
} else {
    // The WHERE condition was NOT satisfied
    // Nothing was updated
    // Stock not available
    return StockDeductionResult.failure("DB_STOCK_DECREMENT_FAILED");
}
```

---

## How the Database Implements Atomicity

### InnoDB Locking Mechanism

MySQL uses **row-level locking** for this:

```
Request: UPDATE ticket_item SET stock = stock - 50 WHERE id = 4 AND stock >= 50

Step 1: Acquire lock on row 4
        ├─ Shared lock (read-lock) - other transactions can read
        └─ Exclusive lock (write-lock) - other transactions blocked

Step 2: Evaluate WHERE clause
        ├─ Current stock value? 100
        ├─ Is 100 >= 50? YES
        └─ Condition satisfied

Step 3: Apply the UPDATE
        └─ stock = 100 - 50 = 50

Step 4: Release lock
        └─ Other transactions can now proceed

Result: Either UPDATE happened (1 row affected) or it didn't (0 rows)
        No partial state possible!
```

---

### Timeline with Multiple Threads

```
Time  │ Thread A                    │ Thread B                    │ Database State
──────┼─────────────────────────────┼─────────────────────────────┼──────────────────
T0    │                             │                             │ stock = 100
T1    │ UPDATE ... WHERE stock>=50  │                             │
T2    │ (acquire exclusive lock)    │                             │ Lock: row 4 (A)
T3    │ (evaluate condition)        │                             │
      │ 100 >= 50? YES              │                             │
T4    │ (apply update)              │                             │ stock = 50
T5    │ (release lock)              │                             │ Lock: released
T6    │                             │ UPDATE ... WHERE stock>=60  │
T7    │                             │ (acquire exclusive lock)    │ Lock: row 4 (B)
T8    │                             │ (evaluate condition)        │
      │                             │ 50 >= 60? NO               │
T9    │                             │ (no update)                 │ stock = 50
T10   │                             │ (release lock)              │ Lock: released
      │                             │ Return: 0 rows affected     │
```

---

## Guaranteed Properties: ACID

The Check-And-Deduct pattern guarantees **ACID**:

| Property | Guarantee | Example |
|----------|-----------|---------|
| **A**tomicity | All or nothing | Either stock deducted or not |
| **C**onsistency | Valid state | stock never goes negative |
| **I**solation | No interference | Thread B doesn't see Thread A's partial work |
| **D**urability | Survives crashes | If commit succeeds, data persists |

---

# Index Strategy for Performance

## What is an Index?

**Simple analogy:** Like an index in a book.

```
Book without index:
├─ Want to find all pages about "Concurrency"
├─ Read page 1, 2, 3, 4, ... 500 (SLOW!)
└─ Found 3 pages with "Concurrency"

Book with index:
├─ Look up "Concurrency" in index
├─ Index says: pages 45, 234, 389
├─ Jump directly to those pages (FAST!)
└─ Found same 3 pages but 100x faster!
```

---

## Index in Your Schema

**Current schema:**

```sql
CREATE TABLE ticket_item (
    id BIGINT PRIMARY KEY,              ← PRIMARY KEY index (clustered)
    name VARCHAR(50),                   ← NO INDEX
    description TEXT,                   ← NO INDEX
    stock_initial INT,                  ← NO INDEX
    stock_available INT,                ← ⚠️ MISSING INDEX!
    price_original BIGINT,              ← NO INDEX
    price_flash BIGINT,                 ← NO INDEX
    sale_start_time DATETIME,           ← ⭐ INDEX: idx_start_time
    sale_end_time DATETIME,             ← ⭐ INDEX: idx_end_time
    status INT,                         ← ⭐ INDEX: idx_status
    activity_id BIGINT,                 ← NO INDEX
    updated_at DATETIME,                ← NO INDEX
    created_at DATETIME,                ← NO INDEX
    PRIMARY KEY (id),
    KEY idx_end_time (sale_end_time),
    KEY idx_start_time (sale_start_time),
    KEY idx_status (status)
);
```

---

## Index for CONDITIONAL_DB Query

**The query:**
```sql
UPDATE ticket_item 
SET stock_available = stock_available - :quantity,
    updated_at = CURRENT_TIMESTAMP
WHERE id = :ticketId 
  AND stock_available >= :quantity;
```

**Index usage analysis:**

| WHERE clause | Index available? | Impact |
|---|---|---|
| `id = :ticketId` | ✅ PRIMARY KEY | Very fast lookup (clustered index) |
| `stock_available >= :quantity` | ❌ NO INDEX | Full table scan if many rows |

**Scenario 1: Without index on stock_available**
```
Query: UPDATE ... WHERE id = 4 AND stock_available >= 100

MySQL execution plan:
├─ Use PRIMARY KEY index to find row 4
│  └─ Found in 1ms
├─ Evaluate stock_available >= 100
│  └─ Check: Is 150 >= 100? YES
├─ Update row
│  └─ Done in 1ms
└─ Total: ~2ms (acceptable because we already have the row)
```

**Scenario 2: Complex query with 1000s of conditions**
```
Query: UPDATE ... WHERE status = 'active' AND stock_available >= 100

MySQL execution plan:
├─ Option A: Use status index, then check stock_available
│  └─ Find 50 active tickets via index
│  └─ Check each: stock_available >= 100? (filter)
│  └─ Update 30 matching rows
│  └─ Total: ~30ms
├─ Option B: Full table scan
│  └─ Scan all 10000 rows
│  └─ Check status, then stock_available
│  └─ Update 30 matching rows
│  └─ Total: ~300ms
└─ Index helps 10x here!
```

---

## Types of Indexes

### 1. PRIMARY KEY Index (Clustered)

```sql
PRIMARY KEY (id)

Characteristics:
├─ Automatically created
├─ Unique: No duplicates
├─ Clustered: Data physically sorted by this key
├─ Speed: ⭐⭐⭐⭐⭐ Fastest
└─ Usage: Almost every query
```

---

### 2. UNIQUE Index

```sql
UNIQUE KEY order_number (order_number)

Characteristics:
├─ Manually created
├─ Unique: No duplicates
├─ Non-clustered: Doesn't affect physical data order
├─ Speed: ⭐⭐⭐⭐ Very fast
└─ Usage: When you need unique constraint + fast lookup
```

---

### 3. REGULAR Index (Non-Unique)

```sql
KEY idx_status (status)
KEY idx_end_time (sale_end_time)

Characteristics:
├─ Manually created
├─ Allows duplicates
├─ Non-clustered
├─ Speed: ⭐⭐⭐⭐ Very fast
└─ Usage: WHERE, JOIN, ORDER BY clauses
```

---

### 4. COMPOSITE Index (Multiple Columns)

```sql
KEY idx_sale_window (sale_start_time, sale_end_time)

Characteristics:
├─ Indexes multiple columns together
├─ Order matters!
├─ Speed: ⭐⭐⭐⭐ (depends on query pattern)
└─ Usage: Queries filtering on multiple columns
```

---

## Composite Index: Column Order Matters

**Example: Composite Index**

```sql
CREATE INDEX idx_order_lookup ON ticket_order (user_id, order_date);
```

**This index helps:**
```sql
SELECT * FROM ticket_order WHERE user_id = 42 AND order_date > '2025-01-01';
-- ✅ Uses index efficiently
```

**This index does NOT help:**
```sql
SELECT * FROM ticket_order WHERE order_date > '2025-01-01';
-- ❌ Can't use composite index (missing leading column user_id)
```

**Why?** Index is ordered by (user_id, order_date). If you skip user_id, the index order doesn't help.

---

## When to Add Index: The Trade-Off

### Benefits of Index
- ✅ Fast SELECT queries
- ✅ Fast WHERE conditions
- ✅ Fast JOIN operations

### Costs of Index
- ❌ Slower INSERT (must update index)
- ❌ Slower UPDATE (must update index)
- ❌ Slower DELETE (must update index)
- ❌ Extra disk space

### Decision: Should you index stock_available?

**For CONDITIONAL_DB:**
- UPDATE queries on stock_available
- With 1000s of concurrent threads
- Each UPDATE locks the row briefly

**Decision:**
```
If most queries are: UPDATE ... WHERE id = X AND stock >= Y
    └─ ID lookup is PRIMARY KEY (fast)
    └─ stock >= Y is just a filter (not searching)
    └─ Adding index on stock_available won't help much!
    └─ Don't add index (saves INSERT/UPDATE cost)

If most queries are: SELECT * WHERE stock >= Y AND status = 'active'
    └─ Must scan to find matching rows
    └─ Index on (status, stock) would help!
    └─ Add composite index
```

---

# Partitioned Tables for Scalability

## The Problem: Large Tables

**Without partitioning:**

```
Single table: ticket_order
├─ January 2025: 1 million rows
├─ February 2025: 1 million rows
├─ ...
├─ December 2025: 1 million rows
└─ Total: 12 million rows
```

**Queries are slow:**
```sql
SELECT * FROM ticket_order 
WHERE order_date BETWEEN '2025-01-01' AND '2025-01-31'

MySQL: Must scan all 12 million rows!
├─ Check row 1: Is it Jan? YES, include
├─ Check row 2: Is it Jan? NO, skip
├─ ...
├─ Check row 12000000: Is it Jan? ... → Finally find Jan rows
└─ Result: ~1 second (SLOW!)
```

---

## Solution: Monthly Partitioning

**With partitioning:**

```
Multiple tables by month:
├─ ticket_order_202501 (1M rows)
├─ ticket_order_202502 (1M rows)
├─ ticket_order_202503 (1M rows)
└─ ...
├─ ticket_order_202512 (1M rows)

"Partitioned table" - logically one table, physically many!
```

**Queries are fast:**
```sql
SELECT * FROM ticket_order 
WHERE order_date BETWEEN '2025-01-01' AND '2025-01-31'

MySQL: Only scan January partition!
├─ Ignore 202502, 202503, ..., 202512 (partition pruning)
├─ Scan only 202501 (1M rows instead of 12M)
└─ Result: ~10ms (100x FASTER!)
```

---

## Partition Strategies

### Strategy 1: RANGE Partitioning (by date)

```sql
CREATE TABLE ticket_order (
    id INT,
    user_id INT,
    order_date DATETIME,
    ...
) PARTITION BY RANGE (YEAR_MONTH(order_date)) (
    PARTITION p_202501 VALUES LESS THAN (202502),
    PARTITION p_202502 VALUES LESS THAN (202503),
    PARTITION p_202503 VALUES LESS THAN (202504),
    ...
    PARTITION p_202512 VALUES LESS THAN (202601)
);
```

**How it works:**
- NEW: If order_date = '2025-01-15' → Goes to p_202501 partition
- NEW: If order_date = '2025-02-20' → Goes to p_202502 partition

**Benefits:**
- Old data (Jan) stays in small table
- New data (current month) in separate table
- Query on Jan data: fast!
- Archive old data: easy (DROP p_202501)

---

### Strategy 2: HASH Partitioning (by value)

```sql
CREATE TABLE ticket_order (
    id INT,
    user_id INT,
    order_date DATETIME,
    ...
) PARTITION BY HASH(user_id) PARTITIONS 4;
```

**How it works:**
- HASH(user_id) determines partition
- user_id=1 → hash=1 → partition 1
- user_id=100 → hash=0 → partition 0
- Spreads data evenly across partitions

**Benefits:**
- Each partition has ~25% of data
- Concurrent writes to different partitions (less lock contention!)
- Queries by user_id: fast!

---

## Your Current Setup: Monthly Partitions

**From schema:**

```sql
-- Manually created per month
CREATE TABLE `vetautet`.`ticket_order_202502` (
    id INT PRIMARY KEY,
    user_id INT NOT NULL,
    order_number VARCHAR(50) NOT NULL UNIQUE,
    order_date TIMESTAMP NOT NULL,
    ...
);

CREATE TABLE `vetautet`.`ticket_order_202503` (
    id INT PRIMARY KEY,
    user_id INT NOT NULL,
    order_number VARCHAR(50) NOT NULL UNIQUE,
    order_date TIMESTAMP NOT NULL,
    ...
);
```

**Advantage 1: Query Speed**
```sql
-- For January report
SELECT * FROM ticket_order_202501 WHERE order_date > '2025-01-01'
-- Queries only January data!
```

**Advantage 2: Archive Old Data**
```sql
-- Drop December data (keep database lean)
DROP TABLE ticket_order_202412;
-- Can't do this with single big table
```

**Advantage 3: Parallel Inserts**
```
Thread A: INSERT INTO ticket_order_202501 (Jan data)
Thread B: INSERT INTO ticket_order_202502 (Feb data)
↓
No lock contention! Different tables!
```

---

## Partition Lifecycle in Your Code

```
Application creates partitions on-demand:

New month arrives → No table for it yet!
├─ Check: Does ticket_order_202504 exist?
├─ Answer: NO
├─ Action: CREATE TABLE ticket_order_202504 (...)
└─ INSERT orders into new table

3 months later:
├─ Old data: ticket_order_202501 (full, not changing)
├─ Current data: ticket_order_202504 (active)
└─ Archive policy: Can DROP old partitions
```

---

# CONDITIONAL_DB vs REDIS_LUA Trade-offs

## Side-by-Side Comparison

| Aspect | CONDITIONAL_DB | REDIS_LUA_WITH_COMPENSATION |
|--------|---|---|
| **Implementation** | SQL WHERE clause | Redis Lua script + DB fallback |
| **Atomicity** | ✅ Database level | ✅ Redis level (fast) + DB (authoritative) |
| **Throughput** | 38.64 req/ms | 354.33 req/ms (9x faster!) |
| **Latency** | ~5-50ms | ~1-5ms |
| **Bottleneck** | DB row lock | None (Redis in-memory) |
| **Failure Mode** | Clean (0 rows affected) | Compensation needed (Redis restore) |
| **Complexity** | Simple | Medium (need compensation logic) |
| **Consistency** | Guaranteed atomic | Eventually consistent + reconciliation |

---

## Why REDIS_LUA is Faster

**CONDITIONAL_DB bottleneck:**
```
T0: Thread 1 acquires lock on row 4
T1: Thread 1 evaluates WHERE clause
T2: Thread 1 applies UPDATE
T3: Thread 1 releases lock
T4: Thread 2 acquires lock (was waiting!)
T5: Thread 2 evaluates WHERE clause
    ...
    All threads queue on SINGLE ROW!

Result: Threads serialize (one at a time)
        Effective throughput: ~38 req/ms
```

**REDIS_LUA advantage:**
```
T0: Thread 1 calls Redis (in-memory)
T1: Thread 2 calls Redis (in-memory)
T2: Thread 3 calls Redis (in-memory)
    Lua script executes atomically in Redis
    No disk I/O, no network round-trip
    
Result: Threads run parallel (100+ concurrent)
        Effective throughput: ~354 req/ms
```

---

## Why You Can't Use Just Redis

**The problem:**

```
Redis is FAST but NOT AUTHORITATIVE

Scenario: Crash happens!
├─ Redis stock: 500 (in-memory)
├─ MySQL stock: 502 (on disk, authoritative)
├─ Server crashes
├─ Restart: Redis lost! (in-memory)
├─ MySQL survives (on disk)
└─ Result: Redis/DB mismatch

Solution: Use CONDITIONAL_DB as fallback + reconciliation
```

---

## Why REDIS_LUA_WITH_COMPENSATION is Best

**Combines the best of both worlds:**

```
Step 1: Redis Lua (fast gate)
├─ Atomic check-and-deduct in Redis
├─ 354 req/ms throughput
├─ Reject excess demand quickly
└─ Cost: ~1-2ms latency

Step 2: DB conditional update (authoritative)
├─ If Redis succeeded: Usually succeeds too
├─ If DB fails: Compensation (restore Redis)
├─ Guarantees no oversell
└─ Cost: +3-4ms latency

Step 3: Reconciliation (eventual consistency)
├─ Scheduled job: Compare Redis vs DB
├─ If mismatch: Restore Redis = DB truth
├─ Runs every 30 seconds
└─ Fixes any double-faults

Result: Fast (354 req/ms) + Safe (no oversell) + Consistent (reconciliation)
```

---

# Summary: The Complete Picture

## Why CONDITIONAL_DB Works

```
┌──────────────────────────────────────────────────────┐
│ MySQL CONDITIONAL_DB: Safe Baseline                 │
└──────────────────────────────────────────────────────┘

1. ISOLATION LEVEL: REPEATABLE_READ (MySQL default)
   └─ Each transaction sees consistent snapshot
   └─ Prevents dirty reads within a transaction

2. ATOMIC CHECK-AND-DEDUCT: WHERE stock >= quantity
   └─ One SQL statement = atomic operation
   └─ Database guarantees: Either UPDATE succeeds or fails (nothing in between)
   └─ Java receives: 1 row affected (success) or 0 rows affected (fail)

3. ROW-LEVEL LOCKING: id = ticketId
   └─ Only one thread can UPDATE same row at a time
   └─ Others wait for lock release
   └─ Ensures condition check and deduction happen atomically

4. MONTHLY PARTITIONS: ticket_order_YYYYMM
   └─ Each month is separate table
   └─ Query on Jan: scan only Jan table (faster)
   └─ Old data: archive or drop

5. PRIMARY KEY INDEX: id
   └─ Fast lookup (clustered index)
   └─ Direct access to row in ~1ms

RESULT: No overselling, no race conditions, guaranteed consistency!
```

---

## Why REDIS_LUA_WITH_COMPENSATION is Faster

```
┌──────────────────────────────────────────────────────┐
│ Redis Lua + Compensation: Fast & Safe                │
└──────────────────────────────────────────────────────┘

1. REDIS LUA SCRIPT: Atomic in-memory operation
   └─ No disk I/O
   └─ No network round-trip
   └─ Parallel execution (100s of threads)
   └─ Result: 354 req/ms throughput

2. DB FALLBACK: CONDITIONAL_DB as backup
   └─ Redis succeeds → Try DB
   └─ If DB fails → Compensation (restore Redis)
   └─ Ensures consistency

3. RECONCILIATION: Periodic sync
   └─ Compare Redis vs DB every 30 seconds
   └─ If mismatch: Restore Redis = DB truth
   └─ Fixes any double-faults

RESULT: Fast (Redis) + Safe (DB fallback) + Consistent (reconciliation)
```

---

## Learning Progression

```
Level 1: UNSAFE_DB (Where mistakes happen)
├─ Learn: What race conditions look like
├─ SQL: UPDATE without WHERE clause
└─ Outcome: Overselling (bad!)

Level 2: CONDITIONAL_DB (Safe baseline)
├─ Learn: Atomic check-and-deduct pattern
├─ SQL: UPDATE ... WHERE ... AND stock >= qty
├─ Mechanism: Row-level locking
└─ Outcome: No overselling (good!)
└─ Problem: Throughput bottleneck

Level 3: REDIS_LUA (Fast gate)
├─ Learn: Redis Lua scripts are atomic
├─ Implementation: In-memory check-and-deduct
├─ Mechanism: No disk I/O, parallel execution
└─ Problem: Redis/DB mismatch if crash

Level 4: REDIS_LUA_WITH_COMPENSATION (Best of both)
├─ Learn: Distributed transaction patterns
├─ Implementation: Redis gate + DB fallback + compensation
├─ Mechanism: Fast + safe + consistent
└─ Outcome: No overselling (good!) + fast (354 req/ms) + safe crash recovery
```

---

## Key Concepts You Should Understand Now

After reading this document, you should be able to explain:

- [ ] **Transaction Isolation Levels**: Why MySQL uses REPEATABLE_READ
- [ ] **Dirty Reads**: What happens with READ UNCOMMITTED
- [ ] **Non-Repeatable Reads**: Why READ COMMITTED causes problems
- [ ] **Phantom Reads**: Why REPEATABLE_READ isn't perfect
- [ ] **Why SERIALIZABLE is slow**: Lock contention
- [ ] **Atomic Check-And-Deduct**: How WHERE clause prevents race conditions
- [ ] **Row-level Locking**: Why only one thread can UPDATE same row
- [ ] **Index Types**: PRIMARY KEY, UNIQUE, REGULAR, COMPOSITE
- [ ] **Index Trade-offs**: Benefits vs costs
- [ ] **Partition by RANGE**: Why monthly tables are faster
- [ ] **Partition by HASH**: Why it reduces lock contention
- [ ] **CONDITIONAL_DB vs REDIS_LUA**: Speed vs complexity trade-off
- [ ] **Compensation Pattern**: How to fix Redis/DB mismatch

---

## Next Steps (Hands-On Practice)

In the next session, we'll:

1. **Run EXPLAIN** on actual queries
2. **Test race conditions** with concurrent transactions
3. **Measure throughput** differences
4. **Create custom indexes** and measure impact
5. **Trace compensation** in REDIS_LUA_WITH_COMPENSATION

---

**Document Complete:** Theoretical foundation ready! 🎓
