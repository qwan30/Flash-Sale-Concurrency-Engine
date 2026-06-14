# ADR-003: Redis as Fast Gate, Not Source of Truth

> **Status**: Accepted | **Date**: 2026-04-27 | **Deciders**: qwan30

## Context

Flash-sale needs high-throughput gating AND guaranteed stock correctness. Two stores: MySQL (ACID, durable) and Redis (in-memory, sub-ms, Lua atomic). Question: should Redis be authoritative, or MySQL?

## Decision

**MySQL is the durable source of truth. Redis is a fast gate/cache — never authoritative.**

```text
REDIS_LUA_WITH_COMPENSATION:
1. Redis Lua: atomic check-and-decrement TICKET:{id}:STOCK
2. Redis rejects → fast rejection (no DB call)
3. Redis accepts → DB conditional UPDATE WHERE stock_available >= qty
4. DB rejects → restore Redis (compensation)
5. DB accepts → Redis = DB (consistent)
```

Reconciliation (every 30s) repairs any drift: `setStockCache(id, dbStock)`.

## Alternatives

| Alternative | Why Rejected |
|---|---|
| Redis primary | No ACID; data loss risk on restart |
| DB-only | Contention bottleneck (173 req/s vs 443 req/s) |
| Two-phase commit (XA) | Redis doesn't support XA; massive complexity |

## Evidence

Benchmark (2026-05-31, ACER, 5000 req, 100 threads):

| Strategy | Throughput | Oversold | Drift |
|---|---|---|---|
| CONDITIONAL_DB (DB-only) | 173 req/s | 0 | 0 |
| REDIS_LUA_WITH_COMPENSATION | **443 req/s** | **0** | **0** |

Redis-as-gate: **2.5× throughput** while maintaining zero oversell and zero drift.

## Consequences

**Positive**: Fast rejection, durable correctness, observable/repairable drift, reconcilable
**Negative**: Compensation logic needed, possible drift until reconciliation, Redis keys must be warmed

## References
- `StockOrderCacheService.java`, `ConsistencyCheckService.java`, `OrderReconciliationService.java`
- Redis key: `TICKET:{ticketItemId}:STOCK`
- Benchmark: `docs/performance/BENCHMARK_RESULTS_ANALYSIS.md`
