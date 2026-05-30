package com.xxxx.ddd.application.service.order.strategy;

import com.xxxx.ddd.application.model.order.CreateOrderRequest;
import com.xxxx.ddd.application.model.order.OrderStrategy;
import com.xxxx.ddd.domain.service.TickerOrderDomainService;
import org.springframework.stereotype.Component;

/**
 * Database-only safe baseline using a conditional stock update.
 *
 * <p>The database row is the contention point: one statement checks available stock and decrements
 * it, then a zero affected-row result is treated as a clean rejection.
 */
@Component
public class ConditionalDbStockDeductionStrategy implements StockDeductionStrategy {

    private final TickerOrderDomainService tickerOrderDomainService;

    public ConditionalDbStockDeductionStrategy(TickerOrderDomainService tickerOrderDomainService) {
        this.tickerOrderDomainService = tickerOrderDomainService;
    }

    @Override
    public OrderStrategy strategy() {
        return OrderStrategy.CONDITIONAL_DB;
    }

    @Override
    public StockDeductionResult decrease(CreateOrderRequest request) {
        if (tickerOrderDomainService.decreaseStockLevel1(request.getTicketItemId(), request.getQuantity())) {
            return StockDeductionResult.success(false);
        }
        return StockDeductionResult.failure("DB_STOCK_DECREMENT_FAILED", "Database stock was not decremented");
    }
}
