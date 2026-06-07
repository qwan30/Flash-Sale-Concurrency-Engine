# Phase 6 Learning Guide: Advanced Distributed Systems & Scaling
**Status:** 📋 PLANNED (Not yet executed)  
**Timeline:** June 29 - July 12, 2026 (2 weeks)  
**Prerequisite:** Phase 5 complete with optimized & monitored system  

---

## 🎯 **Phase 6 Mission**

Transform a **single-node optimized system** into a **horizontally scalable, event-driven architecture** with advanced distributed patterns.

**Success Criteria (To Be Achieved):**
- ⏳ Implement event-driven architecture (OrderCreatedEvent publishing)
- ⏳ Build CQRS pattern (separate read/write models)
- ⏳ Implement Saga pattern (distributed transactions)
- ⏳ Decompose monolith into microservices (3+ services)
- ⏳ Add distributed consensus algorithm (Raft or similar)
- ⏳ Achieve 10x scalability (from single instance to distributed cluster)
- ⏳ Create ADVANCED_PATTERNS_AND_ARCHITECTURE.md with implementation details

---

## 📚 **What is Distributed Architecture?**

**Definition:** Breaking a monolithic system into multiple independent services that communicate through messages/APIs.

**Why?**
- **Scalability:** Each service scales independently
- **Resilience:** One service failing doesn't take down the whole system
- **Agility:** Teams can develop independently
- **Tech diversity:** Different services can use different tech stacks

**Cost:** Complexity increases 5-10x (debugging, monitoring, consistency)

---

## 🏗️ **Architecture Evolution Path**

### **Level 1: Monolith (Current - Phase 5)**
```
┌─────────────────────────┐
│   Single Application    │
├─────────────────────────┤
│ Controller → Service    │
│ Domain → Infrastructure │
│ Data Access → MySQL     │
└─────────────────────────┘
    ↓
    └─ Redis (cache)
    └─ MySQL (database)

Challenges:
  ❌ Can't scale controller independently from domain
  ❌ All deployments require full app restart
  ❌ One slow query affects entire system
  ❌ Hard to adopt new technologies
```

### **Level 2: Monolith with Events (Day 1-2)**
```
┌──────────────────────────────┐
│   Single Application         │
├──────────────────────────────┤
│ OrderCreatedEvent published  │
└──────────────────────────────┘
    ↓
    ├─ Events (Spring Application Events)
    ├─ Order Service (deduction logic)
    ├─ Notification Service (send emails)
    └─ Reconciliation Service (drift repair)

Benefits:
  ✅ Decouples order creation from notifications
  ✅ Can run services independently
  ⚠️ Still one process (can't scale separately)
```

### **Level 3: CQRS (Day 3-4)**
```
┌─────────────────────────┐
│   Write Model (Command) │
├─────────────────────────┤
│ POST /orders            │
│ Deduct from Redis       │
│ Update MySQL            │
└─────────────────────────┘
         ↓
    Publish Event
         ↓
┌─────────────────────────┐
│   Read Model (Query)    │
├─────────────────────────┤
│ GET /orders (fast)      │
│ Query read-optimized DB │
│ (denormalized, indexed) │
└─────────────────────────┘

Benefits:
  ✅ Reads optimized independently from writes
  ✅ Read DB can be denormalized (flat tables, no joins)
  ✅ Can scale read replicas 10x more than writes
  ⚠️ Eventual consistency (read data might be 100ms stale)
```

### **Level 4: Distributed Saga (Day 5-6)**
```
Step 1: Create Order
  Order Service: Deduct stock → emit "StockDeducted"
  
Step 2: Process Payment
  Payment Service: Charge card → emit "PaymentProcessed"
  
Step 3: Send Notification
  Notification Service: Send email → emit "EmailSent"
  
If Step 2 fails (card declined):
  → Compensation: Order Service refunds stock (INCR)
  → Compensation: Notification Service (don't send email)
  → Final state: Order canceled, stock restored

Benefits:
  ✅ Multi-step transactions across services
  ✅ Automatic compensation on failure
  ✅ No distributed locks (no deadlocks)
  ⚠️ Complexity: must handle idempotency
```

