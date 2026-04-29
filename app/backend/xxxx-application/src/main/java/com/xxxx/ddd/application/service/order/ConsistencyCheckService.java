package com.xxxx.ddd.application.service.order;

import com.xxxx.ddd.application.model.order.ConsistencySnapshot;
import com.xxxx.ddd.application.service.order.cache.StockOrderCacheService;
import com.xxxx.ddd.application.service.order.support.OrderDateSupport;
import com.xxxx.ddd.domain.service.OrderDeductionDomainService;
import com.xxxx.ddd.domain.service.TickerOrderDomainService;
import org.springframework.stereotype.Service;

@Service
public class ConsistencyCheckService {

    private final TickerOrderDomainService tickerOrderDomainService;
    private final OrderDeductionDomainService orderDeductionDomainService;
    private final StockOrderCacheService stockOrderCacheService;

    public ConsistencyCheckService(
            TickerOrderDomainService tickerOrderDomainService,
            OrderDeductionDomainService orderDeductionDomainService,
            StockOrderCacheService stockOrderCacheService
    ) {
        this.tickerOrderDomainService = tickerOrderDomainService;
        this.orderDeductionDomainService = orderDeductionDomainService;
        this.stockOrderCacheService = stockOrderCacheService;
    }

    public ConsistencySnapshot getConsistency(Long ticketItemId, String yearMonth) {
        String normalizedYearMonth = OrderDateSupport.normalizeYearMonth(yearMonth);
        int redisStock = ticketItemId == null ? -1 : stockOrderCacheService.getStockCache(ticketItemId);
        int dbStock = ticketItemId == null ? -1 : tickerOrderDomainService.getStockAvailable(ticketItemId);
        long orderCount = orderDeductionDomainService.countOrders(normalizedYearMonth);

        // Mathematical consistency: initialStock = current DB stock + orders already placed
        // expectedRedisStock = initialStock - orderCount  (i.e. dbStock, in a perfect world)
        int initialStock = (int) (dbStock + orderCount);
        int expectedRedisStock = (int) (initialStock - orderCount); // equals dbStock
        int driftAmount = redisStock - expectedRedisStock;

        return ConsistencySnapshot.builder()
                .ticketItemId(ticketItemId)
                .yearMonth(normalizedYearMonth)
                .redisStockAfter(redisStock)
                .dbStockAfter(dbStock)
                .dbOrderCount(orderCount)
                .oversoldCount(Math.max(0, -dbStock))
                .initialStock(initialStock)
                .expectedRedisStock(expectedRedisStock)
                .driftAmount(driftAmount)
                .redisDbInconsistencyCount(driftAmount != 0 ? 1 : 0)
                .build();
    }
}
