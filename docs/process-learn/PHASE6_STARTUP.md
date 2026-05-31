# Phase 6: Advanced Patterns & Scaling (June 29 - July 12)

**Timeline:** June 29 - July 12, 2026 (2 weeks)  
**Prerequisite:** Phase 5 complete with deployed system + monitoring in place  
**Goal:** Extend architecture with advanced distributed systems patterns  
**Deliverable:** `docs/ADVANCED_PATTERNS_AND_ARCHITECTURE.md`

---

## 📋 **Phase 6 Overview**

This phase deepens distributed systems knowledge by implementing real patterns:

1. **Event-driven architecture** → async processing
2. **CQRS (Command Query Responsibility Segregation)** → separate read/write models
3. **Saga pattern** → distributed transactions across services
4. **Microservices decomposition** → modularize monolith
5. **Distributed consensus** → handle network partitions

**Expected outcome:** Understand scaling patterns + implement 2-3 new features

---

## 🎯 **Week 1: June 29 - July 5**

### **Day 1-2 (June 29-30): Event-Driven Architecture**

**Scope:** Add event streaming for order creation

**Task 1: Design Event Schema**

- [ ] Define core events:
  - [ ] `OrderCreatedEvent` (ticket_id, quantity, user_id, timestamp)
  - [ ] `OrderFailedEvent` (ticket_id, reason, user_id)
  - [ ] `StockDriftDetectedEvent` (redis_count, db_count, diff)
- [ ] Create event definition file: `src/main/java/com/.../event/DomainEvent.java`
- [ ] Design event bus (Spring Events vs Kafka):
  - [ ] Option A: Spring ApplicationEventPublisher (simple, local)
  - [ ] Option B: Apache Kafka (distributed, production-grade)
  - [ ] Recommendation: Start with Spring Events (Day 1), migrate to Kafka (Day 2)

**Task 2: Implement Event Publishing**

- [ ] Modify `OrderDeductionDomainService.decrease()`:
  - [ ] After successful stock deduction → publish `OrderCreatedEvent`
  - [ ] On failure → publish `OrderFailedEvent`
- [ ] Create event publisher:
  ```java
  @Component
  public class DomainEventPublisher {
    @Autowired ApplicationEventPublisher publisher;
    
    public void publishOrderCreated(OrderCreatedEvent event) {
      publisher.publishEvent(event);
    }
  }
  ```
- [ ] Test event publishing with unit tests

**Task 3: Implement Event Listeners**

- [ ] Create listeners for business logic:
  - [ ] `OrderMetricsListener` → increment counters
  - [ ] `OrderAuditListener` → log to audit table
  - [ ] `InventorySyncListener` → sync Redis ↔ DB
- [ ] Configure async processing:
  ```yaml
  spring:
    task:
      execution:
        pool:
          core-size: 5
          max-size: 10
  ```
- [ ] Test end-to-end: order creation → event → listener execution

**Deliverable:** Event-driven order creation with 3+ listeners

---

### **Day 3-4 (July 1-2): CQRS Pattern**

**Scope:** Separate read model from write model

**Task 1: Design Read Model**

- [ ] Create read-optimized schema:
  ```sql
  CREATE TABLE order_read_model (
    id BIGINT PRIMARY KEY,
    order_number VARCHAR(50) UNIQUE,
    user_id BIGINT,
    ticket_id BIGINT,
    quantity INT,
    status ENUM('CREATED', 'FAILED'),
    created_at TIMESTAMP,
    INDEX idx_user_id (user_id),
    INDEX idx_ticket_id (ticket_id)
  );
  ```
- [ ] Create separate repository:
  - [ ] `OrderReadRepository` (Spring Data for querying)
  - [ ] `OrderReadModelProjector` (updates read model from events)

**Task 2: Implement Projections**

- [ ] Create projector that listens to events:
  ```java
  @Component
  public class OrderReadModelProjector {
    @EventListener
    public void onOrderCreated(OrderCreatedEvent event) {
      // Insert into order_read_model
    }
  }
  ```
- [ ] Handle eventual consistency:
  - [ ] Read model may lag write model by milliseconds
  - [ ] Add version field to detect staleness
  - [ ] Add cache invalidation on update
- [ ] Test projection: create order → verify read model populated

**Task 3: Query Optimization with Read Model**