### **Level 5: Microservices (Day 7-11)**
```
                     ┌─────────────┐
                     │   Gateway   │
                     └─────────────┘
                      ↙   ↓   ↘
          ┌─────────────────────────────────────┐
          │         Service Network             │
          ├─────────────────────────────────────┤
          │                                     │
    ┌──────────────┐  ┌─────────────┐  ┌──────────────┐
    │ Order Service│  │Payment Svc  │  │Notification │
    ├──────────────┤  ├─────────────┤  ├──────────────┤
    │ MySQL 1      │  │ MySQL 2     │  │ Kafka queue  │
    │ Redis 1      │  │ Redis 2     │  │ (async)      │
    └──────────────┘  └─────────────┘  └──────────────┘

Benefits:
  ✅ Independent scaling of each service
  ✅ Independent deployment (CI/CD per service)
  ✅ Technology diversity (use best tool per service)
  ✅ Team autonomy (separate teams own services)
  ⚠️ Operational complexity (10x harder to debug)
  ⚠️ Network complexity (network is unreliable)
```

### **Level 6: Distributed Consensus (Day 12-13)**
```
                  ┌─────────────────────┐
                  │  Consensus Protocol │
                  │  (Raft, Paxos)      │
                  └─────────────────────┘
                    ↙   ↓        ↘
        ┌─────────────────────────────────┐
        │   Replicated State Machine      │
        ├─────────────────────────────────┤
        │ Server 1   Server 2   Server 3  │
        │ (Leader)   (Follower) (Follower)│
        │                                 │
        │ All have exact same state       │
        │ Leader handles writes           │
        │ If leader fails → new leader    │
        └─────────────────────────────────┘

Benefits:
  ✅ Handles leader election automatically
  ✅ Prevents split-brain scenarios
  ✅ Data consistency across replicas
  ⚠️ Requires odd number of servers (3, 5, 7...)
  ⚠️ Network partitions cause temporary unavailability
```

---

## 🔑 **Key Patterns**

### **Pattern 1: Event Sourcing**

**Concept:** Instead of storing current state, store all state-changing events.

**Example:**
```
Traditional:
  Table: users
  ┌────────┬────────┐
  │ id     │ balance│
  ├────────┼────────┤
  │ user:1 │ $900   │  ← Current state only
  └────────┴────────┘

Event Sourcing:
  Table: user_events (append-only log)
  ┌────────┬──────────────────────┬─────────────┐
  │ event  │ type                 │ amount      │
  ├────────┼──────────────────────┼─────────────┤
  │ evt:1  │ AccountCreated       │ init $1000  │
  │ evt:2  │ MoneyTransferred     │ -$50        │
  │ evt:3  │ InterestApplied      │ +$5         │
  │ evt:4  │ PurchaseCompleted    │ -$55        │
  └────────┴──────────────────────┴─────────────┘
  
  Current state derived by replaying: $1000 - $50 + $5 - $55 = $900
```

**Benefits:**
- ✅ Complete audit trail (every change recorded)
- ✅ Can replay to any point in time (debugging)
- ✅ No lost information (even deletes are events)

**Phase 6 application:** Track all order events (created, deducted, compensated)

### **Pattern 2: Eventual Consistency**

**Concept:** Accept temporary inconsistency to gain performance/scalability.

**Example:**
```
Strongly Consistent (ACID):
  1. Deduct from Redis (instant)
  2. Deduct from MySQL (wait for reply)
  3. Send response to client (only after both succeed)
  
  Cost: Latency = Redis latency + MySQL latency
  Benefit: Always accurate

Eventual Consistency (BASE):
  1. Deduct from Redis (instant)
  2. Send response to client (immediately)
  3. Update MySQL asynchronously (might take 100ms)
  
  Cost: Client sees stale data for 100ms
  Benefit: 10x faster response time
  Reconciliation: Periodic job checks Redis vs MySQL drift
```

