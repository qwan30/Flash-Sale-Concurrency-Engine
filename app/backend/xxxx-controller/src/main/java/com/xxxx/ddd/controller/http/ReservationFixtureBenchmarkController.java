package com.xxxx.ddd.controller.http;

import com.xxxx.ddd.application.reservation.ReservationFixtureEvidence;
import com.xxxx.ddd.application.reservation.ReservationFixtureResetRequest;
import com.xxxx.ddd.application.reservation.ReservationFixtureResult;
import com.xxxx.ddd.application.service.reservation.ReservationFixtureService;
import com.xxxx.ddd.controller.model.enums.ResultUtil;
import com.xxxx.ddd.controller.model.vo.ResultMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Destructive local-lab controls for resetting and inspecting the reservation fixture.
 *
 * <p>The routes do not exist unless both the {@code benchmark} profile and the explicit
 * {@code benchmark.fixture-reset-enabled} opt-in are active. They remain token- and
 * synthetic-marker-gated even in that deliberately isolated runtime.
 */
@RestController
@Profile("benchmark")
@ConditionalOnProperty(prefix = "benchmark", name = "fixture-reset-enabled", havingValue = "true")
public class ReservationFixtureBenchmarkController {

    private final ReservationFixtureService reservationFixtureService;
    private final String fixtureResetToken;
    private final long fixtureTicketItemId;

    public ReservationFixtureBenchmarkController(
            ReservationFixtureService reservationFixtureService,
            @Value("${benchmark.fixture-reset-token:}") String fixtureResetToken,
            @Value("${benchmark.fixture-ticket-item-id:950015}") long fixtureTicketItemId
    ) {
        this.reservationFixtureService = reservationFixtureService;
        this.fixtureResetToken = fixtureResetToken;
        this.fixtureTicketItemId = fixtureTicketItemId;
    }

    @PostMapping("/admin/reservation-fixtures/reset")
    public ResultMessage<ReservationFixtureResult> resetReservationFixture(
            @RequestHeader(value = "X-Flashsale-Synthetic", required = false) String syntheticHeader,
            @RequestHeader(value = "X-Flashsale-Fixture-Token", required = false) String fixtureToken,
            @RequestBody ReservationFixtureResetRequest request
    ) {
        requireFixtureAccess(syntheticHeader, fixtureToken, "reservation fixture reset");
        requireFixtureTicket(request.ticketItemId());
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
        requireFixtureAccess(syntheticHeader, fixtureToken, "reservation fixture evidence");
        requireFixtureTicket(ticketItemId);
        return ResultUtil.data(reservationFixtureService.evidence(ticketItemId));
    }

    private void requireFixtureAccess(String syntheticHeader, String fixtureToken, String operation) {
        if (!"true".equalsIgnoreCase(syntheticHeader) || !matchesConfiguredFixtureToken(fixtureToken)) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    operation + " requires synthetic marker and configured fixture token");
        }
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

    private void requireFixtureTicket(long ticketItemId) {
        if (fixtureTicketItemId <= 0 || ticketItemId != fixtureTicketItemId) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "reservation fixture ticket is not authorized");
        }
    }
}
