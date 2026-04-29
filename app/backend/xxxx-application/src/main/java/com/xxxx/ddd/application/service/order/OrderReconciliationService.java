package com.xxxx.ddd.application.service.order;

import com.xxxx.ddd.application.model.order.ConsistencySnapshot;
import com.xxxx.ddd.application.service.order.cache.StockOrderCacheService;
import com.xxxx.ddd.application.service.order.support.OrderDateSupport;
import com.xxxx.ddd.domain.service.OrderDeductionDomainService;
import com.xxxx.ddd.domain.service.TickerOrderDomainService;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Scheduled reconciliation service that detects and repairs Redis/MySQL stock drift.
 *
 * <p>This fixes two edge cases in the Redis-first flash-sale architecture:
 * <ol>
 *   <li><b>Lost Stock (JVM Crash):</b> Application crashes after Redis Lua decrement
 *       succeeds but before MySQL commit — Redis stock stays decremented with no matching order.</li>
 *   <li><b>Double Fault (Compensation Failure):</b> MySQL insert fails and the compensation
 *       call to {@code restoreStockCache()} also fails due to a Redis blip.</li>
 * </ol>
 *
 * <p>The reconciliation logic:
 * <pre>
 *   expectedRedisStock = dbStockAvailable          // DB is source of truth
 *   drift              = actualRedisStock - expectedRedisStock
 *   if drift != 0  →   Redis SET to expectedRedisStock
 * </pre>
 */
@Service
@Slf4j
public class OrderReconciliationService {

    /** The ticket item ID to reconcile. In a real system this would iterate all active items. */
    private static final long DEFAULT_TICKET_ITEM_ID = 4L;

    private final TickerOrderDomainService tickerOrderDomainService;
    private final OrderDeductionDomainService orderDeductionDomainService;
    private final StockOrderCacheService stockOrderCacheService;
    private final ConsistencyCheckService consistencyCheckService;

    @Autowired(required = false)
    private MeterRegistry meterRegistry;

    public OrderReconciliationService(
            TickerOrderDomainService tickerOrderDomainService,
            OrderDeductionDomainService orderDeductionDomainService,
            StockOrderCacheService stockOrderCacheService,
            ConsistencyCheckService consistencyCheckService
    ) {
        this.tickerOrderDomainService = tickerOrderDomainService;
        this.orderDeductionDomainService = orderDeductionDomainService;
        this.stockOrderCacheService = stockOrderCacheService;
        this.consistencyCheckService = consistencyCheckService;
    }

    /**
     * Scheduled reconciliation — runs every 30 seconds.
     * In production this would iterate all active ticket items;
     * for the lab we reconcile the default ticket item.
     */
    @Scheduled(fixedDelay = 30_000, initialDelay = 10_000)
    public void scheduledReconcile() {
        try {
            ReconciliationResult result = reconcile(DEFAULT_TICKET_ITEM_ID, null);
            if (result.isDriftDetected()) {
                log.warn("RECONCILIATION: repaired drift of {} for ticketItemId={}",
                        result.getDriftAmount(), DEFAULT_TICKET_ITEM_ID);
            }
        } catch (Exception e) {
            log.error("RECONCILIATION: scheduled run failed for ticketItemId={}", DEFAULT_TICKET_ITEM_ID, e);
        }
    }

    /**
     * On-demand reconciliation for a specific ticket item.
     *
     * @param ticketItemId the ticket item to reconcile
     * @param yearMonth    optional year-month for order counting (null = current month)
     * @return the reconciliation result with before/after state
     */
    public ReconciliationResult reconcile(Long ticketItemId, String yearMonth) {
        String normalizedYearMonth = OrderDateSupport.normalizeYearMonth(yearMonth);

        // Step 1: Read current state from both stores
        int redisStock = stockOrderCacheService.getStockCache(ticketItemId);
        int dbStock = tickerOrderDomainService.getStockAvailable(ticketItemId);
        long orderCount = orderDeductionDomainService.countOrders(normalizedYearMonth);

        // Step 2: DB is the source of truth. Expected Redis = dbStockAvailable.
        int expectedRedisStock = dbStock;
        int drift = redisStock - expectedRedisStock;

        ReconciliationResult result = new ReconciliationResult();
        result.setTicketItemId(ticketItemId);
        result.setRedisStockBefore(redisStock);
        result.setDbStockBefore(dbStock);
        result.setOrderCount(orderCount);
        result.setExpectedRedisStock(expectedRedisStock);
        result.setDriftAmount(drift);

        if (drift != 0) {
            // Step 3: Correct Redis to match the DB truth
            stockOrderCacheService.setStockCache(ticketItemId, expectedRedisStock);
            result.setRedisStockAfter(expectedRedisStock);
            result.setDriftDetected(true);
            result.setRepaired(true);

            recordReconciliationMetric(drift);
            log.info("RECONCILIATION: ticketItemId={} drift={} redisWas={} redisCorrectedTo={}",
                    ticketItemId, drift, redisStock, expectedRedisStock);
        } else {
            result.setRedisStockAfter(redisStock);
            result.setDriftDetected(false);
            result.setRepaired(false);
        }

        return result;
    }

    /**
     * Get the current consistency snapshot (delegates to ConsistencyCheckService).
     */
    public ConsistencySnapshot getConsistencyAfterReconciliation(Long ticketItemId, String yearMonth) {
        return consistencyCheckService.getConsistency(ticketItemId, yearMonth);
    }

    private void recordReconciliationMetric(int drift) {
        if (meterRegistry == null) {
            return;
        }
        meterRegistry.counter(
                "flashsale.reconciliation",
                "action", "repair",
                "direction", drift > 0 ? "redis_over" : "redis_under"
        ).increment();
    }

    // --- Inner result class ---

    @lombok.Data
    public static class ReconciliationResult {
        private Long ticketItemId;
        private int redisStockBefore;
        private int dbStockBefore;
        private long orderCount;
        private int expectedRedisStock;
        private int driftAmount;
        private int redisStockAfter;
        private boolean driftDetected;
        private boolean repaired;
    }
}