**Phase 6 application:** Read model (CQRS) might be 100ms behind write model

### **Pattern 3: Idempotency**

**Concept:** Same request applied multiple times produces same result.

**Non-idempotent:** `account.balance += 100` (applying twice = wrong)
```
Call 1: $500 → $600
Call 2: $600 → $700  ❌ Wrong!
```

**Idempotent:** `account.balance = 600` (applying twice = same)
```
Call 1: → $600
Call 2: → $600  ✅ Correct!
```

**Implementation:**
- Use request ID / idempotency key
- Store "already processed" in database
- On retry, return cached result

**Phase 6 application:** Saga steps must be idempotent (might retry on network failure)

---

## 📅 **Week 1: Days 1-7 (June 29 - July 5)**

### **Days 1-2: Event-Driven Architecture**

**Goal:** Decouple order creation from side effects

**Concepts Learned:**
1. Event-driven messaging
2. Publisher-subscriber pattern
3. Eventual consistency
4. Dead letter queues

**Implementation Steps:**
1. Define domain events:
   - `OrderCreatedEvent` (ticket_id, qty, user_id)
   - `OrderFailedEvent` (ticket_id, reason)
   - `StockDriftDetectedEvent` (redis_count, db_count)

2. Implement event publisher:
   ```java
   @Component
   public class DomainEventPublisher {
     @Autowired ApplicationEventPublisher applicationEventPublisher;
     
     public void publishOrderCreatedEvent(Order order) {
       OrderCreatedEvent event = new OrderCreatedEvent(order);
       applicationEventPublisher.publishEvent(event);
     }
   }
   ```

3. Create event listeners:
   ```java
   @Component
   public class OrderEventListener {
     @EventListener
     public void onOrderCreated(OrderCreatedEvent event) {
       // Send notification, update analytics, etc.
     }
   }
   ```

4. Benefits:
   - ✅ Decouple notification service from order service
   - ✅ Can add new listeners without touching order code
   - ✅ Async processing (non-blocking)

**Deliverable:** Event publishers + 3 listeners working

### **Days 3-4: CQRS Pattern**

**Goal:** Separate read and write paths for scalability

**Concepts Learned:**
1. Command vs Query
2. Read model vs write model
3. Eventual consistency
4. Denormalization

**Implementation Steps:**

1. **Write Side:** Keep existing order deduction logic
   - Takes commands: `CreateOrderCommand`
   - Updates authoritative MySQL
   - Publishes events

2. **Read Side:** Create read-optimized model
   ```sql
   -- Write model (normalized)
   CREATE TABLE ticket_order (
     id INT, ticket_id INT, user_id INT, created_at TIMESTAMP
   );
   
   -- Read model (denormalized, flat)
   CREATE TABLE order_view (
     order_id INT,
     ticket_id INT,
     ticket_name VARCHAR,
     ticket_price DECIMAL,
     user_id INT,
     user_email VARCHAR,
     order_status VARCHAR,
     created_at TIMESTAMP,
     INDEX (user_id),
     INDEX (ticket_id)
   );
   ```

3. **Projection:** Event listener populates read model
   ```java
   @EventListener
   public void onOrderCreated(OrderCreatedEvent event) {
     // Insert into order_view (denormalized)
   }
   ```

4. **Query Endpoint:** Read from denormalized model
   ```java
   // GET /orders (now super fast - no joins!)
   public List<OrderView> getOrders(String userId) {
     return orderViewRepository.findByUserId(userId);
   }
   ```

**Benefits:**
- ✅ Reads use flat table (no joins) = 10x faster
- ✅ Can add read replicas independently
- ✅ Read DB can be optimized differently (column store, etc.)

