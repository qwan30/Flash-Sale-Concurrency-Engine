package com.xxxx.ddd.controller.http;

import com.xxxx.ddd.application.model.benchmark.BenchmarkRunDetail;
import com.xxxx.ddd.application.model.benchmark.BenchmarkRunSummary;
import com.xxxx.ddd.application.model.order.ConsistencySnapshot;
import com.xxxx.ddd.application.model.order.OrderStrategy;
import com.xxxx.ddd.application.service.benchmark.BenchmarkRunService;
import com.xxxx.ddd.application.service.order.TicketOrderAppService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AdminBenchmarkControllerTest {

    private MockMvc mockMvc;

    @Mock
    private TicketOrderAppService ticketOrderAppService;

    @Mock
    private BenchmarkRunService benchmarkRunService;

    @BeforeEach
    void setUp() {
        AdminBenchmarkController controller = new AdminBenchmarkController();
        ReflectionTestUtils.setField(controller, "ticketOrderAppService", ticketOrderAppService);
        ReflectionTestUtils.setField(controller, "benchmarkRunService", benchmarkRunService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void consistencyReturnsSnapshot() throws Exception {
        when(ticketOrderAppService.getConsistency(anyLong(), anyString())).thenReturn(
                ConsistencySnapshot.builder()
                        .ticketItemId(4L)
                        .redisStockAfter(998)
                        .dbStockAfter(998)
                        .dbOrderCount(2L)
                        .oversoldCount(0)
                        .redisDbInconsistencyCount(0)
                        .driftAmount(0)
                        .build()
        );

        mockMvc.perform(get("/admin/benchmarks/consistency")
                        .param("ticketItemId", "4")
                        .param("yearMonth", "202606"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.redisStockAfter").value(998))
                .andExpect(jsonPath("$.result.dbStockAfter").value(998))
                .andExpect(jsonPath("$.result.oversoldCount").value(0))
                .andExpect(jsonPath("$.result.driftAmount").value(0));
    }

    @Test
    void consistencyReturnsStockDriftWhenRedisAndDbDiverge() throws Exception {
        when(ticketOrderAppService.getConsistency(anyLong(), anyString())).thenReturn(
                ConsistencySnapshot.builder()
                        .ticketItemId(4L)
                        .redisStockAfter(5)
                        .dbStockAfter(10)
                        .dbOrderCount(10L)
                        .oversoldCount(0)
                        .redisDbInconsistencyCount(1)
                        .driftAmount(-5)
                        .build()
        );

        mockMvc.perform(get("/admin/benchmarks/consistency")
                        .param("ticketItemId", "4")
                        .param("yearMonth", "202606"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.redisStockAfter").value(5))
                .andExpect(jsonPath("$.result.dbStockAfter").value(10))
                .andExpect(jsonPath("$.result.driftAmount").value(-5))
                .andExpect(jsonPath("$.result.redisDbInconsistencyCount").value(1));
    }

    @Test
    void listBenchmarkRunsReturnsRuns() throws Exception {
        when(benchmarkRunService.listRuns()).thenReturn(List.of(
                BenchmarkRunSummary.builder()
                        .runId("run-001")
                        .strategy(OrderStrategy.REDIS_LUA_WITH_COMPENSATION)
                        .totalRequests(80)
                        .successOrders(20)
                        .failedOrders(60)
                        .oversoldCount(0)
                        .build()
        ));

        mockMvc.perform(get("/admin/benchmarks/runs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result[0].runId").value("run-001"))
                .andExpect(jsonPath("$.result[0].successOrders").value(20))
                .andExpect(jsonPath("$.result[0].oversoldCount").value(0));
    }

    @Test
    void getBenchmarkRunReturnsDetail() throws Exception {
        when(benchmarkRunService.getRun("run-001")).thenReturn(
                BenchmarkRunDetail.builder()
                        .summary(BenchmarkRunSummary.builder()
                                .runId("run-001")
                                .strategy(OrderStrategy.REDIS_LUA_WITH_COMPENSATION)
                                .totalRequests(80)
                                .successOrders(20)
                                .failedOrders(60)
                                .oversoldCount(0)
                                .redisStockAfter(0)
                                .dbStockAfter(0)
                                .build())
                        .build()
        );

        mockMvc.perform(get("/admin/benchmarks/runs/run-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.summary.runId").value("run-001"))
                .andExpect(jsonPath("$.result.summary.redisStockAfter").value(0))
                .andExpect(jsonPath("$.result.summary.dbStockAfter").value(0));
    }
}
