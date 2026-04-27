package com.xxxx.ddd.application.model.order;

import lombok.Data;

@Data
public class BenchmarkResetRequest {
    private Long ticketItemId;
    private Integer stock;
    private String yearMonth;
}