**Deliverable:** Read model working, queries 10x faster

### **Days 5-6: Saga Pattern**

**Goal:** Handle multi-step distributed transactions

**Concepts Learned:**
1. Orchestration-based saga
2. Choreography-based saga
3. Compensation logic
4. Saga state machine

**Implementation Steps:**

1. **Define Saga Flow:**
   ```
   Step 1: Order Created
     → Deduct stock from Redis
     → Publish StockDeductedEvent
   
   Step 2: Reserve Payment
     → Call payment service
     → If failed → compensate (refund stock)
   
   Step 3: Send Confirmation
     → Queue email notification
     → If failed → log (don't compensate)
   ```

2. **Implement Saga Orchestrator:**
   ```java
   @Service
   public class CreateOrderSaga {
     public OrderSagaResult execute(CreateOrderCommand cmd) {
       // Step 1: Deduct stock
       if (!deductStock(cmd)) {
         return FAILED; // Natural boundary
       }
       
       // Step 2: Reserve payment
       if (!reservePayment(cmd)) {
         compensateStock(cmd); // Compensation
         return FAILED;
       }
       
       // Step 3: Send notification (no compensation if fails)
       sendNotification(cmd);
       return SUCCESS;
     }
   }
   ```

3. **Compensation Logic:**
   ```java
   private void compensateStock(CreateOrderCommand cmd) {
     // Reverse the deduction
     redisClient.incrby(
       "stock:ticket:" + cmd.ticketId,
       cmd.quantity
     );
   }
   ```

**Deliverable:** Saga handling 3-step process with automatic compensation

### **Day 7: Integration Testing**

**Goal:** Verify all patterns work together

**Tests:**
- [ ] Event published after order creation
- [ ] Read model updated 100ms after write
- [ ] Failed payment triggers compensation
- [ ] Idempotent operations succeed on retry

**Deliverable:** Integration test suite (20+ tests)

---

## 📅 **Week 2: Days 8-13 (July 6 - July 12)**

### **Days 8-9: Microservices Decomposition**

**Goal:** Extract services from monolith

**Services to Extract:**

1. **Order Service**
   - Responsibility: Stock deduction, order creation
   - Database: MySQL (order_item table)
   - API: POST /orders, GET /orders/{id}

2. **Payment Service**
   - Responsibility: Payment processing
   - Database: MySQL (payment table)
   - API: POST /payments, GET /payments/{id}

3. **Notification Service**
   - Responsibility: Email/SMS sending
   - Database: None (stateless)
   - API: POST /notifications (async queue)

**Decomposition Steps:**

1. Create service folder: `app/backend/xxxx-payment-service`
2. Move payment logic from monolith
3. Create API gateway to route requests
4. Implement service discovery (Spring Cloud)

**Deliverable:** 3 services deployable independently

### **Days 10-11: Distributed Consensus**

**Goal:** Handle leader election & replicas

**Concepts:**
- Raft consensus algorithm
- Leader election
- State machine replication
- Split-brain prevention

**Implementation:**
- Use Zookeeper or etcd for coordination
- Detect leader crashes (heartbeat)
- Auto-elect new leader
- Ensure write consistency

**Deliverable:** Cluster of 3+ nodes, self-healing from failures

### **Days 12-13: Integration & Testing**

**Goal:** Verify end-to-end distributed system

**Tests:**
- [ ] Create order across Order Service
- [ ] Payment Service processes async
- [ ] Notification Service sends email
- [ ] Stop Order Service → orders queue
- [ ] Restart Order Service → orders resume
- [ ] Stop leader → new leader elected

**Deliverable:** Full integration test suite

---

## 💡 **Expected Learning Outcomes**

**By end of Phase 6, you'll understand:**

1. **Why systems decompose into services**
   - Scalability: Each service scales independently
   - Reliability: One failure doesn't cascade
   - Agility: Ship features without full deployment

