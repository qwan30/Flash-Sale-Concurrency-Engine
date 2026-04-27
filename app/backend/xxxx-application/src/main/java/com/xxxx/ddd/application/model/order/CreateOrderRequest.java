package com.xxxx.ddd.application.model.order;

import lombok.Data;

@Data
public class CreateOrderRequest {
    private Long ticketItemId;
    private Long userId;
    private Integer quantity;
    private OrderStrategy strategy;
    private String idempotencyKey;
}
