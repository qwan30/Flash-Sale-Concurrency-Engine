package com.xxxx.ddd.application.service.benchmark;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xxxx.ddd.application.model.benchmark.BenchmarkRunDetail;
import com.xxxx.ddd.application.model.benchmark.BenchmarkRunSummary;
import com.xxxx.ddd.application.model.order.OrderStrategy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BenchmarkRunServiceTest {

    @TempDir
    private Path resultsDir;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void listRunsReadsRunManifestsNewestFirst() throws Exception {
        writeRun("REDIS_LUA-20260427-090000", 250.0);
        writeRun("REDIS_LUA_WITH_COMPENSATION-20260427-100000", 350.0);
        BenchmarkRunService service = new BenchmarkRunService(objectMapper, resultsDir.toString());

        List<BenchmarkRunSummary> runs = service.listRuns();

        assertThat(runs).extracting(BenchmarkRunSummary::getRunId)
                .containsExactly(
                        "REDIS_LUA_WITH_COMPENSATION-20260427-100000",
                        "REDIS_LUA-20260427-090000"
                );
        assertThat(runs.getFirst().getThroughput()).isEqualTo(350.0);
    }

    @Test
    void getRunRejectsPathTraversal() {
        BenchmarkRunService service = new BenchmarkRunService(objectMapper, resultsDir.toString());

        assertThat(service.getRun("../secret")).isNull();
        assertThat(service.getRun("nested/path")).isNull();
    }

    private void writeRun(String runId, double throughput) throws Exception {
        Path runDir = Files.createDirectories(resultsDir.resolve(runId));
        BenchmarkRunSummary summary = BenchmarkRunSummary.builder()
                .runId(runId)
                .date("2026-04-27")
                .machine("ACER")
                .strategy(OrderStrategy.REDIS_LUA_WITH_COMPENSATION)
                .totalRequests(5000)
                .concurrency(100)
                .throughput(throughput)
                .averageMs(200.0)
                .p95Ms(400)
                .p99Ms(500)
                .successOrders(1000)
                .failedOrders(4000)
                .oversoldCount(0)
                .redisStockAfter(0)
                .dbStockAfter(0)
                .dbOrderCount(1000L)
                .redisDbInconsistencyCount(0)
                .status("PASS")
                .build();
        BenchmarkRunDetail detail = BenchmarkRunDetail.builder()
                .summary(summary)
                .reset(Map.of())
                .warmup(Map.of())
                .consistency(Map.of())
                .artifacts(Map.of("jtl", "results.jtl"))
                .build();

        objectMapper.writeValue(runDir.resolve("run.json").toFile(), detail);
    }
}