- [ ] Create optimized queries:
  - [ ] `getOrdersByUserId()` (previously required JOIN)
  - [ ] `getOrdersByStatus()` (now direct index lookup)
  - [ ] `getOrdersCreatedToday()` (single table scan)
- [ ] Measure query latency:
  - [ ] Before CQRS: ___ ms
  - [ ] After CQRS: ___ ms (target: 50% reduction)
- [ ] Update `TicketOrderAppService` to use read model for queries

**Deliverable:** CQRS implementation with read model projection + query improvements

---

### **Day 5-6 (July 3-4): Saga Pattern for Distributed Transactions**

**Scope:** Handle complex multi-step order flows

**Task 1: Define Saga Workflow**

- [ ] Create new order flow:
  ```
  1. Deduct stock (write to DB + Redis)
  2. Process payment (call payment service stub)
  3. Send confirmation email (async)
  4. Update analytics (event stream)
  
  If any step fails → compensate (reverse previous steps)
  ```
- [ ] Document compensation steps:
  - [ ] Payment fails → restore stock to Redis/DB
  - [ ] Email fails → log error (don't block)
  - [ ] Analytics fails → queue for retry

**Task 2: Implement Choreography-based Saga**

- [ ] Create saga state machine:
  ```java
  enum SagaStep {
    STOCK_DEDUCTED,
    PAYMENT_PROCESSED,
    EMAIL_SENT,
    ANALYTICS_UPDATED,
    COMPLETED,
    FAILED
  }
  ```
- [ ] Implement saga orchestrator:
  ```java
  @Service
  public class OrderSagaOrchestrator {
    public void executeOrderSaga(CreateOrderRequest req) {
      // Step 1: Deduct stock
      // Step 2: Call payment service
      // Step 3: Send email
      // Step 4: Update analytics
      // If any fails: compensate
    }
  }
  ```
- [ ] Test happy path: all steps succeed
- [ ] Test failure paths: payment fails, email fails, etc.

**Task 3: Compensation Logic**

- [ ] Implement rollback handlers:
  - [ ] `compensateStockDeduction()` → restore to Redis + DB
  - [ ] `compensatePayment()` → refund via payment API
  - [ ] `compensateEmail()` → log for manual review
- [ ] Store compensation events:
  ```sql
  CREATE TABLE saga_compensation_log (
    saga_id UUID,
    step VARCHAR(50),
    status ENUM('PENDING', 'COMPLETED', 'FAILED'),
    error_msg TEXT
  );
  ```
- [ ] Test saga failure recovery: manual execution of compensations

**Deliverable:** Distributed saga pattern with compensation logic + test cases

---

### **Day 7 (July 5): Week 1 Synthesis**

- [ ] Update `LEARNING_JOURNEY.md`: Week 1 complete
- [ ] Create `notes/Week1-Advanced-Patterns-Summary.md`:
  - [ ] Event-driven: What/Why/How implemented
  - [ ] CQRS: Query improvements (before/after latency)
  - [ ] Saga: Compensation scenarios tested
  - [ ] Challenges encountered & solutions
- [ ] Prepare architecture diagram:
  - [ ] Show event flows between components
  - [ ] Show CQRS read/write separation
  - [ ] Show saga compensation paths

**Deliverable:** Week 1 summary + architecture updates

---

## 🎯 **Week 2: July 6-12**

### **Day 8-9 (July 6-7): Microservices Decomposition**

**Scope:** Identify service boundaries from monolith

**Task 1: Analyze Domain Boundaries**

- [ ] Review current modules:
  - [ ] `xxxx-domain` → Stock management domain
  - [ ] `xxxx-application` → Order orchestration
  - [ ] `xxxx-infrastructure` → Persistence
- [ ] Identify new services:
  - [ ] **Stock Service:** Stock deduction, inventory sync
  - [ ] **Payment Service:** (stub) Process payments
  - [ ] **Notification Service:** Email, SMS
  - [ ] **Analytics Service:** Event processing, metrics
- [ ] Create dependency map: Which service talks to which?

**Task 2: Extract Payment Service**

- [ ] Create new module: `xxxx-payment-service`
- [ ] Define API contract:
  ```
  POST /payments
  {
    "orderId": "123",
    "amount": 100.00,
    "userId": "user-1"
  }
  
  Response:
  {
    "paymentId": "pay-456",
    "status": "COMPLETED"
  }
  ```
- [ ] Implement stub service:
  ```java
  @RestController
  @RequestMapping("/payments")
  public class PaymentController {
    @PostMapping
    public ResponseEntity<PaymentResponse> process(PaymentRequest req) {
      // Simulate: random 90% success, 10% failure
      return success ? 200 : 400;
    }
  }
  ```
- [ ] Integrate with main service: order saga calls payment API

**Task 3: Extract Notification Service**

- [ ] Create new module: `xxxx-notification-service`
- [ ] Define async messaging:
  - [ ] Subscribe to `OrderCreatedEvent`
  - [ ] Send email (stub: just log)
- [ ] Configuration:
  - [ ] If event-driven → listen to Spring Events
  - [ ] If message queue → consume from RabbitMQ/Kafka
- [ ] Test: order created → email notification sent

**Deliverable:** Payment + Notification microservices extracted + integrated

---

### **Day 10-11 (July 8-9): Distributed Consensus**

**Scope:** Handle leader election & consensus scenarios

**Task 1: Study Consensus Algorithms**

- [ ] Research:
  - [ ] Raft algorithm (easier to understand)
  - [ ] Paxos algorithm (production: Chubby, Zookeeper)
  - [ ] Distributed consensus in Redis/MySQL
- [ ] Read: `docs/CONCURRENCY_AND_CONSISTENCY.md` (CAP theorem section)
- [ ] Document findings: `notes/Consensus-Algorithms-Research.md`

**Task 2: Implement Simple Leader Election**

- [ ] Use Redis for distributed lock:
  ```java
  @Component
  public class LeaderElection {
    public boolean becomeLeader(String serviceName, Duration duration) {
      return redisTemplate.opsForValue()
        .setIfAbsent(serviceName + "-leader", instanceId, duration);
    }
  }
  ```
- [ ] Use for scheduled batch jobs:
  - [ ] Reconciliation job: only leader runs
  - [ ] Cleanup job: only leader runs
- [ ] Test: start multiple instances, verify only 1 is leader

**Task 3: Handle Split-Brain Scenarios**

- [ ] Simulate network partition:
  - [ ] Pause Redis container
  - [ ] Verify: services can't acquire lock
  - [ ] When Redis recovers: leader re-elected
- [ ] Document behavior:
  - [ ] What happens during partition?
  - [ ] How is consistency maintained?
  - [ ] What data could be inconsistent?
- [ ] Create test case: partition → recovery → verification

**Deliverable:** Distributed leader election + split-brain recovery tests

---

### **Day 12 (July 10): Integration & Testing**

**Scope:** Verify all patterns work together

**Task 1: End-to-End Integration Test**

- [ ] Scenario: Create order with full saga
  - [ ] Stock deducted (event published)
  - [ ] Payment processed (saga step)
  - [ ] Email sent (event listener)
  - [ ] Analytics recorded (event listener)
  - [ ] Read model updated (CQRS projector)
- [ ] Verify: all systems synchronized
- [ ] Run under load:
  ```bash
  .\benchmark\run-jmeter.ps1 -Strategy "REDIS_LUA_WITH_COMPENSATION" \
    -Threads 50 -Requests 5000
  ```
- [ ] Check: no data inconsistencies

**Task 2: Failure Recovery Test**

- [ ] Simulate failures:
  - [ ] Stop payment service → saga compensates
  - [ ] Stop notification service → saga continues
  - [ ] Stop Redis → saga fails gracefully
- [ ] Verify recovery:
  - [ ] All compensations logged
  - [ ] System returns to consistent state
  - [ ] Alerts/metrics reflect failures

**Deliverable:** Integration tests + failure recovery verification

---

### **Day 13 (July 11-12): Documentation & Portfolio**

**Task 1: Create Architecture Document**

- [ ] Write `docs/ADVANCED_PATTERNS_AND_ARCHITECTURE.md`:
  ```
  # Advanced Patterns & Scaling

  ## 1. Event-Driven Architecture
  - Problem: Tight coupling between order & analytics
  - Solution: Publish OrderCreatedEvent
  - Result: Decoupled systems, async processing
  
  ## 2. CQRS Pattern
  - Problem: Complex queries on write-optimized schema
  - Solution: Maintain separate read model
  - Result: ___ % query latency improvement
  
  ## 3. Saga Pattern
  - Problem: Multi-step distributed transactions
  - Solution: Choreography-based saga with compensation
  - Result: Reliable multi-service workflows
  
  ## 4. Microservices
  - Services: Stock, Payment, Notification, Analytics
  - Communication: REST + events
  - Deployment: Each service in own container
  
  ## 5. Distributed Consensus
  - Leader election: Redis-based distributed lock
  - Scenarios tested: Network partition, leader failure
  - Recovery: Automatic re-election
  ```

**Task 2: Interview Preparation**

- [ ] Prepare talking points:
  - [ ] "Implemented event-driven architecture for loose coupling"
  - [ ] "Applied CQRS to optimize query performance by ___% "
  - [ ] "Designed saga pattern with compensation for distributed transactions"
  - [ ] "Decomposed monolith into microservices (Stock, Payment, Notification)"
  - [ ] "Implemented distributed consensus for reliable leader election"
- [ ] Diagram: System architecture after Phase 6
  - [ ] Show service boundaries
  - [ ] Show event flows
  - [ ] Show data consistency guarantees

**Task 3: Update Learning Journey**

- [ ] Update `LEARNING_JOURNEY.md`:
  - [ ] Mark Phase 6 as ✅ COMPLETE
  - [ ] Add link to advanced patterns document
  - [ ] List skills acquired
  - [ ] Preview Phase 7

**Deliverable:** `docs/ADVANCED_PATTERNS_AND_ARCHITECTURE.md` + interview story + diagrams

---

## 💼 **Interview Talking Points**

After Phase 6, you can discuss:

1. **Event-Driven Design**
   - "Implemented event publishing to decouple services"
   - "Event listeners handle analytics, auditing, and notifications"
   - "System is now more scalable and maintainable"

2. **CQRS & Read Models**
   - "Optimized query performance by separating read model"
   - "Achieved ___% latency reduction through projections"
   - "Handled eventual consistency challenges"

3. **Distributed Transactions**
   - "Implemented saga pattern for multi-service workflows"
   - "Designed compensation logic for failure scenarios"
   - "Tested and verified recovery from failures"

4. **Microservices Thinking**
   - "Identified service boundaries using domain-driven design"
   - "Extracted Payment & Notification services from monolith"
   - "Designed REST APIs and async communication"

5. **Distributed Systems**
   - "Implemented distributed leader election using Redis"
   - "Tested split-brain scenarios and recovery"
   - "Understands CAP theorem trade-offs in our design"

---

## 🎓 **Skills Achieved**

- ✅ Event-driven architecture
- ✅ CQRS pattern (Command Query Responsibility Segregation)
- ✅ Saga pattern (distributed transactions with compensation)
- ✅ Microservices decomposition
- ✅ Distributed consensus & leader election
- ✅ Async processing & event streams
- ✅ Complex system integration testing

---

## 📊 **Comparison: Before & After Phase 6**

| Aspect | Before | After |
|--------|--------|-------|
| Architecture | Monolith | Microservices + Events |
| Data Consistency | Strong (DB transactions) | Eventual (saga compensation) |
| Coupling | Tight (direct calls) | Loose (events) |
| Scalability | Limited | High (independent services) |
| Query Performance | Complex joins | Optimized read model |
| Failure Recovery | Limited | Compensation-based |

---

## 📍 **Next Steps (Phase 7)**

After completing Phase 6, you're ready for:

**Phase 7 - Portfolio & Interview Prep**
- Polish GitHub repository
- Write CV bullets with architectural accomplishments
- Create demo video walkthrough
- Prepare 5-minute "system design" elevator pitch
- Document challenges & lessons learned

---

## 📚 **Reference Materials**

- Event-Driven: Spring Framework documentation (ApplicationEvent)
- CQRS: Microsoft patterns & practices guide
- Saga: Microservices patterns (Chris Richardson)
- Distributed Systems: Designing Data-Intensive Applications (Martin Kleppmann)

---

**Status:** 📋 PLANNED  
**Estimated Duration:** 2 weeks (intensive)  
**Difficulty:** ⭐⭐⭐⭐⭐ (advanced)  
**Portfolio Value:** ⭐⭐⭐⭐⭐ (very high - shows architecture thinking)

