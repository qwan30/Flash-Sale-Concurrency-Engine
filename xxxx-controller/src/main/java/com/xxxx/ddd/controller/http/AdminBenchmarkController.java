package com.xxxx.ddd.controller.http;

import com.xxxx.ddd.application.model.order.BenchmarkResetRequest;
import com.xxxx.ddd.application.model.order.BenchmarkResetResponse;
import com.xxxx.ddd.application.model.order.ConsistencySnapshot;
import com.xxxx.ddd.application.model.order.CreateOrderResponse;
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

@RestController
public class AdminBenchmarkController {

    @Autowired
    private TicketOrderAppService ticketOrderAppService;

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
}
