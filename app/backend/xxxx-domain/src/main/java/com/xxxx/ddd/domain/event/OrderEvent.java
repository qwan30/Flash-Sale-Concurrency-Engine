package com.xxxx.ddd.domain.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Domain event representing order lifecycle transitions.
 *
 * <p>Captured by the Transactional Outbox and published to Kafka after the
 * database transaction commits, guaranteeing at-least-once delivery.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderEvent {

    public static final String AGGREGATE_TYPE = "Order";

    private String orderNumber;
    private Long ticketItemId;
    private Long userId;
    private Integer quantity;
    private String strategy;
    private String eventType;
    private Integer redisStockAfter;
    private Integer dbStockAfter;

    /** Event type constants */
    public static final String ORDER_CREATED = "ORDER_CREATED";
    public static final String ORDER_REJECTED = "ORDER_REJECTED";
    public static final String STOCK_EXHAUSTED = "STOCK_EXHAUSTED";
}
