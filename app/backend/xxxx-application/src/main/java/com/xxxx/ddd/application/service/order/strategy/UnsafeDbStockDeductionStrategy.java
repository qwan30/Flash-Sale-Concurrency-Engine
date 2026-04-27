package com.xxxx.ddd.application.service.order.strategy;

import com.xxxx.ddd.application.model.order.CreateOrderRequest;
import com.xxxx.ddd.application.model.order.OrderStrategy;
import com.xxxx.ddd.domain.service.TickerOrderDomainService;
import org.springframework.stereotype.Component;

@Component
public class UnsafeDbStockDeductionStrategy implements StockDeductionStrategy {

    private final TickerOrderDomainService tickerOrderDomainService;

    public UnsafeDbStockDeductionStrategy(TickerOrderDomainService tickerOrderDomainService) {
        this.tickerOrderDomainService = tickerOrderDomainService;
    }

    @Override
    public OrderStrategy strategy() {
        return OrderStrategy.UNSAFE_DB;
    }

    @Override
    public StockDeductionResult decrease(CreateOrderRequest request) {
        if (tickerOrderDomainService.decreaseStockUnsafe(request.getTicketItemId(), request.getQuantity())) {
            return StockDeductionResult.success(false);
        }
        return StockDeductionResult.failure("DB_STOCK_DECREMENT_FAILED", "Database stock was not decremented");
    }
}
