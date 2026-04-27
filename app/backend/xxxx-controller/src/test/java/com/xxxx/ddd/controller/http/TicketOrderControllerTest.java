package com.xxxx.ddd.controller.http;

import com.xxxx.ddd.application.model.order.CreateOrderRequest;
import com.xxxx.ddd.application.model.order.CreateOrderResponse;
import com.xxxx.ddd.application.model.order.OrderStrategy;
import com.xxxx.ddd.application.service.order.TicketOrderAppService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.test.util.ReflectionTestUtils;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class TicketOrderControllerTest {

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
    void postOrdersReturnsStructuredResponse() throws Exception {
        when(ticketOrderAppService.createOrder(any(CreateOrderRequest.class))).thenReturn(
                CreateOrderResponse.builder()
                        .success(true)
                        .code("SUCCESS")
                        .message("Order created")
                        .orderNumber("OKX-SGN-42-1777280000000")
                        .strategy(OrderStrategy.CONDITIONAL_DB)
                        .ticketItemId(4L)
                        .userId(42L)
                        .quantity(1)
                        .redisStockAfter(-1)
                        .dbStockAfter(999)
                        .build()
        );

        mockMvc.perform(post("/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "ticketItemId": 4,
                                  "userId": 42,
                                  "quantity": 1,
                                  "strategy": "CONDITIONAL_DB",
                                  "idempotencyKey": "idem-1"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.result.orderNumber").value("OKX-SGN-42-1777280000000"))
                .andExpect(jsonPath("$.result.userId").value(42));
    }
}
