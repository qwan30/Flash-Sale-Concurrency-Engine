package com.xxxx.ddd.application.reservation.port;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OperationJournalRepository {

    JournalEntry claimCreate(JournalEntry candidate);

    Optional<JournalEntry> findByOperationId(UUID operationId);

    boolean transition(
            UUID operationId,
            JournalState expectedState,
            JournalState nextState,
            String resultCode,
            Integer resultStockAfter
    );

    List<JournalEntry> claimRecoverable(String workerId, int limit, Duration lease);

    enum JournalState {
        RECEIVED,
        REJECTED,
        REDIS_APPLIED,
        COMMITTED,
        COMPENSATED,
        COMPENSATION_PENDING,
        MIRROR_PENDING,
        REPAIR_REQUIRED
    }

    record JournalEntry(
            UUID operationId,
            UUID reservationId,
            UUID demoActorId,
            String idempotencyKeyHash,
            long ticketItemId,
            int quantity,
            long fenceVersion,
            JournalState state,
            String resultCode,
            Integer resultStockAfter
    ) {
        public JournalEntry {
            if (operationId == null || reservationId == null || demoActorId == null) {
                throw new NullPointerException("journal identifiers must not be null");
            }
            if (idempotencyKeyHash == null || idempotencyKeyHash.isBlank()) {
                throw new IllegalArgumentException("idempotencyKeyHash is required");
            }
            if (ticketItemId <= 0) {
                throw new IllegalArgumentException("ticketItemId must be positive");
            }
            if (quantity < 1 || quantity > 4) {
                throw new IllegalArgumentException("quantity must be between 1 and 4");
            }
            if (fenceVersion < 0) {
                throw new IllegalArgumentException("fenceVersion must not be negative");
            }
            if (state == null) {
                throw new NullPointerException("state must not be null");
            }
            if (resultStockAfter != null && resultStockAfter < 0) {
                throw new IllegalArgumentException("resultStockAfter must not be negative");
            }
        }
    }
}
