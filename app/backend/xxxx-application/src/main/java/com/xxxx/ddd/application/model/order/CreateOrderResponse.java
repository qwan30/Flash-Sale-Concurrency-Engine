package com.xxxx.ddd.application.model.order;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateOrderResponse {
    private boolean success;
    private String code;
    private String message;
    private String orderNumber;
    private OrderStrategy strategy;
    private Long ticketItemId;
    private Long userId;
    private Integer quantity;
    private Integer redisStockAfter;
    private Integer dbStockAfter;

    public static CreateOrderResponse failure(CreateOrderRequest request, String code, String message) {
        return base(request)
                .success(false)
                .code(code)
                .message(message)
                .build();
    }

    public static CreateOrderResponse success(CreateOrderRequest request, String orderNumber,
                                              Integer redisStockAfter, Integer dbStockAfter) {
        return base(request)
                .success(true)
                .code("SUCCESS")
                .message("Order created")
                .orderNumber(orderNumber)
                .redisStockAfter(redisStockAfter)
                .dbStockAfter(dbStockAfter)
                .build();
    }

    private static CreateOrderResponseBuilder base(CreateOrderRequest request) {
        if (request == null) {
            return CreateOrderResponse.builder();
        }
        return CreateOrderResponse.builder()
                .strategy(request.getStrategy())
                .ticketItemId(request.getTicketItemId())
                .userId(request.getUserId())
                .quantity(request.getQuantity());
    }
}
