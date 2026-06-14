# Coding Standards — Java

> Derived from `project-foundation.md` §5. Expands language-specific conventions for this project.

## 1. Package Structure

```text
com.xxxx.ddd.
├── domain/
│   ├── model/entity/       ← JPA entities
│   ├── respository/        ← Repository interfaces (ports)
│   ├── service/            ← Domain service interfaces
│   └── service/impl/       ← Domain service implementations
├── application/
│   ├── model/              ← DTOs, request/response models
│   ├── model/cache/        ← Cache DTOs
│   ├── model/order/        ← Order-specific DTOs
│   ├── model/benchmark/    ← Benchmark DTOs
│   ├── mapper/             ← Entity ↔ DTO mappers
│   ├── port/cache/         ← Cache port interfaces
│   ├── service/            ← Application service interfaces
│   ├── service/impl/       ← Application service implementations
│   ├── service/strategy/   ← Stock deduction strategies
│   ├── cronjob/            ← Scheduled/cron tasks
│   └── MQ/                 ← Messaging (Outbox)
├── infrastructure/
│   ├── cache/redis/        ← Redis adapter implementations
│   ├── config/             ← Infrastructure @Configuration
│   ├── distributed/redisson/ ← Redisson lock adapters
│   └── persistence/
│       ├── mapper/         ← JPA repository interfaces
│       └── repository/     ← Repository implementations
├── controller/
│   ├── http/               ← REST controllers
│   └── model/
│       ├── enums/          ← ResultCode, ResultUtil
│       └── vo/             ← ResultMessage<T>
└── config/                 ← Bootstrap config (OpenApi, Observation)
```

## 2. Naming Conventions

| Element | Convention | Example |
|---|---|---|
| Entity | `PascalCase`, matches table | `TicketDetail`, `TickerOrder` |
| DTO | `PascalCase` + `DTO` suffix | `TicketDetailDTO` |
| Service interface | `PascalCase` + `Service` suffix | `TicketDetailAppService` |
| Service impl | Interface name + `Impl` suffix | `TicketDetailAppServiceImpl` |
| Repository interface | `PascalCase` + `Repository` suffix | `TicketDetailRepository` |
| Repository impl | Interface name + `InfrasImpl` suffix | `TicketDetailInfrasRepositoryImpl` |
| Controller | `PascalCase` + `Controller` suffix | `TicketOrderController` |
| Strategy | `PascalCase` + `Strategy` suffix | `RedisLuaStockDeductionStrategy` |
| Config | `PascalCase` + `Config` suffix | `RedisConfig` |
| Mapper | `PascalCase` + `Mapper` suffix | `TicketDetailMapper` |

## 3. Dependency Injection

**Preferred (new code)**: Constructor injection

```java
@Service
public class OrderCreationService {
    private final TickerOrderDomainService tickerOrderDomainService;
    private final StockDeductionStrategyRegistry strategyRegistry;

    public OrderCreationService(
            TickerOrderDomainService tickerOrderDomainService,
            StockDeductionStrategyRegistry strategyRegistry
    ) {
        this.tickerOrderDomainService = tickerOrderDomainService;
        this.strategyRegistry = strategyRegistry;
    }
}
```

**Legacy (existing code)**: Field injection with `@Autowired` — avoid in new code.

## 4. Immutability

- **DTOs**: Prefer immutable. Use `@Builder` or constructor injection.
- **Entities**: Lombok `@Data` + `@Accessors(chain=true)` is the project convention.
- **Never** mutate method parameters — return new objects for transformations.

## 5. Error Handling

| Pattern | Usage |
|---|---|
| Result objects | `StockDeductionResult.success()` / `.failure()` for strategy outcomes |
| `ResultMessage<T>` | Controller response envelope |
| Exceptions | Truly exceptional cases; let global handler wrap |
| Logging | `@Slf4j` + structured log with trace context |

```java
// Good: domain result object
if (stockDecremented) {
    return StockDeductionResult.success(requiresCompensation);
}
return StockDeductionResult.failure("DB_STOCK_DECREMENT_FAILED", "...");
```

## 6. Strategy Pattern Convention

All stock strategies implement `StockDeductionStrategy`:

```java
public interface StockDeductionStrategy {
    OrderStrategy strategy();
    StockDeductionResult decrease(CreateOrderRequest request);
}
```

New strategies must: (1) Add enum to `OrderStrategy`, (2) Implement interface, (3) Annotate `@Component` for auto-registration, (4) Add tests for oversell/drift.

## 7. Logging

- `@Slf4j` on all service/controller classes
- `log.info(...)` for business events, `log.warn(...)` for recoverable, `log.error(...)` for failures
- No `System.out.println` or `console.log`

## 8. Testing Conventions

| Type | Location | Naming |
|---|---|---|
| Unit | `src/test/` mirror of `src/main/` | `{ClassName}Test.java` |
| Integration | `xxxx-start/src/test/` | Descriptive name |
| Test method | — | `should{Behavior}_when{Condition}()` |

AAA pattern (Arrange-Act-Assert) with clear section comments.

## 9. Lombok Usage

| Annotation | When |
|---|---|
| `@Data` | Entities |
| `@Slf4j` | Logging classes |
| `@Builder` | DTOs with optional fields |
| `@AllArgsConstructor` + `@NoArgsConstructor` | JPA entities |
| `@Accessors(chain=true)` | Entities for fluent setters |
