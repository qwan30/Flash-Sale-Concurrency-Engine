package com.xxxx.ddd.controller.http;

import com.xxxx.ddd.application.model.order.BenchmarkResetRequest;
import com.xxxx.ddd.application.model.order.BenchmarkResetResponse;
import com.xxxx.ddd.application.model.order.CreateOrderResponse;
import com.xxxx.ddd.application.service.order.OrderReconciliationService;
import com.xxxx.ddd.application.service.order.TicketOrderAppService;
import com.xxxx.ddd.controller.model.enums.ResultUtil;
import com.xxxx.ddd.controller.model.vo.ResultMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Mutating legacy benchmark controls, available only in an explicitly enabled local-lab profile.
 */
@RestController
@Profile("benchmark")
@ConditionalOnProperty(prefix = "benchmark", name = "control-enabled", havingValue = "true")
public class BenchmarkControlController {

    private final TicketOrderAppService ticketOrderAppService;
    private final OrderReconciliationService orderReconciliationService;
    private final String controlToken;

    public BenchmarkControlController(
            TicketOrderAppService ticketOrderAppService,
            OrderReconciliationService orderReconciliationService,
            @Value("${benchmark.control-token:}") String controlToken
    ) {
        this.ticketOrderAppService = ticketOrderAppService;
        this.orderReconciliationService = orderReconciliationService;
        this.controlToken = controlToken;
    }

    @PostMapping("/admin/tickets/{ticketItemId}/stock/warmup")
    public ResultMessage<CreateOrderResponse> warmupStock(
            @PathVariable("ticketItemId") Long ticketItemId,
            @RequestHeader(value = "X-Flashsale-Synthetic", required = false) String syntheticHeader,
            @RequestHeader(value = "X-Flashsale-Control-Token", required = false) String token
    ) {
        requireControlAccess(syntheticHeader, token);
        return ResultUtil.data(ticketOrderAppService.warmupStock(ticketItemId));
    }

    @PostMapping("/admin/benchmarks/reset")
    public ResultMessage<BenchmarkResetResponse> resetBenchmark(
            @RequestHeader(value = "X-Flashsale-Synthetic", required = false) String syntheticHeader,
            @RequestHeader(value = "X-Flashsale-Control-Token", required = false) String token,
            @RequestBody BenchmarkResetRequest request
    ) {
        requireControlAccess(syntheticHeader, token);
        return ResultUtil.data(ticketOrderAppService.resetBenchmark(request));
    }

    @PostMapping("/admin/benchmarks/reconcile")
    public ResultMessage<OrderReconciliationService.ReconciliationResult> reconcile(
            @RequestHeader(value = "X-Flashsale-Synthetic", required = false) String syntheticHeader,
            @RequestHeader(value = "X-Flashsale-Control-Token", required = false) String token,
            @RequestParam("ticketItemId") Long ticketItemId,
            @RequestParam(value = "yearMonth", required = false) String yearMonth
    ) {
        requireControlAccess(syntheticHeader, token);
        return ResultUtil.data(orderReconciliationService.reconcile(ticketItemId, yearMonth));
    }

    private void requireControlAccess(String syntheticHeader, String token) {
        if (!"true".equalsIgnoreCase(syntheticHeader) || !matchesConfiguredToken(token)) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "benchmark control requires synthetic marker and configured control token");
        }
    }

    private boolean matchesConfiguredToken(String token) {
        if (controlToken == null || controlToken.isBlank() || token == null || token.isBlank()) {
            return false;
        }
        return MessageDigest.isEqual(
                controlToken.getBytes(StandardCharsets.UTF_8),
                token.getBytes(StandardCharsets.UTF_8));
    }
}
