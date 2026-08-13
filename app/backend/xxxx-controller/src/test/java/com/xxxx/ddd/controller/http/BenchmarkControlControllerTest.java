package com.xxxx.ddd.controller.http;

import com.xxxx.ddd.application.model.order.BenchmarkResetResponse;
import com.xxxx.ddd.application.model.order.CreateOrderResponse;
import com.xxxx.ddd.application.service.order.OrderReconciliationService;
import com.xxxx.ddd.application.service.order.TicketOrderAppService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class BenchmarkControlControllerTest {

    @Mock
    private TicketOrderAppService ticketOrderAppService;

    @Mock
    private OrderReconciliationService orderReconciliationService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(
                new BenchmarkControlController(
                        ticketOrderAppService,
                        orderReconciliationService,
                        "test-control-token"))
                .build();
    }

    @Test
    void resetRequiresTheSyntheticMarkerAndControlToken() throws Exception {
        mockMvc.perform(post("/admin/benchmarks/reset")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "ticketItemId": 4, "stock": 1000, "yearMonth": "202608" }
                                """))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/admin/benchmarks/reset")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Flashsale-Synthetic", "true")
                        .header("X-Flashsale-Control-Token", "incorrect")
                        .content("""
                                { "ticketItemId": 4, "stock": 1000, "yearMonth": "202608" }
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    void resetDelegatesOnlyAfterAuthorizedLocalLabAccess() throws Exception {
        when(ticketOrderAppService.resetBenchmark(any())).thenReturn(
                BenchmarkResetResponse.builder()
                        .success(true)
                        .message("Benchmark reset")
                        .ticketItemId(4L)
                        .stock(1000)
                        .yearMonth("202608")
                        .redisStockAfter(1000)
                        .dbStockAfter(1000)
                        .dbOrderCount(0L)
                        .build());

        mockMvc.perform(post("/admin/benchmarks/reset")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Flashsale-Synthetic", "true")
                        .header("X-Flashsale-Control-Token", "test-control-token")
                        .content("""
                                { "ticketItemId": 4, "stock": 1000, "yearMonth": "202608" }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.ticketItemId").value(4));
    }

    @Test
    void warmupRequiresTheControlToken() throws Exception {
        when(ticketOrderAppService.warmupStock(4L)).thenReturn(
                CreateOrderResponse.builder().success(true).code("SUCCESS").message("Stock warmed up").build());

        mockMvc.perform(post("/admin/tickets/4/stock/warmup")
                        .header("X-Flashsale-Synthetic", "true"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/admin/tickets/4/stock/warmup")
                        .header("X-Flashsale-Synthetic", "true")
                        .header("X-Flashsale-Control-Token", "test-control-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.code").value("SUCCESS"));
    }

    @Test
    void reconcileRequiresTheControlToken() throws Exception {
        mockMvc.perform(post("/admin/benchmarks/reconcile")
                        .param("ticketItemId", "4")
                        .header("X-Flashsale-Synthetic", "true"))
                .andExpect(status().isForbidden());
    }
}
