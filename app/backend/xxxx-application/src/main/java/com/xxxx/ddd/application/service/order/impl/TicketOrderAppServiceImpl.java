package com.xxxx.ddd.application.service.order.impl;

import com.xxxx.ddd.application.model.TicketOrderDTO;
import com.xxxx.ddd.application.model.order.BenchmarkResetRequest;
import com.xxxx.ddd.application.model.order.BenchmarkResetResponse;
import com.xxxx.ddd.application.model.order.ConsistencySnapshot;
import com.xxxx.ddd.application.model.order.CreateOrderRequest;
import com.xxxx.ddd.application.model.order.CreateOrderResponse;
import com.xxxx.ddd.application.model.order.OrderStrategy;
import com.xxxx.ddd.application.service.order.BenchmarkFixtureService;
import com.xxxx.ddd.application.service.order.ConsistencyCheckService;
import com.xxxx.ddd.application.service.order.OrderCreationService;
import com.xxxx.ddd.application.service.order.OrderQueryService;
import com.xxxx.ddd.application.service.order.TicketOrderAppService;
import com.xxxx.ddd.domain.model.entity.TickerOrder;
import com.xxxx.ddd.domain.service.TickerOrderDomainService;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class TicketOrderAppServiceImpl implements TicketOrderAppService {

    private final OrderCreationService orderCreationService;
    private final BenchmarkFixtureService benchmarkFixtureService;
    private final ConsistencyCheckService consistencyCheckService;
    private final OrderQueryService orderQueryService;
    private final TickerOrderDomainService tickerOrderDomainService;

    public TicketOrderAppServiceImpl(
            OrderCreationService orderCreationService,
            BenchmarkFixtureService benchmarkFixtureService,
            ConsistencyCheckService consistencyCheckService,
            OrderQueryService orderQueryService,
            TickerOrderDomainService tickerOrderDomainService
    ) {
        this.orderCreationService = orderCreationService;
        this.benchmarkFixtureService = benchmarkFixtureService;
        this.consistencyCheckService = consistencyCheckService;
        this.orderQueryService = orderQueryService;
        this.tickerOrderDomainService = tickerOrderDomainService;
    }

    @Override
    public CreateOrderResponse createOrder(CreateOrderRequest request) {
        return orderCreationService.createOrder(request);
    }

    @Override
    public CreateOrderResponse warmupStock(Long ticketItemId) {
        return benchmarkFixtureService.warmupStock(ticketItemId);
    }

    @Override
    public BenchmarkResetResponse resetBenchmark(BenchmarkResetRequest request) {
        return benchmarkFixtureService.resetBenchmark(request);
    }

    @Override
    public ConsistencySnapshot getConsistency(Long ticketItemId, String yearMonth) {
        return consistencyCheckService.getConsistency(ticketItemId, yearMonth);
    }

    @Override
    public boolean decreaseStockLevel1(Long tickerId, int quantity) {
        return orderCreationService.createLegacyOrder(tickerId, quantity, OrderStrategy.CONDITIONAL_DB).isSuccess();
    }

    @Override
    public boolean decreaseStockLevel2(Long tickerId, int quantity) {
        return false;
    }

    @Override
    public boolean decreaseStockLevel3CAS(Long tickerId, int quantity) {
        return orderCreationService.createLegacyOrder(tickerId, quantity, OrderStrategy.REDIS_LUA_WITH_COMPENSATION)
                .isSuccess();
    }

    @Override
    public int getStockAvailable(Long ticketId) {
        return tickerOrderDomainService.getStockAvailable(ticketId);
    }

    @Override
    public List<TicketOrderDTO> findAll(String yearMonth) {
        return orderQueryService.findAll(yearMonth);
    }

    @Override
    public List<TicketOrderDTO> findAllByUser(String yearMonth, Long userId) {
        return orderQueryService.findAllByUser(yearMonth, userId);
    }

    @Override
    public boolean insertOrder(String yearMonth, TickerOrder tickerOrder) {
        return orderQueryService.insertOrder(yearMonth, tickerOrder);
    }

    @Override
    public TicketOrderDTO findByOrderNumber(String yearMonth, String orderNumber) {
        return orderQueryService.findByOrderNumber(yearMonth, orderNumber);
    }
}
