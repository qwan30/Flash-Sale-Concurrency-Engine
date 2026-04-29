package com.xxxx.ddd.application.model.order;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConsistencySnapshot {
    private Long ticketItemId;
    private String yearMonth;
    private Integer redisStockAfter;
    private Integer dbStockAfter;
    private Long dbOrderCount;
    private Integer oversoldCount;
    private Integer redisDbInconsistencyCount;
    /** The initial stock configured for this ticket item (from DB stock_available + orders sold). */
    private Integer initialStock;
    /** The stock Redis *should* have: initialStock - dbOrderCount. */
    private Integer expectedRedisStock;
    /** Signed drift: redisStockAfter - expectedRedisStock. Positive = Redis over-counted, negative = Redis lost stock. */
    private Integer driftAmount;
}
