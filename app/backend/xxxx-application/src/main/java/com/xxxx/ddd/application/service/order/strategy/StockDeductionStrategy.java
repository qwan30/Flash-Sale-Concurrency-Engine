package com.xxxx.ddd.application.service.order.strategy;

import com.xxxx.ddd.application.model.order.CreateOrderRequest;
import com.xxxx.ddd.application.model.order.OrderStrategy;

/**
 * Contract for one stock deduction strategy in the flash-sale lab.
 *
 * <p>Implementations make the accept/reject decision for the requested quantity and return whether
 * Redis must be compensated if a later order write fails. They do not create order rows.
 */
public interface StockDeductionStrategy {
    /**
     * Returns the strategy key used by {@link StockDeductionStrategyRegistry}.
     */
    OrderStrategy strategy();

    /**
     * Attempts to reserve stock for the request.
     *
     * @param request validated order request
     * @return reservation result, including the failure code or compensation requirement
     */
    StockDeductionResult decrease(CreateOrderRequest request);
}
