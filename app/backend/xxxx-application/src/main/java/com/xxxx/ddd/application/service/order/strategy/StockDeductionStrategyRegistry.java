package com.xxxx.ddd.application.service.order.strategy;

import com.xxxx.ddd.application.model.order.OrderStrategy;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Component
public class StockDeductionStrategyRegistry {

    private final Map<OrderStrategy, StockDeductionStrategy> strategies;

    public StockDeductionStrategyRegistry(List<StockDeductionStrategy> strategies) {
        this.strategies = new EnumMap<>(OrderStrategy.class);
        for (StockDeductionStrategy strategy : strategies) {
            this.strategies.put(strategy.strategy(), strategy);
        }
    }

    public StockDeductionStrategy get(OrderStrategy strategy) {
        StockDeductionStrategy stockDeductionStrategy = strategies.get(strategy);
        if (stockDeductionStrategy == null) {
            throw new IllegalArgumentException("Unsupported order strategy: " + strategy);
        }
        return stockDeductionStrategy;
    }
}
