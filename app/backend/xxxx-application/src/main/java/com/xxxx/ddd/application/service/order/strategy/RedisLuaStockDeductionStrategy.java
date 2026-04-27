package com.xxxx.ddd.application.service.order.strategy;

import com.xxxx.ddd.application.model.order.CreateOrderRequest;
import com.xxxx.ddd.application.model.order.OrderStrategy;
import com.xxxx.ddd.application.service.order.cache.StockOrderCacheService;
import com.xxxx.ddd.domain.service.TickerOrderDomainService;
import org.springframework.stereotype.Component;

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
