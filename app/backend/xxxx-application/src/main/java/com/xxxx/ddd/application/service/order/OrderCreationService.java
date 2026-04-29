package com.xxxx.ddd.application.service.order;

import com.xxxx.ddd.application.model.order.CreateOrderRequest;
import com.xxxx.ddd.application.model.order.CreateOrderResponse;
import com.xxxx.ddd.application.model.order.OrderStrategy;
import com.xxxx.ddd.application.service.order.cache.StockOrderCacheService;
import com.xxxx.ddd.application.service.order.strategy.StockDeductionResult;
import com.xxxx.ddd.application.service.order.strategy.StockDeductionStrategyRegistry;
import com.xxxx.ddd.application.service.order.support.OrderDateSupport;
import com.xxxx.ddd.domain.model.entity.TickerOrder;
import com.xxxx.ddd.domain.service.OrderDeductionDomainService;
import com.xxxx.ddd.domain.service.TickerOrderDomainService;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;

@Service
@Slf4j
public class OrderCreationService {

    private static final BigDecimal UNIT_PRICE = BigDecimal.valueOf(5000);
    private static final String TERMINAL_ID = "OKX-SGN";

    private final TickerOrderDomainService tickerOrderDomainService;
    private final OrderDeductionDomainService orderDeductionDomainService;
    private final StockOrderCacheService stockOrderCacheService;
    private final StockDeductionStrategyRegistry stockDeductionStrategyRegistry;
    private final IdempotencyService idempotencyService;

    @Autowired(required = false)
    private MeterRegistry meterRegistry;

    public OrderCreationService(
            TickerOrderDomainService tickerOrderDomainService,
            OrderDeductionDomainService orderDeductionDomainService,
            StockOrderCacheService stockOrderCacheService,
            StockDeductionStrategyRegistry stockDeductionStrategyRegistry,
            IdempotencyService idempotencyService
    ) {
        this.tickerOrderDomainService = tickerOrderDomainService;
        this.orderDeductionDomainService = orderDeductionDomainService;
        this.stockOrderCacheService = stockOrderCacheService;
        this.stockDeductionStrategyRegistry = stockDeductionStrategyRegistry;
        this.idempotencyService = idempotencyService;
    }

    @Transactional(rollbackFor = Exception.class)
    public CreateOrderResponse createOrder(CreateOrderRequest request) {
        String validationError = validateCreateOrderRequest(request);
        if (validationError != null) {
            CreateOrderResponse response = CreateOrderResponse.failure(request, "INVALID_REQUEST", validationError);
            recordOrderMetric(request == null ? null : request.getStrategy(), false);
            return response;
        }

        String idempotencyKey = request.getUserId() + ":" + request.getIdempotencyKey();
        return idempotencyService.getOrCreate(idempotencyKey, () -> doCreateOrder(request));
    }

    public CreateOrderResponse createLegacyOrder(Long tickerId, int quantity, OrderStrategy strategy) {
        return createOrder(legacyRequest(tickerId, quantity, strategy));
    }

