package com.xxxx.ddd.application.service.order;

import com.xxxx.ddd.application.model.order.BenchmarkResetRequest;
import com.xxxx.ddd.application.model.order.BenchmarkResetResponse;
import com.xxxx.ddd.application.model.order.CreateOrderResponse;
import com.xxxx.ddd.application.service.order.cache.StockOrderCacheService;
import com.xxxx.ddd.application.service.order.support.OrderDateSupport;
import com.xxxx.ddd.domain.service.OrderDeductionDomainService;
import com.xxxx.ddd.domain.service.TickerOrderDomainService;
import org.springframework.stereotype.Service;

@Service
public class BenchmarkFixtureService {

    private final TickerOrderDomainService tickerOrderDomainService;
    private final OrderDeductionDomainService orderDeductionDomainService;
    private final StockOrderCacheService stockOrderCacheService;
    private final IdempotencyService idempotencyService;

    public BenchmarkFixtureService(
            TickerOrderDomainService tickerOrderDomainService,
            OrderDeductionDomainService orderDeductionDomainService,
            StockOrderCacheService stockOrderCacheService,
            IdempotencyService idempotencyService
    ) {
        this.tickerOrderDomainService = tickerOrderDomainService;
        this.orderDeductionDomainService = orderDeductionDomainService;
        this.stockOrderCacheService = stockOrderCacheService;
        this.idempotencyService = idempotencyService;
    }

    public CreateOrderResponse warmupStock(Long ticketItemId) {
        int dbStock = ticketItemId == null ? -1 : tickerOrderDomainService.getStockAvailable(ticketItemId);
        boolean warmed = ticketItemId != null && dbStock >= 0;
        if (warmed) {
            stockOrderCacheService.setStockCache(ticketItemId, dbStock);
        }
        return CreateOrderResponse.builder()
                .success(warmed)
                .code(warmed ? "SUCCESS" : "WARMUP_FAILED")
                .message(warmed ? "Redis stock warmed from database" : "Redis stock warmup failed")
                .ticketItemId(ticketItemId)
                .redisStockAfter(ticketItemId == null ? -1 : stockOrderCacheService.getStockCache(ticketItemId))
                .dbStockAfter(dbStock)
                .build();
    }

    public BenchmarkResetResponse resetBenchmark(BenchmarkResetRequest request) {
        String yearMonth = OrderDateSupport.normalizeYearMonth(request == null ? null : request.getYearMonth());
        if (request == null || request.getTicketItemId() == null || request.getStock() == null || request.getStock() < 0) {
            return BenchmarkResetResponse.builder()
                    .success(false)
                    .message("ticketItemId and non-negative stock are required")
                    .yearMonth(yearMonth)
                    .build();
        }

        tickerOrderDomainService.resetStock(request.getTicketItemId(), request.getStock());
        orderDeductionDomainService.ensureMonthlyOrderTable(yearMonth);
        orderDeductionDomainService.clearOrders(yearMonth);
        stockOrderCacheService.setStockCache(request.getTicketItemId(), request.getStock());
        idempotencyService.clear();

        return BenchmarkResetResponse.builder()
                .success(true)
                .message("Benchmark data reset")
                .ticketItemId(request.getTicketItemId())
                .stock(request.getStock())
                .yearMonth(yearMonth)
                .redisStockAfter(stockOrderCacheService.getStockCache(request.getTicketItemId()))
                .dbStockAfter(tickerOrderDomainService.getStockAvailable(request.getTicketItemId()))
                .dbOrderCount(orderDeductionDomainService.countOrders(yearMonth))
                .build();
    }
}
