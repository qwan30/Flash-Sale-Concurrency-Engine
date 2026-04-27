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
}
