# Reviewer Guide

This project is a Spring Boot flash-sale concurrency lab. It uses a ticket-order fixture to show how different stock deduction strategies behave under concurrent demand. The strongest project claim is not "I built a ticketing platform"; it is "I built a reproducible backend lab that proves when stock deduction oversells, when it is safe, and how Redis/MySQL consistency is checked."

## What The Project Proves

| Proof area | Evidence in the repo |
|---|---|
| Oversell risk | `UNSAFE_DB` decrements DB stock without a stock predicate and can drive stock negative under load |
| DB-safe baseline | `CONDITIONAL_DB` uses one conditional DB update with `stockAvailable >= quantity` |
| Redis fast gate | `REDIS_LUA` atomically rejects excess demand in Redis before the DB write |
| Compensation rule | `REDIS_LUA_WITH_COMPENSATION` restores Redis if the DB or order-write phase fails after Redis decrement |
| Consistency visibility | `GET /admin/benchmarks/consistency` reports Redis stock, DB stock, order count, oversold rows, expected Redis stock, and drift |
| Reproducible benchmark flow | `benchmark/run-jmeter.ps1` writes raw JMeter samples, HTML report, consistency snapshot, `run.json`, and `summary-row.md` |

## Scope Boundaries

This repo intentionally stays narrow.

In scope:

- Stock correctness under high concurrency.
- Redis Lua pre-deduction and Redis compensation behavior.
- MySQL conditional updates as a safe baseline.
- Redis/MySQL drift detection and reconciliation.
- Local JMeter benchmarks and saved evidence.
- Optional dashboard for operators and reviewers.

Out of scope:

- Buyer account management.
- Payment gateways.
- Public admin hardening.
- Distributed workflow orchestration.
- Kubernetes or microservice deployment.
- A complete consumer ticket-sales product.

## Architecture At A Glance

```text
HTTP controllers
  -> application services
  -> stock deduction strategy registry
  -> domain services
  -> MySQL repositories and Redis cache adapters
```

The main order path is:

```text
TicketOrderController.createOrder
  -> TicketOrderAppServiceImpl.createOrder
  -> OrderCreationService.createOrder
  -> IdempotencyService.getOrCreate
  -> StockDeductionStrategyRegistry.get
  -> selected StockDeductionStrategy.decrease
  -> OrderDeductionDomainService.ensureMonthlyOrderTable
  -> OrderDeductionDomainService.insertOrder
```

MySQL is the durable source of truth. Redis is a fast gate/cache. The reconciliation job repairs Redis back to DB truth when drift appears.

## Strategy Comparison

| Strategy | Purpose | Safety | Reviewer interpretation |
|---|---|---|---|
| `UNSAFE_DB` | Demonstrate the race condition | Can oversell | A negative result here is the demonstration baseline |
| `CONDITIONAL_DB` | Simple safe DB baseline | Prevents oversell | Correct but DB row contention limits throughput |
| `REDIS_LUA` | Fast Redis gate without compensation | Prevents normal-path oversell, may drift | Useful for observing Redis/DB mismatch on later failure |
| `REDIS_LUA_WITH_COMPENSATION` | Redis gate with restore on later failure | Preferred lab strategy | Best story for fast rejection plus consistency repair |

## Reviewer Walkthrough

1. Read [ARCHITECTURE.md](./ARCHITECTURE.md) for module boundaries and the order path.
2. Read [CONCURRENCY_AND_CONSISTENCY.md](./CONCURRENCY_AND_CONSISTENCY.md) for the stock invariants and strategy behavior.
3. Read [BENCHMARKING.md](./BENCHMARKING.md) for how evidence is produced and interpreted.
4. Open Swagger locally at `http://localhost:1122/swagger-ui.html`.
5. Run `benchmark/smoke-local.ps1` to prove the reset, warmup, order, and consistency cycle.
6. Run `benchmark/run-jmeter.ps1` to generate benchmark evidence for one strategy.
7. Use the dashboard at `http://localhost:3000` only as an operator view over the backend proof.

## CV-Safe Framing

Strong wording:

```text
Built a Spring Boot flash-sale concurrency lab comparing unsafe DB updates, DB conditional updates, Redis Lua gating, and Redis compensation under concurrent load, with reproducible JMeter evidence and Redis/MySQL consistency checks.
```

Avoid:

- "Production-ready ticket sales platform."
- "Complete e-commerce system."
- "Payment-ready booking product."
- "Distributed microservices flash-sale platform."

Those claims are broader than the current repo. The real strength is the focused reliability proof.
