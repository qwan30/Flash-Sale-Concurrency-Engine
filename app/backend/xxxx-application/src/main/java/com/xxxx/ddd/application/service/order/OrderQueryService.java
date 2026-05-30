package com.xxxx.ddd.application.service.order;

import com.xxxx.ddd.application.model.TicketOrderDTO;
import com.xxxx.ddd.application.service.order.support.OrderDateSupport;
import com.xxxx.ddd.domain.model.entity.TickerOrder;
import com.xxxx.ddd.domain.service.OrderDeductionDomainService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Reads order rows from the monthly sharded order tables used by the lab.
 *
 * <p>Order numbers include their creation timestamp, so single-order lookups can route to the
 * correct monthly table without scanning every shard.
 */
@Service
@Slf4j
public class OrderQueryService {

    private final OrderDeductionDomainService orderDeductionDomainService;

    public OrderQueryService(OrderDeductionDomainService orderDeductionDomainService) {
        this.orderDeductionDomainService = orderDeductionDomainService;
    }

    public List<TicketOrderDTO> findAll(String yearMonth) {
        String normalizedYearMonth = OrderDateSupport.normalizeYearMonth(yearMonth);
        orderDeductionDomainService.ensureMonthlyOrderTable(normalizedYearMonth);
        List<Object[]> results = orderDeductionDomainService.findAll(normalizedYearMonth);
        return mapOrderRows(results);
    }

    public List<TicketOrderDTO> findAllByUser(String yearMonth, Long userId) {
        String normalizedYearMonth = OrderDateSupport.normalizeYearMonth(yearMonth);
        orderDeductionDomainService.ensureMonthlyOrderTable(normalizedYearMonth);
        List<Object[]> results = orderDeductionDomainService.findAllByUser(normalizedYearMonth, userId);
        return mapOrderRows(results);
    }

    public boolean insertOrder(String yearMonth, TickerOrder tickerOrder) {
        String normalizedYearMonth = OrderDateSupport.normalizeYearMonth(yearMonth);
        orderDeductionDomainService.ensureMonthlyOrderTable(normalizedYearMonth);
        orderDeductionDomainService.insertOrder(normalizedYearMonth, tickerOrder);
        return true;
    }

    public TicketOrderDTO findByOrderNumber(String yearMonth, String orderNumber) {
        // Prefer the timestamp embedded in the order number over caller input to avoid shard drift.
        String orderTableMonth = extractYearMonthFromOrderNumber(orderNumber);
        orderDeductionDomainService.ensureMonthlyOrderTable(orderTableMonth);
        log.info("findByOrderNumber yearMonth={}", orderTableMonth);
        Object[] row = orderDeductionDomainService.findByOrderNumber(orderTableMonth, orderNumber);
        if (row == null) {
            return null;
        }
        return mapOrderRow(row);
    }

    private List<TicketOrderDTO> mapOrderRows(List<Object[]> results) {
        return results.stream().map(this::mapOrderRow).toList();
    }

    private TicketOrderDTO mapOrderRow(Object[] row) {
        return new TicketOrderDTO(
                ((Number) row[0]).intValue(),
                ((Number) row[1]).intValue(),
                (String) row[2],
                (BigDecimal) row[3],
                (String) row[4],
                ((Timestamp) row[5]).toLocalDateTime(),
                (String) row[6],
                ((Timestamp) row[7]).toLocalDateTime(),
                ((Timestamp) row[8]).toLocalDateTime()
        );
    }

    private String extractYearMonthFromOrderNumber(String orderNumber) {
        try {
            String[] parts = orderNumber.split("-");
            if (parts.length < 2) {
                throw new IllegalArgumentException("Invalid order number format");
            }
            long timestamp = Long.parseLong(parts[parts.length - 1]);
            LocalDateTime dateTime = Instant.ofEpochMilli(timestamp)
                    .atZone(ZoneId.systemDefault())
                    .toLocalDateTime();
            return dateTime.format(DateTimeFormatter.ofPattern("yyyyMM"));
        } catch (Exception e) {
            throw new RuntimeException("Failed to extract yearMonth from orderNumber: " + orderNumber, e);
        }
    }
}
