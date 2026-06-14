# ADR-002: Strategy Pattern for Stock Deduction

> **Status**: Accepted | **Date**: 2026-04-27 | **Deciders**: qwan30

## Context

The engine must compare 4 stock deduction approaches (UNSAFE_DB, CONDITIONAL_DB, REDIS_LUA, REDIS_LUA_WITH_COMPENSATION) under identical benchmark conditions. Strategy must be selectable per-request at runtime. Adding a new strategy must not modify existing code.

## Decision

**Strategy Pattern + Registry with Spring auto-discovery**:

```java
public interface StockDeductionStrategy {
    OrderStrategy strategy();              // enum key
    StockDeductionResult decrease(CreateOrderRequest request);
}

@Component  // auto-registers via List<StockDeductionStrategy> injection
public class ConditionalDbStockDeductionStrategy implements StockDeductionStrategy { ... }
```

Registry: `EnumMap<OrderStrategy, StockDeductionStrategy>` populated by Spring injecting all `@Component` implementations.

## Alternatives

| Alternative | Why Rejected |
|---|---|
| if/switch in OrderCreationService | Violates Open/Closed; every new strategy touches core |
| Chain of Responsibility | Over-engineered; no clear selection key |
| Separate endpoints per strategy | Duplicates order logic |

## Consequences

**Positive**: Open/Closed (add class + enum = done), auto-registration, type-safe EnumMap lookup, independently testable
**Negative**: Enum coupling (compile-time change needed), static wiring (no runtime add/remove)

## References
- `StockDeductionStrategy.java`, `StockDeductionStrategyRegistry.java`
- `OrderStrategy.java` (4 enum values)
