package com.xxxx.ddd.controller.http;

import com.xxxx.ddd.application.model.benchmark.BenchmarkRunDetail;
import com.xxxx.ddd.application.model.benchmark.BenchmarkRunSummary;
import com.xxxx.ddd.application.model.order.BenchmarkResetRequest;
import com.xxxx.ddd.application.model.order.BenchmarkResetResponse;
import com.xxxx.ddd.application.model.order.ConsistencySnapshot;
import com.xxxx.ddd.application.model.order.CreateOrderResponse;
import com.xxxx.ddd.application.service.benchmark.BenchmarkRunService;
import com.xxxx.ddd.application.service.order.TicketOrderAppService;
import com.xxxx.ddd.controller.model.enums.ResultUtil;
import com.xxxx.ddd.controller.model.vo.ResultMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class AdminBenchmarkController {

    @Autowired
    private TicketOrderAppService ticketOrderAppService;

    @Autowired
    private BenchmarkRunService benchmarkRunService;

    @PostMapping("/admin/tickets/{ticketItemId}/stock/warmup")
    public ResultMessage<CreateOrderResponse> warmupStock(@PathVariable("ticketItemId") Long ticketItemId) {
        return ResultUtil.data(ticketOrderAppService.warmupStock(ticketItemId));
    }

    @PostMapping("/admin/benchmarks/reset")
    public ResultMessage<BenchmarkResetResponse> resetBenchmark(@RequestBody BenchmarkResetRequest request) {
        return ResultUtil.data(ticketOrderAppService.resetBenchmark(request));
    }

    @GetMapping("/admin/benchmarks/consistency")
    public ResultMessage<ConsistencySnapshot> consistency(
            @RequestParam("ticketItemId") Long ticketItemId,
            @RequestParam(value = "yearMonth", required = false) String yearMonth
    ) {
        return ResultUtil.data(ticketOrderAppService.getConsistency(ticketItemId, yearMonth));
    }

    @GetMapping("/admin/benchmarks/runs")
    public ResultMessage<List<BenchmarkRunSummary>> listRuns() {
        return ResultUtil.data(benchmarkRunService.listRuns());
    }

    @GetMapping("/admin/benchmarks/runs/{runId}")
    public ResultMessage<BenchmarkRunDetail> getRun(@PathVariable("runId") String runId) {
        return ResultUtil.data(benchmarkRunService.getRun(runId));
    }
}
