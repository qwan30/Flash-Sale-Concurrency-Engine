package com.xxxx.ddd.application.service.order.strategy;

import com.xxxx.ddd.application.model.order.CreateOrderRequest;
import com.xxxx.ddd.application.model.order.OrderStrategy;
import com.xxxx.ddd.application.service.order.cache.StockOrderCacheService;
import com.xxxx.ddd.domain.service.TickerOrderDomainService;
import org.springframework.stereotype.Component;

/**
 * Redis-first strategy that gates demand with Lua before touching the database.
 *
 * <p>This version intentionally does not restore Redis after a later database failure. It is useful
 * for measuring fast rejection behavior and observing Redis-DB drift during reconciliation.
 */
@Component
public class RedisLuaStockDeductionStrategy implements StockDeductionStrategy {

    private final StockOrderCacheService stockOrderCacheService;
    private final TickerOrderDomainService tickerOrderDomainService;

    public RedisLuaStockDeductionStrategy(
            StockOrderCacheService stockOrderCacheService,
            TickerOrderDomainService tickerOrderDomainService
    ) {
        this.stockOrderCacheService = stockOrderCacheService;
        this.tickerOrderDomainService = tickerOrderDomainService;
    }

    @Override
    public OrderStrategy strategy() {
        return OrderStrategy.REDIS_LUA;
    }

    @Override
    public StockDeductionResult decrease(CreateOrderRequest request) {
        // Redis decides capacity first; the DB conditional update remains the source-of-truth guard.
        long remainingStock = stockOrderCacheService.decreaseStockCacheByLuaReturningRemaining(
                request.getTicketItemId(), request.getQuantity());
        if (remainingStock < 0) {
            return StockDeductionResult.failure("REDIS_STOCK_UNAVAILABLE", "Redis stock is missing or not enough");
        }
        if (tickerOrderDomainService.decreaseStockLevel1(request.getTicketItemId(), request.getQuantity())) {
            return StockDeductionResult.success(false);
        }
        return StockDeductionResult.failure("DB_STOCK_DECREMENT_FAILED", "Database stock was not decremented");
    }
}
