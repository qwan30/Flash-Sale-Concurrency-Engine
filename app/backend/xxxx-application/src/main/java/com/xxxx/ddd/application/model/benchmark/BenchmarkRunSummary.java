package com.xxxx.ddd.application.model.benchmark;

import com.xxxx.ddd.application.model.order.OrderStrategy;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BenchmarkRunSummary {
    private String runId;
    private String date;
    private String machine;
    private OrderStrategy strategy;
    private Integer totalRequests;
    private Integer concurrency;
    private Double throughput;
    private Double averageMs;
    private Integer p95Ms;
    private Integer p99Ms;
    private Integer successOrders;
    private Integer failedOrders;
    private Integer oversoldCount;
    private Integer redisStockAfter;
    private Integer dbStockAfter;
    private Long dbOrderCount;
    private Integer redisDbInconsistencyCount;
    private String status;
}
