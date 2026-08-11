package com.xxxx.ddd.application.reservation.port;

import com.xxxx.ddd.domain.reservation.InventorySnapshot;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public interface ReservationRepairRepository {

    Optional<RepairContext> start(UUID repairId, long ticketItemId, String disposition);

    Optional<RepairContext> find(UUID repairId);

    boolean markVerified(UUID repairId, String disposition);

    boolean close(UUID repairId);

    boolean open(UUID repairId);

    boolean complete(UUID repairId, String disposition);

    boolean restart(UUID repairId, String disposition);

    boolean fail(UUID repairId, String disposition);

    enum RepairState {
        STARTED,
        VERIFIED,
        COMPLETED,
        FAILED
    }

    record RepairContext(
            UUID repairId,
            long ticketItemId,
            long previousFenceVersion,
            long newFenceVersion,
            InventorySnapshot snapshot,
            RepairState state,
            String disposition
    ) {
        public RepairContext {
            Objects.requireNonNull(repairId, "repairId must not be null");
            if (ticketItemId <= 0 || previousFenceVersion < 0 || newFenceVersion <= previousFenceVersion) {
                throw new IllegalArgumentException("invalid repair fence context");
            }
            Objects.requireNonNull(snapshot, "snapshot must not be null");
            Objects.requireNonNull(state, "state must not be null");
            if (disposition == null || disposition.isBlank()) {
                throw new IllegalArgumentException("repair disposition is required");
            }
        }
    }
}
