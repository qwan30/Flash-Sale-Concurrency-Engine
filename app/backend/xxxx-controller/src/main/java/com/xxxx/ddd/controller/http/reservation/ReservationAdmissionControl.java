package com.xxxx.ddd.controller.http.reservation;

import com.xxxx.ddd.application.reservation.ReservationFixtureGate;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.function.Supplier;

@Component
public class ReservationAdmissionControl {

    private final RateLimiter createRateLimiter;
    private final Bulkhead createBulkhead;
    private final Bulkhead terminalBulkhead;
    private final ReservationFixtureGate fixtureGate;
    private MeterRegistry meterRegistry;

    public ReservationAdmissionControl(
            @Value("${resilience4j.ratelimiter.instances.reservationCreate.limitForPeriod:40}") int createLimitForPeriod,
            @Value("${resilience4j.ratelimiter.instances.reservationCreate.limitRefreshPeriod:1s}") Duration createLimitRefreshPeriod,
            @Value("${resilience4j.ratelimiter.instances.reservationCreate.timeoutDuration:0}") Duration createTimeout,
            @Value("${resilience4j.bulkhead.instances.reservationCreate.maxConcurrentCalls:4}") int createMaxConcurrentCalls,
            @Value("${resilience4j.bulkhead.instances.reservationCreate.maxWaitDuration:0}") Duration createMaxWait,
            @Value("${resilience4j.bulkhead.instances.reservationTerminal.maxConcurrentCalls:2}") int terminalMaxConcurrentCalls,
            @Value("${resilience4j.bulkhead.instances.reservationTerminal.maxWaitDuration:100ms}") Duration terminalMaxWait,
            ReservationFixtureGate fixtureGate
    ) {
        this.createRateLimiter = RateLimiter.of("reservationCreate", RateLimiterConfig.custom()
                .limitForPeriod(createLimitForPeriod)
                .limitRefreshPeriod(createLimitRefreshPeriod)
                .timeoutDuration(createTimeout)
                .build());
        this.createBulkhead = Bulkhead.of("reservationCreate", io.github.resilience4j.bulkhead.BulkheadConfig.custom()
                .maxConcurrentCalls(createMaxConcurrentCalls)
                .maxWaitDuration(createMaxWait)
                .build());
        this.terminalBulkhead = Bulkhead.of("reservationTerminal", io.github.resilience4j.bulkhead.BulkheadConfig.custom()
                .maxConcurrentCalls(terminalMaxConcurrentCalls)
                .maxWaitDuration(terminalMaxWait)
                .build());
        this.fixtureGate = fixtureGate;
    }

    @Autowired(required = false)
    public void setMeterRegistry(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public <T> T executeCreate(Supplier<T> operation) {
        try {
            if (!createRateLimiter.acquirePermission()) {
                recordRejection("create", "RATE_LIMITED");
                throw RequestNotPermitted.createRequestNotPermitted(createRateLimiter);
            }
            return createBulkhead.executeSupplier(() -> fixtureGate.withReservationOperation(operation));
        } catch (RequestNotPermitted exception) {
            throw ReservationAdmissionException.rateLimited();
        } catch (BulkheadFullException exception) {
            recordRejection("create", "BULKHEAD_FULL");
            throw ReservationAdmissionException.saturated();
        }
    }

    public <T> T executeTerminal(Supplier<T> operation) {
        try {
            return terminalBulkhead.executeSupplier(() -> fixtureGate.withReservationOperation(operation));
        } catch (BulkheadFullException exception) {
            recordRejection("terminal", "BULKHEAD_FULL");
            throw ReservationAdmissionException.saturated();
        }
    }

    public <T> T executeRead(Supplier<T> operation) {
        return fixtureGate.withReservationOperation(operation);
    }

    private void recordRejection(String operation, String reason) {
        if (meterRegistry != null) {
            Counter.builder("flashsale.admission.rejections")
                    .tag("operation", operation)
                    .tag("reason", reason)
                    .register(meterRegistry)
                    .increment();
        }
    }
}
