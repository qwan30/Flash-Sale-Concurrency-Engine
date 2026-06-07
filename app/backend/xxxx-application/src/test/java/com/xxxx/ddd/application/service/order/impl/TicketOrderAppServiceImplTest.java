package com.xxxx.ddd.application.service.order.impl;

import com.xxxx.ddd.application.model.order.BenchmarkResetRequest;
import com.xxxx.ddd.application.model.order.CreateOrderRequest;
import com.xxxx.ddd.application.model.order.CreateOrderResponse;
import com.xxxx.ddd.application.model.order.OrderStrategy;
import com.xxxx.ddd.application.MQ.OutboxService;
import com.xxxx.ddd.application.service.order.BenchmarkFixtureService;
import com.xxxx.ddd.application.service.order.ConsistencyCheckService;
import com.xxxx.ddd.application.service.order.IdempotencyService;
import com.xxxx.ddd.application.service.order.OrderCreationService;
import com.xxxx.ddd.application.service.order.OrderQueryService;
import com.xxxx.ddd.application.service.order.cache.StockOrderCacheService;
import com.xxxx.ddd.application.service.order.strategy.ConditionalDbStockDeductionStrategy;
import com.xxxx.ddd.application.service.order.strategy.RedisLuaCompensatingStockDeductionStrategy;
import com.xxxx.ddd.application.service.order.strategy.RedisLuaStockDeductionStrategy;
import com.xxxx.ddd.application.service.order.strategy.StockDeductionStrategyRegistry;
import com.xxxx.ddd.application.service.order.strategy.UnsafeDbStockDeductionStrategy;
import com.xxxx.ddd.domain.model.entity.TickerOrder;
import com.xxxx.ddd.domain.service.OrderDeductionDomainService;
import com.xxxx.ddd.domain.service.TickerOrderDomainService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TicketOrderAppServiceImplTest {

    @Mock
    private TickerOrderDomainService tickerOrderDomainService;

    @Mock
    private OrderDeductionDomainService orderDeductionDomainService;

    @Mock
    private StockOrderCacheService stockOrderCacheService;

    @Mock
    private OutboxService outboxService;

    private TicketOrderAppServiceImpl service;

    @BeforeEach
    void setUp() {
        IdempotencyService idempotencyService = new IdempotencyService();
        StockDeductionStrategyRegistry stockDeductionStrategyRegistry = new StockDeductionStrategyRegistry(List.of(
                new UnsafeDbStockDeductionStrategy(tickerOrderDomainService),
                new ConditionalDbStockDeductionStrategy(tickerOrderDomainService),
                new RedisLuaStockDeductionStrategy(stockOrderCacheService, tickerOrderDomainService),
                new RedisLuaCompensatingStockDeductionStrategy(stockOrderCacheService, tickerOrderDomainService)
        ));
        OrderCreationService orderCreationService = new OrderCreationService(
                tickerOrderDomainService,
                orderDeductionDomainService,
                stockOrderCacheService,
                stockDeductionStrategyRegistry,
                idempotencyService,
                outboxService
        );
        BenchmarkFixtureService benchmarkFixtureService = new BenchmarkFixtureService(
                tickerOrderDomainService,
                orderDeductionDomainService,
                stockOrderCacheService,
                idempotencyService
        );
        ConsistencyCheckService consistencyCheckService = new ConsistencyCheckService(
                tickerOrderDomainService,
                orderDeductionDomainService,
                stockOrderCacheService
        );
        OrderQueryService orderQueryService = new OrderQueryService(orderDeductionDomainService);
        service = new TicketOrderAppServiceImpl(
                orderCreationService,
                benchmarkFixtureService,
                consistencyCheckService,
                orderQueryService,
                tickerOrderDomainService
        );
    }

    @Test
    void createOrderRejectsInvalidQuantity() {
        CreateOrderRequest request = request(OrderStrategy.CONDITIONAL_DB);
        request.setQuantity(0);

        CreateOrderResponse response = service.createOrder(request);

        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getCode()).isEqualTo("INVALID_REQUEST");
        verify(tickerOrderDomainService, never()).decreaseStockLevel1(any(), any(Integer.class));
    }

    @Test
    void conditionalDbUsesRequestUserAndCreatesOrder() {
        CreateOrderRequest request = request(OrderStrategy.CONDITIONAL_DB);
        when(tickerOrderDomainService.decreaseStockLevel1(4L, 2)).thenReturn(true);
        when(tickerOrderDomainService.getStockAvailable(4L)).thenReturn(998);
        ArgumentCaptor<TickerOrder> orderCaptor = ArgumentCaptor.forClass(TickerOrder.class);

        CreateOrderResponse response = service.createOrder(request);

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getOrderNumber()).isNotBlank();
        assertThat(response.getDbStockAfter()).isEqualTo(998);
        verify(orderDeductionDomainService).insertOrder(anyString(), orderCaptor.capture());
        assertThat(orderCaptor.getValue().getUserId()).isEqualTo(42);
    }

    @Test
    void compensatedRedisRestoresStockWhenDbDecrementFails() {
        CreateOrderRequest request = request(OrderStrategy.REDIS_LUA_WITH_COMPENSATION);
        when(stockOrderCacheService.decreaseStockCacheByLuaReturningRemaining(4L, 2)).thenReturn(8L);
        when(tickerOrderDomainService.decreaseStockLevel1(4L, 2)).thenReturn(false);
        when(stockOrderCacheService.getStockCache(4L)).thenReturn(10);
        when(tickerOrderDomainService.getStockAvailable(4L)).thenReturn(10);

        CreateOrderResponse response = service.createOrder(request);

        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getCode()).isEqualTo("DB_STOCK_DECREMENT_FAILED");
        verify(stockOrderCacheService).restoreStockCache(4L, 2);
        verify(orderDeductionDomainService, never()).insertOrder(anyString(), any());
    }

    @Test
    void duplicateIdempotencyKeyReturnsSameResultWithoutSecondInsert() {
        CreateOrderRequest request = request(OrderStrategy.CONDITIONAL_DB);
        when(tickerOrderDomainService.decreaseStockLevel1(4L, 2)).thenReturn(true);
        when(tickerOrderDomainService.getStockAvailable(4L)).thenReturn(998);

        CreateOrderResponse first = service.createOrder(request);
        CreateOrderResponse second = service.createOrder(request);

        assertThat(second.getOrderNumber()).isEqualTo(first.getOrderNumber());
        verify(orderDeductionDomainService, times(1)).insertOrder(anyString(), any());
    }

    @Test
    void resetBenchmarkClearsIdempotencyCacheForRepeatableRuns() {
        CreateOrderRequest request = request(OrderStrategy.CONDITIONAL_DB);
        when(tickerOrderDomainService.decreaseStockLevel1(4L, 2)).thenReturn(true);
        when(tickerOrderDomainService.getStockAvailable(4L)).thenReturn(998);
        when(orderDeductionDomainService.countOrders("202604")).thenReturn(0L);

        service.createOrder(request);

        BenchmarkResetRequest resetRequest = new BenchmarkResetRequest();
        resetRequest.setTicketItemId(4L);
        resetRequest.setStock(1000);
        resetRequest.setYearMonth("202604");
        service.resetBenchmark(resetRequest);

        service.createOrder(request);

        verify(orderDeductionDomainService, times(2)).insertOrder(anyString(), any());
    }

    private CreateOrderRequest request(OrderStrategy strategy) {
        CreateOrderRequest request = new CreateOrderRequest();
        request.setTicketItemId(4L);
        request.setUserId(42L);
        request.setQuantity(2);
        request.setStrategy(strategy);
        request.setIdempotencyKey("idem-1");
        return request;
    }
}
