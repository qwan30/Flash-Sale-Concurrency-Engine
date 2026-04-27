package com.xxxx.ddd.application.service.order.strategy;

import com.xxxx.ddd.application.model.order.CreateOrderRequest;
import com.xxxx.ddd.application.model.order.OrderStrategy;
import com.xxxx.ddd.application.service.order.cache.StockOrderCacheService;
import com.xxxx.ddd.domain.service.TickerOrderDomainService;
import org.springframework.stereotype.Component;

@Component
public class RedisLuaCompensatingStockDeductionStrategy implements StockDeductionStrategy {

    private final StockOrderCacheService stockOrderCacheService;
    private final TickerOrderDomainService tickerOrderDomainService;

    public RedisLuaCompensatingStockDeductionStrategy(
            StockOrderCacheService stockOrderCacheService,
            TickerOrderDomainService tickerOrderDomainService
    ) {
        this.stockOrderCacheService = stockOrderCacheService;
        this.tickerOrderDomainService = tickerOrderDomainService;
    }

    @Override
    public OrderStrategy strategy() {
        return OrderStrategy.REDIS_LUA_WITH_COMPENSATION;
    }

    @Override
    public StockDeductionResult decrease(CreateOrderRequest request) {
        boolean redisDecremented = false;
        try {
            long remainingStock = stockOrderCacheService.decreaseStockCacheByLuaReturningRemaining(
                    request.getTicketItemId(), request.getQuantity());
            if (remainingStock < 0) {
                return StockDeductionResult.failure("REDIS_STOCK_UNAVAILABLE", "Redis stock is missing or not enough");
            }
            redisDecremented = true;

            if (tickerOrderDomainService.decreaseStockLevel1(request.getTicketItemId(), request.getQuantity())) {
                return StockDeductionResult.success(true);
            }

            stockOrderCacheService.restoreStockCache(request.getTicketItemId(), request.getQuantity());
            return StockDeductionResult.failure("DB_STOCK_DECREMENT_FAILED", "Database stock was not decremented");
        } catch (RuntimeException e) {
            if (redisDecremented) {
                stockOrderCacheService.restoreStockCache(request.getTicketItemId(), request.getQuantity());
            }
            throw e;
        }
    }
}
