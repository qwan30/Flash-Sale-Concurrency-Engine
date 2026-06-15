package com.xxxx.ddd.application.service.order.impl;

import com.xxxx.ddd.application.MQ.OutboxService;
import com.xxxx.ddd.application.model.order.CreateOrderRequest;
import com.xxxx.ddd.application.model.order.CreateOrderResponse;
import com.xxxx.ddd.application.model.order.OrderStrategy;
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
import com.xxxx.ddd.domain.service.OrderDeductionDomainService;
import com.xxxx.ddd.domain.service.TickerOrderDomainService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests all 4 stock deduction strategies through the application service layer.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AllStrategiesAppServiceTest {

    @Mock
    private TickerOrderDomainService tickerOrderDomainService;

    @Mock
    private OrderDeductionDomainService orderDeductionDomainService;

    @Mock
    private StockOrderCacheService stockOrderCacheService;

    @Mock
    private OutboxService outboxService;

    private TicketOrderAppServiceImpl service;

    private static final long TICKET_ID = 4L;
    private static final long USER_ID = 42L;
    private static final int QUANTITY = 2;

    @BeforeEach
    void setUp() {
        // Common stubs for insert/ensure/outbox (called on success path)
        doNothing().when(orderDeductionDomainService).ensureMonthlyOrderTable(anyString());
        doNothing().when(orderDeductionDomainService).insertOrder(anyString(), any());
        when(outboxService.record(anyString(), anyString(), anyString(), any())).thenReturn(null);

        IdempotencyService idempotencyService = new IdempotencyService();
        StockDeductionStrategyRegistry registry = new StockDeductionStrategyRegistry(List.of(
                new UnsafeDbStockDeductionStrategy(tickerOrderDomainService),
                new ConditionalDbStockDeductionStrategy(tickerOrderDomainService),
                new RedisLuaStockDeductionStrategy(stockOrderCacheService, tickerOrderDomainService),
                new RedisLuaCompensatingStockDeductionStrategy(stockOrderCacheService, tickerOrderDomainService)
        ));
        OrderCreationService orderCreationService = new OrderCreationService(
                tickerOrderDomainService,
                orderDeductionDomainService,
                stockOrderCacheService,
                registry,
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

    // ─── UNSAFE_DB strategy ──────────────────────────────────────────────────

    @Test
    void unsafeDbDecrementsStockAndCreatesOrder() {
        CreateOrderRequest request = request(OrderStrategy.UNSAFE_DB);
        when(tickerOrderDomainService.decreaseStockUnsafe(TICKET_ID, QUANTITY)).thenReturn(true);
        when(stockOrderCacheService.getStockCache(TICKET_ID)).thenReturn(998);
        when(tickerOrderDomainService.getStockAvailable(TICKET_ID)).thenReturn(998);

        CreateOrderResponse response = service.createOrder(request);

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getOrderNumber()).isNotBlank();
        assertThat(response.getDbStockAfter()).isEqualTo(998);
        verify(orderDeductionDomainService).insertOrder(anyString(), any());
    }

    @Test
    void unsafeDbReportsDbStockDecrementFailed() {
        CreateOrderRequest request = request(OrderStrategy.UNSAFE_DB);
        when(tickerOrderDomainService.decreaseStockUnsafe(TICKET_ID, QUANTITY)).thenReturn(false);
        when(stockOrderCacheService.getStockCache(TICKET_ID)).thenReturn(1000);
        when(tickerOrderDomainService.getStockAvailable(TICKET_ID)).thenReturn(1000);

        CreateOrderResponse response = service.createOrder(request);

        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getCode()).isEqualTo("DB_STOCK_DECREMENT_FAILED");
        verify(orderDeductionDomainService, never()).insertOrder(anyString(), any());
    }

    // ─── CONDITIONAL_DB strategy ─────────────────────────────────────────────

    @Test
    void conditionalDbSucceedsWhenStockAvailable() {
        CreateOrderRequest request = request(OrderStrategy.CONDITIONAL_DB);
        when(tickerOrderDomainService.decreaseStockLevel1(TICKET_ID, QUANTITY)).thenReturn(true);
        when(stockOrderCacheService.getStockCache(TICKET_ID)).thenReturn(998);
        when(tickerOrderDomainService.getStockAvailable(TICKET_ID)).thenReturn(998);

        CreateOrderResponse response = service.createOrder(request);

        assertThat(response.isSuccess()).isTrue();
        verify(orderDeductionDomainService).insertOrder(anyString(), any());
    }

    @Test
    void conditionalDbFailsWhenStockDecrementReturnsFalse() {
        CreateOrderRequest request = request(OrderStrategy.CONDITIONAL_DB);
        when(tickerOrderDomainService.decreaseStockLevel1(TICKET_ID, QUANTITY)).thenReturn(false);
        when(stockOrderCacheService.getStockCache(TICKET_ID)).thenReturn(1000);
        when(tickerOrderDomainService.getStockAvailable(TICKET_ID)).thenReturn(1000);

        CreateOrderResponse response = service.createOrder(request);

        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getCode()).isEqualTo("DB_STOCK_DECREMENT_FAILED");
        verify(orderDeductionDomainService, never()).insertOrder(anyString(), any());
    }

    // ─── REDIS_LUA strategy ─────────────────────────────────────────────────

    @Test
    void redisLuaDecrementsRedisThenDbAndCreatesOrder() {
        CreateOrderRequest request = request(OrderStrategy.REDIS_LUA);
        when(stockOrderCacheService.decreaseStockCacheByLuaReturningRemaining(TICKET_ID, QUANTITY))
                .thenReturn(8L); // 10 - 2 = 8 remaining
        when(tickerOrderDomainService.decreaseStockLevel1(TICKET_ID, QUANTITY)).thenReturn(true);
        when(stockOrderCacheService.getStockCache(TICKET_ID)).thenReturn(8);
        when(tickerOrderDomainService.getStockAvailable(TICKET_ID)).thenReturn(8);

        CreateOrderResponse response = service.createOrder(request);

        assertThat(response.isSuccess()).isTrue();
        verify(orderDeductionDomainService).insertOrder(anyString(), any());
    }

    @Test
    void redisLuaDoesNotCompensateWhenDbDecrementFails() {
        CreateOrderRequest request = request(OrderStrategy.REDIS_LUA);
        when(stockOrderCacheService.decreaseStockCacheByLuaReturningRemaining(TICKET_ID, QUANTITY))
                .thenReturn(8L);
        when(tickerOrderDomainService.decreaseStockLevel1(TICKET_ID, QUANTITY)).thenReturn(false);
        when(stockOrderCacheService.getStockCache(TICKET_ID)).thenReturn(8);
        when(tickerOrderDomainService.getStockAvailable(TICKET_ID)).thenReturn(10);

        // REDIS_LUA does NOT compensate — that's the difference vs REDIS_LUA_WITH_COMPENSATION
        CreateOrderResponse response = service.createOrder(request);

        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getCode()).isEqualTo("DB_STOCK_DECREMENT_FAILED");
        // No stock restored because REDIS_LUA doesn't compensate
        verify(stockOrderCacheService, never()).restoreStockCache(any(), anyInt());
        verify(orderDeductionDomainService, never()).insertOrder(anyString(), any());
    }

    // ─── REDIS_LUA_WITH_COMPENSATION strategy ────────────────────────────────

    @Test
    void compensatedRedisDecrementsBothAndCreatesOrder() {
        CreateOrderRequest request = request(OrderStrategy.REDIS_LUA_WITH_COMPENSATION);
        when(stockOrderCacheService.decreaseStockCacheByLuaReturningRemaining(TICKET_ID, QUANTITY))
                .thenReturn(8L);
        when(tickerOrderDomainService.decreaseStockLevel1(TICKET_ID, QUANTITY)).thenReturn(true);
        when(stockOrderCacheService.getStockCache(TICKET_ID)).thenReturn(8);
        when(tickerOrderDomainService.getStockAvailable(TICKET_ID)).thenReturn(8);

        CreateOrderResponse response = service.createOrder(request);

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getRedisStockAfter()).isEqualTo(8);
        verify(orderDeductionDomainService).insertOrder(anyString(), any());
    }

    @Test
    void compensatedRedisRestoresStockWhenDbFails() {
        CreateOrderRequest request = request(OrderStrategy.REDIS_LUA_WITH_COMPENSATION);
        when(stockOrderCacheService.decreaseStockCacheByLuaReturningRemaining(TICKET_ID, QUANTITY))
                .thenReturn(8L);
        when(tickerOrderDomainService.decreaseStockLevel1(TICKET_ID, QUANTITY)).thenReturn(false);
        when(stockOrderCacheService.getStockCache(TICKET_ID)).thenReturn(8);
        when(tickerOrderDomainService.getStockAvailable(TICKET_ID)).thenReturn(10);

        CreateOrderResponse response = service.createOrder(request);

        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getCode()).isEqualTo("DB_STOCK_DECREMENT_FAILED");
        // Compensation: Redis is restored because DB failed
        verify(stockOrderCacheService).restoreStockCache(TICKET_ID, QUANTITY);
        verify(orderDeductionDomainService, never()).insertOrder(anyString(), any());
    }

    @Test
    void compensatedRedisReportsRedisStockUnavailableWhenNegativeRemaining() {
        CreateOrderRequest request = request(OrderStrategy.REDIS_LUA_WITH_COMPENSATION);
        // Redis Lua returns -1 when stock is insufficient
        when(stockOrderCacheService.decreaseStockCacheByLuaReturningRemaining(TICKET_ID, QUANTITY))
                .thenReturn(-1L);
        when(stockOrderCacheService.getStockCache(TICKET_ID)).thenReturn(0);
        when(tickerOrderDomainService.getStockAvailable(TICKET_ID)).thenReturn(0);

        CreateOrderResponse response = service.createOrder(request);

        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getCode()).isEqualTo("REDIS_STOCK_UNAVAILABLE");
        verify(tickerOrderDomainService, never()).decreaseStockLevel1(any(), anyInt());
        verify(orderDeductionDomainService, never()).insertOrder(anyString(), any());
    }

    // ─── Helper ──────────────────────────────────────────────────────────────

    private CreateOrderRequest request(OrderStrategy strategy) {
        CreateOrderRequest request = new CreateOrderRequest();
        request.setTicketItemId(TICKET_ID);
        request.setUserId(USER_ID);
        request.setQuantity(QUANTITY);
        request.setStrategy(strategy);
        request.setIdempotencyKey("idem-all-" + strategy.name() + "-" + System.nanoTime());
        return request;
    }
}
