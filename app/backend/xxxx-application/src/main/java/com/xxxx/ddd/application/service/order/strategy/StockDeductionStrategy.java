package com.xxxx.ddd.application.service.order.strategy;

import com.xxxx.ddd.application.model.order.CreateOrderRequest;
import com.xxxx.ddd.application.model.order.OrderStrategy;

public interface StockDeductionStrategy {
    OrderStrategy strategy();

    StockDeductionResult decrease(CreateOrderRequest request);
}