2. **Trade-offs in distributed systems**
   - Consistency vs Availability (CAP theorem)
   - Latency vs Throughput (Pareto principle)
   - Complexity vs Benefits (when to decompose?)

3. **How to handle failures at scale**
   - Compensation (undo on failure)
   - Idempotency (safe to retry)
   - Eventual consistency (accept temp staleness)

4. **Operational challenges**
   - Debugging is 10x harder (data spread across services)
   - Network is unreliable (expect failures)
   - Monitoring is critical (end-to-end tracing)

---

## 🎓 **Interview Questions You'll Be Ready For**

**Q: "Describe a distributed system you've built"**
- Story: Started with monolith (Phase 5), extracted services (Phase 6), implemented saga pattern for multi-step transactions
- Numbers: "Scaled from 1,330 req/sec (monolith) to 3,990 req/sec (distributed with 3 services)"

**Q: "What's eventual consistency? When is it OK?"**
- Answer: "Temp data staleness (100ms) acceptable for 10x speed gain. Used in read model (CQRS)"
- Example: "User's order appears in read model 100ms after write. Reconciliation job detects drift."

**Q: "How do you handle failures in microservices?"**
- Answer: "Saga pattern with compensation. If payment fails, auto-refund stock."
- Example: "Implemented 3-step saga: deduct stock → reserve payment → send notification"

**Q: "What's the hardest part of distributed systems?"**
- Answer: "Network is unreliable. Must handle partial failures, timeouts, split-brain scenarios"
- Example: "If network partition separates Service A from Service B, must not accept conflicting writes"

---

## 🚀 **Success Checklist**

**By end of Week 2, you should have:**

- [ ] ✅ Event-driven architecture (events publishing)
- [ ] ✅ CQRS pattern (read model 10x faster)
- [ ] ✅ Saga pattern (multi-step transactions with compensation)
- [ ] ✅ Microservices (Order, Payment, Notification)
- [ ] ✅ Distributed consensus (leader election)
- [ ] ✅ Integration tests (20+ tests passing)
- [ ] ✅ ADVANCED_PATTERNS_AND_ARCHITECTURE.md written
- [ ] ✅ System handles failures gracefully
- [ ] ✅ 10x scalability demonstrated

---

## 📁 **Deliverables Summary**

| Period | Deliverable | Success Criteria |
|--------|-------------|------------------|
| Week 1, Days 1-2 | Event-driven impl | OrderCreatedEvent publishing |
| Week 1, Days 3-4 | CQRS impl | Read model 10x faster |
| Week 1, Days 5-6 | Saga impl | Multi-step with compensation |
| Week 1, Day 7 | Integration tests | 20+ tests passing |
| Week 2, Days 8-9 | Microservices | 3 services deployed |
| Week 2, Days 10-11 | Consensus algo | Leader election working |
| Week 2, Days 12-13 | E2E testing | Resilience verified |
| Week 2, Day 13 | ADVANCED_PATTERNS_AND_ARCHITECTURE.md | Comprehensive guide |

---

## 📚 **Reference Materials**

- Event-Driven Architecture: [Building Event-Driven Microservices](https://learning.oreilly.com/library/view/building-event-driven-microservices/)
- CQRS Pattern: [CQRS by Martin Fowler](https://martinfowler.com/bliki/CQRS.html)
- Saga Pattern: [Sagas by Chris Richardson](https://microservices.io/patterns/data/saga.html)
- Consensus: [Raft Consensus](https://raft.io/)
- Distributed Systems: [Designing Data-Intensive Applications](https://www.oreilly.com/library/view/designing-data-intensive-applications/9781491902141/)

---

**Status:** 📋 PLANNED (Ready to start after Phase 5 completes)  
**Target Completion:** July 12, 2026  
**Estimated Time:** 80-100 hours  
**Difficulty:** ⭐⭐⭐⭐⭐ (Expert)  
**Current Progress:** 0% - awaiting Phase 5 completion

