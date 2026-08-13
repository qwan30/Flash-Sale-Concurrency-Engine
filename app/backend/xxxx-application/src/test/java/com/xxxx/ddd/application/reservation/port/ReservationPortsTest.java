package com.xxxx.ddd.application.reservation.port;

import com.xxxx.ddd.application.reservation.port.ReservationStockPort.RedisApplyResult;
import com.xxxx.ddd.application.reservation.port.ReservationStockPort.RedisCompensationResult;
import com.xxxx.ddd.application.reservation.port.ReservationStockPort.RedisOperationState;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
        assertThat(RedisApplyResult.conflict().status())
                .isEqualTo(RedisApplyResult.Status.CONFLICT);
        assertThat(RedisCompensationResult.replayed(9).status())
                .isEqualTo(RedisCompensationResult.Status.REPLAYED);
        assertThat(RedisCompensationResult.notApplied().status())
                .isEqualTo(RedisCompensationResult.Status.NOT_APPLIED);
        assertThat(stock.operationState(OPERATION_ID)).contains(RedisOperationState.applied(7));
        stock.mirrorTerminalOnce(OPERATION_ID, 42L, 1, 3L);
        telemetry.record("create", "accepted", "none", Duration.ofMillis(1));
        faults.hit(FaultInjectionPort.FaultPoint.AFTER_REDIS_BEFORE_DB, OPERATION_ID);

        assertThatThrownBy(() -> stock.publishFence(42L, 3L, "ADMITTED"))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessage("fence publication is not supported");
        assertThatThrownBy(() -> stock.repairMirror(
                UUID.randomUUID(), 42L, 3L, 10, 7, 2, 1, "RESERVED"))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessage("repair mirror is not supported");
    }

    @Test
    void operationStateFactoryCarriesTheRedisIdentityFields() {
        RedisOperationState state = RedisOperationState.applied(42L, 2, 3L, 7);

        assertThat(state.status()).isEqualTo(RedisOperationState.Status.APPLIED);
        assertThat(state.stockAfter()).isEqualTo(7);
        assertThat(state.ticketItemId()).isEqualTo(42L);
        assertThat(state.quantity()).isEqualTo(2);
        assertThat(state.fenceVersion()).isEqualTo(3L);
    }
}
