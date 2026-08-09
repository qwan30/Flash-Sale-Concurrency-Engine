package com.xxxx.ddd.application.reservation.port;

import com.xxxx.ddd.application.reservation.port.ReservationStockPort.RedisApplyResult;
import com.xxxx.ddd.application.reservation.port.ReservationStockPort.RedisCompensationResult;
import com.xxxx.ddd.application.reservation.port.ReservationStockPort.RedisOperationState;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ReservationPortsTest {

    private static final UUID OPERATION_ID = UUID.fromString("f0f30dd9-2f8e-4d35-9c1f-5c6c3ee08d7c");

    @Test
    void stockTelemetryAndFaultPortsExposeTheReservationContract() {
        ReservationStockPort stock = new ReservationStockPort() {
            @Override
            public RedisApplyResult applyOnce(UUID operationId, long ticketItemId, int quantity, long fenceVersion) {
                return RedisApplyResult.applied(7);
            }

            @Override
            public RedisCompensationResult compensateOnce(
                    UUID operationId,
                    long ticketItemId,
                    int quantity,
                    long fenceVersion
            ) {
                return RedisCompensationResult.compensated(9);
            }

            @Override
            public void mirrorTerminalOnce(UUID operationId, long ticketItemId, int delta, long fenceVersion) {
                // Compile-only contract implementation.
            }

            @Override
            public Optional<RedisOperationState> operationState(UUID operationId) {
                return Optional.of(RedisOperationState.applied(7));
            }
        };

        ReservationTelemetryPort telemetry = new NoOpReservationTelemetry();
        FaultInjectionPort faults = new NoOpFaultInjection();

        assertThat(stock.applyOnce(OPERATION_ID, 42L, 1, 3L).status())
                .isEqualTo(RedisApplyResult.Status.APPLIED);
        assertThat(stock.compensateOnce(OPERATION_ID, 42L, 1, 3L).status())
                .isEqualTo(RedisCompensationResult.Status.COMPENSATED);
        assertThat(stock.operationState(OPERATION_ID)).contains(RedisOperationState.applied(7));
        stock.mirrorTerminalOnce(OPERATION_ID, 42L, 1, 3L);
        telemetry.record("create", "accepted", "none", Duration.ofMillis(1));
        faults.hit(FaultInjectionPort.FaultPoint.AFTER_REDIS_BEFORE_DB, OPERATION_ID);
    }
}
