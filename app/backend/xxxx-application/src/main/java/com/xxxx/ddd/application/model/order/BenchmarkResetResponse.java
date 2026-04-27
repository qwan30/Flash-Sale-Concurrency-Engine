package com.xxxx.ddd.application.model.order;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BenchmarkResetResponse {
    private boolean success;
    private String message;
    private Long ticketItemId;
    private Integer stock;
    private String yearMonth;
    private Integer redisStockAfter;
    private Integer dbStockAfter;
    private Long dbOrderCount;
}