    private CreateOrderResponse doCreateOrder(CreateOrderRequest request) {
        StockDeductionResult deductionResult = null;
        try {
            long nowMillis = System.currentTimeMillis();
            String orderNumber = buildOrderNumber(request.getUserId(), nowMillis);
            String yearMonth = OrderDateSupport.formatYearMonth(nowMillis);

            deductionResult = stockDeductionStrategyRegistry.get(request.getStrategy()).decrease(request);
            if (!deductionResult.isSuccess()) {
                CreateOrderResponse response = failureWithCurrentStock(
                        request, deductionResult.getCode(), deductionResult.getMessage());
                recordOrderMetric(request.getStrategy(), false);
                return response;
            }

            orderDeductionDomainService.ensureMonthlyOrderTable(yearMonth);
            orderDeductionDomainService.insertOrder(yearMonth, buildOrder(request, orderNumber));

            CreateOrderResponse response = CreateOrderResponse.success(
                    request,
                    orderNumber,
                    stockOrderCacheService.getStockCache(request.getTicketItemId()),
                    tickerOrderDomainService.getStockAvailable(request.getTicketItemId())
            );
            recordOrderMetric(request.getStrategy(), true);
            return response;
        } catch (Exception e) {
            markRollbackOnly();
            if (deductionResult != null && deductionResult.isCompensateOnOrderFailure()) {
                try {
                    stockOrderCacheService.restoreStockCache(request.getTicketItemId(), request.getQuantity());
                } catch (Exception compensationEx) {
                    // CRITICAL: Redis compensation failed — "Double Fault" scenario.
                    // Redis remains decremented but no DB order exists.
                    // The scheduled OrderReconciliationService will detect and repair this drift.
                    log.error("COMPENSATION_FAILURE: Redis stock restore failed for ticketItemId={}. " +
                                    "Redis is {} unit(s) lower than it should be. Reconciliation will repair.",
                            request.getTicketItemId(), request.getQuantity(), compensationEx);
                }
            }
            log.error("createOrder failed ticketItemId={} strategy={}", request.getTicketItemId(), request.getStrategy(), e);
            CreateOrderResponse response = failureWithCurrentStock(request, "ORDER_CREATE_FAILED", "Order creation failed");
            recordOrderMetric(request.getStrategy(), false);
            return response;
        }
    }

    private String validateCreateOrderRequest(CreateOrderRequest request) {
        if (request == null) {
            return "Request body is required";
        }
        if (request.getTicketItemId() == null || request.getTicketItemId() <= 0) {
            return "ticketItemId must be positive";
        }
        if (request.getUserId() == null || request.getUserId() <= 0) {
            return "userId must be positive";
        }
        if (request.getQuantity() == null || request.getQuantity() <= 0) {
            return "quantity must be positive";
        }
        if (request.getStrategy() == null) {
            return "strategy is required";
        }
        if (!StringUtils.hasText(request.getIdempotencyKey())) {
            return "idempotencyKey is required";
        }
        return null;
    }

    private CreateOrderResponse failureWithCurrentStock(CreateOrderRequest request, String code, String message) {
        CreateOrderResponse response = CreateOrderResponse.failure(request, code, message);
        response.setRedisStockAfter(stockOrderCacheService.getStockCache(request.getTicketItemId()));
        response.setDbStockAfter(tickerOrderDomainService.getStockAvailable(request.getTicketItemId()));
        return response;
    }

    private TickerOrder buildOrder(CreateOrderRequest request, String orderNumber) {
        TickerOrder tickerOrderPlace = new TickerOrder();
        tickerOrderPlace.setUserId(request.getUserId().intValue());
        tickerOrderPlace.setOrderNumber(orderNumber);
        tickerOrderPlace.setTotalAmount(UNIT_PRICE.multiply(BigDecimal.valueOf(request.getQuantity())));
        tickerOrderPlace.setTerminalId(TERMINAL_ID);
        tickerOrderPlace.setOrderNotes("Order -> Pending");
        return tickerOrderPlace;
    }

    private String buildOrderNumber(Long userId, long nowMillis) {
        return TERMINAL_ID + "-" + userId + "-" + nowMillis;
    }

    private CreateOrderRequest legacyRequest(Long tickerId, int quantity, OrderStrategy strategy) {
        CreateOrderRequest request = new CreateOrderRequest();
        request.setTicketItemId(tickerId);
        request.setUserId(1L);
        request.setQuantity(quantity);
        request.setStrategy(strategy);
        request.setIdempotencyKey("legacy-" + tickerId + "-" + quantity + "-" + System.nanoTime());
        return request;
    }

    private void recordOrderMetric(OrderStrategy strategy, boolean success) {
        if (meterRegistry == null || strategy == null) {
            return;
        }
        meterRegistry.counter(
                "flashsale.orders",
                "strategy", strategy.name(),
                "result", success ? "success" : "failed"
        ).increment();
    }

    private void markRollbackOnly() {
        try {
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
        } catch (Exception ignored) {
            log.debug("No active transaction to mark rollback-only");
        }
    }
}
