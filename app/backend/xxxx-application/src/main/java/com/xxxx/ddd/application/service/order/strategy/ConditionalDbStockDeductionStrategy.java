package com.xxxx.ddd.application.service.order.strategy;

import com.xxxx.ddd.application.model.order.CreateOrderRequest;
import com.xxxx.ddd.application.model.order.OrderStrategy;
import com.xxxx.ddd.domain.service.TickerOrderDomainService;
import org.springframework.stereotype.Component;

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
