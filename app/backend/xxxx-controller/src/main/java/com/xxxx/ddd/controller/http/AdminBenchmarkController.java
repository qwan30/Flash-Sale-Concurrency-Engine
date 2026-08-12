package com.xxxx.ddd.controller.http;

import com.xxxx.ddd.application.model.benchmark.BenchmarkRunDetail;
import com.xxxx.ddd.application.model.benchmark.BenchmarkRunSummary;
import com.xxxx.ddd.application.model.order.BenchmarkResetRequest;
import com.xxxx.ddd.application.model.order.BenchmarkResetResponse;
import com.xxxx.ddd.application.model.order.ConsistencySnapshot;
import com.xxxx.ddd.application.model.order.CreateOrderResponse;
import com.xxxx.ddd.application.reservation.ReservationFixtureResetRequest;
import com.xxxx.ddd.application.reservation.ReservationFixtureResult;
import com.xxxx.ddd.application.reservation.ReservationFixtureEvidence;
import com.xxxx.ddd.application.service.benchmark.BenchmarkRunService;
import com.xxxx.ddd.application.service.order.OrderReconciliationService;
import com.xxxx.ddd.application.service.reservation.ReservationFixtureService;
import com.xxxx.ddd.application.service.order.TicketOrderAppService;
import com.xxxx.ddd.controller.model.enums.ResultUtil;
import com.xxxx.ddd.controller.model.vo.ResultMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/**
 * Local operator endpoints for preparing benchmark data, checking stock drift, and reading saved
 * JMeter runs.
 *
 * <p>These endpoints are lab controls. They reset state and expose operational details, so they are
 * not part of a public buyer-facing API.
 */
@RestController
public class AdminBenchmarkController {

    @Autowired
    private TicketOrderAppService ticketOrderAppService;

    @Autowired
    private BenchmarkRunService benchmarkRunService;

    @Autowired
    private OrderReconciliationService orderReconciliationService;

    @Autowired
    private ReservationFixtureService reservationFixtureService;

    @Value("${benchmark.fixture-reset-token:}")
    private String fixtureResetToken;

    @PostMapping("/admin/tickets/{ticketItemId}/stock/warmup")
    public ResultMessage<CreateOrderResponse> warmupStock(@PathVariable("ticketItemId") Long ticketItemId) {
        return ResultUtil.data(ticketOrderAppService.warmupStock(ticketItemId));
    }

    @PostMapping("/admin/benchmarks/reset")
    public ResultMessage<BenchmarkResetResponse> resetBenchmark(@RequestBody BenchmarkResetRequest request) {
        return ResultUtil.data(ticketOrderAppService.resetBenchmark(request));
    }

    @PostMapping("/admin/reservation-fixtures/reset")
    public ResultMessage<ReservationFixtureResult> resetReservationFixture(
            @RequestHeader(value = "X-Flashsale-Synthetic", required = false) String syntheticHeader,
            @RequestHeader(value = "X-Flashsale-Fixture-Token", required = false) String fixtureToken,
            @RequestBody ReservationFixtureResetRequest request
    ) {
        if (!"true".equalsIgnoreCase(syntheticHeader)
                || !matchesConfiguredFixtureToken(fixtureToken)) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "reservation fixture reset requires synthetic marker and configured fixture token");
        }
        ReservationFixtureResult result = reservationFixtureService.reset(request);
        if (!result.success()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, result.message());
        }
        return ResultUtil.data(result);
    }

    @GetMapping("/admin/reservation-fixtures/evidence")
    public ResultMessage<ReservationFixtureEvidence> reservationFixtureEvidence(
            @RequestHeader(value = "X-Flashsale-Synthetic", required = false) String syntheticHeader,
            @RequestHeader(value = "X-Flashsale-Fixture-Token", required = false) String fixtureToken,
            @RequestParam("ticketItemId") long ticketItemId
    ) {
        if (!"true".equalsIgnoreCase(syntheticHeader)
                || !matchesConfiguredFixtureToken(fixtureToken)) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "reservation fixture evidence requires synthetic marker and configured fixture token");
        }
        return ResultUtil.data(reservationFixtureService.evidence(ticketItemId));
    }

    private boolean matchesConfiguredFixtureToken(String fixtureToken) {
        if (fixtureResetToken == null || fixtureResetToken.isBlank()
                || fixtureToken == null || fixtureToken.isBlank()) {
            return false;
        }
        return MessageDigest.isEqual(
                fixtureResetToken.getBytes(StandardCharsets.UTF_8),
                fixtureToken.getBytes(StandardCharsets.UTF_8));
    }

    @GetMapping("/admin/benchmarks/consistency")
    public ResultMessage<ConsistencySnapshot> consistency(
            @RequestParam("ticketItemId") Long ticketItemId,
            @RequestParam(value = "yearMonth", required = false) String yearMonth
    ) {
        return ResultUtil.data(ticketOrderAppService.getConsistency(ticketItemId, yearMonth));
    }

    @PostMapping("/admin/benchmarks/reconcile")
    public ResultMessage<OrderReconciliationService.ReconciliationResult> reconcile(
            @RequestParam("ticketItemId") Long ticketItemId,
            @RequestParam(value = "yearMonth", required = false) String yearMonth
    ) {
        return ResultUtil.data(orderReconciliationService.reconcile(ticketItemId, yearMonth));
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
