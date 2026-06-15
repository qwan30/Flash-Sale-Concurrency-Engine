package com.xxxx.ddd.controller.http;

import com.xxxx.ddd.application.model.order.CreateOrderRequest;
import com.xxxx.ddd.application.model.order.CreateOrderResponse;
import com.xxxx.ddd.application.model.order.OrderStrategy;
import com.xxxx.ddd.application.service.order.TicketOrderAppService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Negative test cases for POST /orders — verifies the controller properly
 * delegates validation failures and edge cases to the HTTP layer.
 */
@ExtendWith(MockitoExtension.class)
class TicketOrderControllerNegativeTest {

    private MockMvc mockMvc;

    @Mock
    private TicketOrderAppService ticketOrderAppService;

    @BeforeEach
    void setUp() {
        TicketOrderController controller = new TicketOrderController();
        ReflectionTestUtils.setField(controller, "ticketOrderAppService", ticketOrderAppService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void returnsBadRequestWhenServiceReportsInvalidRequest() throws Exception {
        when(ticketOrderAppService.createOrder(any(CreateOrderRequest.class))).thenReturn(
                CreateOrderResponse.builder()
                        .success(false)
                        .code("INVALID_REQUEST")
                        .message("Quantity must be greater than 0")
                        .build()
        );

        mockMvc.perform(post("/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "ticketItemId": 4,
                                  "userId": 42,
                                  "quantity": 0,
                                  "strategy": "CONDITIONAL_DB",
                                  "idempotencyKey": "idem-1"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void returnsSoldOutWhenStockExhausted() throws Exception {
        when(ticketOrderAppService.createOrder(any(CreateOrderRequest.class))).thenReturn(
                CreateOrderResponse.builder()
                        .success(false)
                        .code("SOLD_OUT")
                        .message("Stock exhausted")
                        .strategy(OrderStrategy.REDIS_LUA_WITH_COMPENSATION)
                        .ticketItemId(4L)
                        .userId(42L)
                        .quantity(1)
                        .build()
        );

        mockMvc.perform(post("/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "ticketItemId": 4,
                                  "userId": 42,
                                  "quantity": 1,
                                  "strategy": "REDIS_LUA_WITH_COMPENSATION",
                                  "idempotencyKey": "sold-out-key"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value(409));
    }

    @Test
    void acceptsOrderWithAllFourStrategies() throws Exception {
        for (OrderStrategy strategy : OrderStrategy.values()) {
            when(ticketOrderAppService.createOrder(any(CreateOrderRequest.class))).thenReturn(
                    CreateOrderResponse.builder()
                            .success(true)
                            .code("SUCCESS")
                            .message("Order created")
                            .orderNumber("ORD-" + strategy.name() + "-001")
                            .strategy(strategy)
                            .ticketItemId(4L)
                            .userId(42L)
                            .quantity(1)
                            .build()
            );

            mockMvc.perform(post("/orders")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(String.format("""
                                    {
                                      "ticketItemId": 4,
                                      "userId": 42,
                                      "quantity": 1,
                                      "strategy": "%s",
                                      "idempotencyKey": "key-%s"
                                    }
                                    """, strategy.name(), strategy.name())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.result.strategy").value(strategy.name()));
        }
    }

    @Test
    void returnsDbDecrementFailedWhenCompensationTriggered() throws Exception {
        when(ticketOrderAppService.createOrder(any(CreateOrderRequest.class))).thenReturn(
                CreateOrderResponse.builder()
                        .success(false)
                        .code("DB_STOCK_DECREMENT_FAILED")
                        .message("DB stock decrement failed after Redis deduction; stock restored")
                        .strategy(OrderStrategy.REDIS_LUA_WITH_COMPENSATION)
                        .ticketItemId(4L)
                        .userId(42L)
                        .quantity(1)
                        .build()
        );

        mockMvc.perform(post("/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "ticketItemId": 4,
                                  "userId": 42,
                                  "quantity": 1,
                                  "strategy": "REDIS_LUA_WITH_COMPENSATION",
                                  "idempotencyKey": "comp-fail-key"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value(409))
                .andExpect(jsonPath("$.result.code").value("DB_STOCK_DECREMENT_FAILED"));
    }
}
