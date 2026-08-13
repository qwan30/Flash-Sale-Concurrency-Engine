package com.xxxx.ddd.application.reservation.port;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OperationJournalRepository {

    JournalEntry claimCreate(JournalEntry candidate);

    void recordTerminal(JournalEntry candidate);

    Optional<JournalEntry> findByOperationId(UUID operationId);

    Optional<JournalEntry> findByReservationId(UUID reservationId);

    Optional<JournalEntry> findPendingTerminal(UUID reservationId, OperationType operationType);

    boolean transition(
            UUID operationId,
            JournalState expectedState,
            JournalState nextState,
            String resultCode,
            Integer resultStockAfter
    );

    boolean scheduleRetry(
            UUID operationId,
            JournalState expectedState,
            String errorCode,
            Instant nextAttemptAt
    );

    int attempts(UUID operationId);

    Optional<UUID> repairId(UUID operationId);

    boolean attachRepairId(UUID operationId, JournalState expectedState, UUID repairId, String resultCode);

    List<JournalEntry> claimRecoverable(String workerId, int limit, Duration lease);

    enum JournalState {
        RECEIVED,
        REDIS_APPLYING,
        REJECTED,
        REDIS_APPLIED,
        COMMITTED,
        COMPENSATED,
        COMPENSATION_PENDING,
        MIRROR_PENDING,
        REPAIR_REQUIRED
    }

    enum OperationType {
        CREATE,
        CONFIRM,
        RELEASE,
        EXPIRE,
        COMPENSATE,
        MIRROR,
        REPAIR
    }

    record JournalEntry(
            UUID operationId,
            UUID reservationId,
            OperationType operationType,
            UUID demoActorId,
            String idempotencyKeyHash,
            String requestFingerprint,
            long ticketItemId,
            int quantity,
            long fenceVersion,
            JournalState state,
            String resultCode,
            Integer resultStockAfter
    ) {
        public JournalEntry {
            if (operationId == null || reservationId == null || operationType == null) {
                throw new NullPointerException("journal identifiers and type must not be null");
            }
            if (operationType == OperationType.CREATE) {
                if (demoActorId == null) {
                    throw new NullPointerException("create journal actor must not be null");
                }
                if (idempotencyKeyHash == null || idempotencyKeyHash.isBlank()) {
                    throw new IllegalArgumentException("create idempotencyKeyHash is required");
                }
            } else if (demoActorId != null || idempotencyKeyHash != null) {
                throw new IllegalArgumentException("terminal journal entries must not carry create identity");
            }
            if (requestFingerprint == null || requestFingerprint.isBlank()) {
                throw new IllegalArgumentException("requestFingerprint is required");
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

        public JournalEntry(
                UUID operationId,
                UUID reservationId,
                UUID demoActorId,
                String idempotencyKeyHash,
                String requestFingerprint,
                long ticketItemId,
                int quantity,
                long fenceVersion,
                JournalState state,
                String resultCode,
                Integer resultStockAfter
        ) {
            this(
                    operationId,
                    reservationId,
                    OperationType.CREATE,
                    demoActorId,
                    idempotencyKeyHash,
                    requestFingerprint,
                    ticketItemId,
                    quantity,
                    fenceVersion,
                    state,
                    resultCode,
                    resultStockAfter);
        }

        public static JournalEntry terminal(
                UUID operationId,
                UUID reservationId,
                OperationType operationType,
                String requestFingerprint,
                long ticketItemId,
                int quantity,
                long fenceVersion,
                JournalState state
        ) {
            if (operationType == OperationType.CREATE) {
                throw new IllegalArgumentException("terminal journal type must not be CREATE");
            }
            return new JournalEntry(
                    operationId,
                    reservationId,
                    operationType,
                    null,
                    null,
                    requestFingerprint,
                    ticketItemId,
                    quantity,
                    fenceVersion,
                    state,
                    null,
                    null);
        }
    }
}
