package com.xxxx.ddd.infrastructure.reservation.persistence;

import com.xxxx.ddd.application.reservation.port.OperationJournalRepository;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class JpaOperationJournalRepositoryAdapter implements OperationJournalRepository {

    private final EntityManager entityManager;

    public JpaOperationJournalRepositoryAdapter(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    public JournalEntry claimCreate(JournalEntry candidate) {
        if (candidate.operationType() != OperationType.CREATE) {
            throw new IllegalArgumentException("claimCreate requires a CREATE journal entry");
        }
        PersistenceValueSupport.requireHash(candidate.idempotencyKeyHash(), "idempotencyKeyHash");
        PersistenceValueSupport.requireHash(candidate.requestFingerprint(), "requestFingerprint");
        entityManager.createNativeQuery(
                        "INSERT INTO inventory_operation_journal "
                                + "(operation_id, reservation_id, operation_type, state, ticket_item_id, quantity, "
                                + "demo_actor_id, idempotency_key_hash, request_fingerprint, fence_version, "
                                + "attempts, next_attempt_at) "
                                + "VALUES (UUID_TO_BIN(:operationId), UUID_TO_BIN(:reservationId), 'CREATE', :state, "
                                + ":ticketItemId, :quantity, :demoActorId, UNHEX(:idempotencyKeyHash), "
                                + "UNHEX(:requestFingerprint), :fenceVersion, 0, NULL) "
                                + "ON DUPLICATE KEY UPDATE operation_id = operation_id")
                .setParameter("operationId", candidate.operationId().toString())
                .setParameter("reservationId", candidate.reservationId().toString())
                .setParameter("state", candidate.state().name())
                .setParameter("ticketItemId", candidate.ticketItemId())
                .setParameter("quantity", candidate.quantity())
                .setParameter("demoActorId", candidate.demoActorId().toString())
                .setParameter("idempotencyKeyHash", candidate.idempotencyKeyHash())
                .setParameter("requestFingerprint", candidate.requestFingerprint())
                .setParameter("fenceVersion", candidate.fenceVersion())
                .executeUpdate();

        return findByClaim(candidate.demoActorId().toString(), candidate.idempotencyKeyHash())
                .orElseThrow(() -> new IllegalStateException("journal claim was not persisted"));
    }

    @Override
    public void recordTerminal(JournalEntry candidate) {
        if (candidate.operationType() == OperationType.CREATE) {
            throw new IllegalArgumentException("recordTerminal requires a non-CREATE journal entry");
        }
        PersistenceValueSupport.requireHash(candidate.requestFingerprint(), "requestFingerprint");
        entityManager.createNativeQuery(
                        "INSERT INTO inventory_operation_journal "
                                + "(operation_id, reservation_id, operation_type, state, ticket_item_id, quantity, "
                                + "demo_actor_id, idempotency_key_hash, request_fingerprint, fence_version, "
                                + "attempts, next_attempt_at) "
                                + "VALUES (UUID_TO_BIN(:operationId), UUID_TO_BIN(:reservationId), :operationType, "
                                + ":state, :ticketItemId, :quantity, NULL, NULL, UNHEX(:requestFingerprint), "
                                + ":fenceVersion, 0, NULL)")
                .setParameter("operationId", candidate.operationId().toString())
                .setParameter("reservationId", candidate.reservationId().toString())
                .setParameter("operationType", candidate.operationType().name())
                .setParameter("state", candidate.state().name())
                .setParameter("ticketItemId", candidate.ticketItemId())
                .setParameter("quantity", candidate.quantity())
                .setParameter("requestFingerprint", candidate.requestFingerprint())
                .setParameter("fenceVersion", candidate.fenceVersion())
                .executeUpdate();
    }

    @Override
    public Optional<JournalEntry> findByOperationId(java.util.UUID operationId) {
        List<?> rows = entityManager.createNativeQuery(selectColumns()
                        + " WHERE operation_id = UUID_TO_BIN(:operationId)")
                .setParameter("operationId", operationId.toString())
                .getResultList();
        return rows.stream().findFirst().map(row -> toJournalEntry((Object[]) row));
    }

    @Override
    public Optional<JournalEntry> findByReservationId(java.util.UUID reservationId) {
        List<?> rows = entityManager.createNativeQuery(selectColumns()
                        + " WHERE reservation_id = UUID_TO_BIN(:reservationId) "
                        + "ORDER BY created_at DESC, operation_id DESC LIMIT 1")
                .setParameter("reservationId", reservationId.toString())
                .getResultList();
        return rows.stream().findFirst().map(row -> toJournalEntry((Object[]) row));
    }

    @Override
    public Optional<JournalEntry> findPendingTerminal(java.util.UUID reservationId, OperationType operationType) {
        if (operationType == OperationType.CREATE) {
            throw new IllegalArgumentException("pending terminal lookup requires a non-CREATE journal type");
        }
        List<?> rows = entityManager.createNativeQuery(selectColumns()
                        + " WHERE reservation_id = UUID_TO_BIN(:reservationId) "
                        + "AND state IN ('MIRROR_PENDING', 'REPAIR_REQUIRED') "
                        + "AND (operation_type = :operationType "
                        + "OR (operation_type = 'CREATE' AND "
                        + "(result_code = :terminalResultCode OR state = 'REPAIR_REQUIRED'))) "
                        + "ORDER BY created_at DESC, operation_id DESC LIMIT 1")
                .setParameter("reservationId", reservationId.toString())
                .setParameter("operationType", operationType.name())
                .setParameter("terminalResultCode", terminalResultCode(operationType))
                .getResultList();
        return rows.stream().findFirst().map(row -> toJournalEntry((Object[]) row));
    }

    @Override
    public boolean transition(
            java.util.UUID operationId,
            JournalState expectedState,
            JournalState nextState,
            String resultCode,
            Integer resultStockAfter
    ) {
        int updated = entityManager.createNativeQuery(
                        "UPDATE inventory_operation_journal "
                                + "SET state = :nextState, result_code = :resultCode, "
                                + "result_stock_after = :resultStockAfter, next_attempt_at = NULL, "
                                + "last_error_code = NULL, lease_owner = NULL, lease_until = NULL "
                                + "WHERE operation_id = UUID_TO_BIN(:operationId) AND state = :expectedState")
                .setParameter("nextState", nextState.name())
                .setParameter("resultCode", resultCode)
                .setParameter("resultStockAfter", resultStockAfter)
                .setParameter("operationId", operationId.toString())
                .setParameter("expectedState", expectedState.name())
                .executeUpdate();
        return updated == 1;
    }

    @Override
    public boolean scheduleRetry(
            java.util.UUID operationId,
            JournalState expectedState,
            String errorCode,
            Instant nextAttemptAt
    ) {
        if (errorCode == null || errorCode.isBlank() || nextAttemptAt == null) {
            throw new IllegalArgumentException("retry error and next attempt time are required");
        }
        int updated = entityManager.createNativeQuery(
                        "UPDATE inventory_operation_journal "
                                + "SET last_error_code = :errorCode, next_attempt_at = :nextAttemptAt, "
                                + "lease_owner = NULL, lease_until = NULL "
                                + "WHERE operation_id = UUID_TO_BIN(:operationId) AND state = :expectedState")
                .setParameter("errorCode", errorCode)
                .setParameter("nextAttemptAt", Timestamp.from(nextAttemptAt))
                .setParameter("operationId", operationId.toString())
                .setParameter("expectedState", expectedState.name())
                .executeUpdate();
        return updated == 1;
    }

    @Override
    public int attempts(java.util.UUID operationId) {
        List<?> rows = entityManager.createNativeQuery(
                        "SELECT attempts FROM inventory_operation_journal "
                                + "WHERE operation_id = UUID_TO_BIN(:operationId)")
                .setParameter("operationId", operationId.toString())
                .getResultList();
        return rows.isEmpty() ? 0 : ((Number) rows.get(0)).intValue();
    }

    @Override
    @Transactional
    public Optional<java.util.UUID> repairId(java.util.UUID operationId) {
        List<?> rows = entityManager.createNativeQuery(
                        "SELECT repair_id FROM inventory_operation_journal "
                                + "WHERE operation_id = UUID_TO_BIN(:operationId)")
                .setParameter("operationId", operationId.toString())
                .getResultList();
        return rows.stream()
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .map(PersistenceValueSupport::uuid);
    }

    @Override
    @Transactional
    public boolean attachRepairId(
            java.util.UUID operationId,
            JournalState expectedState,
            java.util.UUID repairId,
            String resultCode
    ) {
        if (repairId == null || resultCode == null || resultCode.isBlank()) {
            throw new IllegalArgumentException("repair id and result code are required");
        }
        int updated = entityManager.createNativeQuery(
                        "UPDATE inventory_operation_journal "
                                + "SET repair_id = UUID_TO_BIN(:repairId), result_code = :resultCode "
                                + "WHERE operation_id = UUID_TO_BIN(:operationId) AND state = :expectedState")
                .setParameter("repairId", repairId.toString())
                .setParameter("resultCode", resultCode)
                .setParameter("operationId", operationId.toString())
                .setParameter("expectedState", expectedState.name())
                .executeUpdate();
        return updated == 1;
    }

    @Override
    @Transactional
    public List<JournalEntry> claimRecoverable(String workerId, int limit, Duration lease) {
        if (workerId == null || workerId.isBlank()) {
            throw new IllegalArgumentException("workerId is required");
        }
        if (limit < 1 || lease.isNegative() || lease.isZero()) {
            throw new IllegalArgumentException("limit and lease must be positive");
        }

        Instant leaseUntil = Instant.now().plus(lease);
        String limitClause = Integer.toString(limit);
        entityManager.createNativeQuery(
                        "UPDATE inventory_operation_journal "
                                + "SET lease_owner = :workerId, lease_until = :leaseUntil, attempts = attempts + 1 "
                        + "WHERE state IN ('RECEIVED', 'REDIS_APPLYING', 'REDIS_APPLIED', 'COMPENSATION_PENDING', "
                                + "'MIRROR_PENDING', 'REPAIR_REQUIRED') "
                                + "AND NOT (state = 'REPAIR_REQUIRED' AND attempts >= 5) "
                                + "AND (next_attempt_at IS NULL OR next_attempt_at <= UTC_TIMESTAMP(6)) "
                                + "AND (lease_until IS NULL OR lease_until <= UTC_TIMESTAMP(6)) "
                                + "ORDER BY created_at, operation_id LIMIT " + limitClause)
                .setParameter("workerId", workerId)
                .setParameter("leaseUntil", Timestamp.from(leaseUntil))
                .executeUpdate();

        List<?> rows = entityManager.createNativeQuery(selectColumns()
                        + " WHERE lease_owner = :workerId AND lease_until = :leaseUntil "
                        + "ORDER BY created_at, operation_id")
                .setParameter("workerId", workerId)
                .setParameter("leaseUntil", Timestamp.from(leaseUntil))
                .getResultList();
        if (rows.isEmpty()) {
            return List.of();
        }
        return rows.stream().map(row -> toJournalEntry((Object[]) row)).toList();
    }

    private Optional<JournalEntry> findByClaim(String actorId, String idempotencyKeyHash) {
        List<?> rows = entityManager.createNativeQuery(selectColumns()
                        + " WHERE operation_type = 'CREATE' AND demo_actor_id = :demoActorId "
                        + "AND idempotency_key_hash = UNHEX(:idempotencyKeyHash)")
                .setParameter("demoActorId", actorId)
                .setParameter("idempotencyKeyHash", idempotencyKeyHash)
                .getResultList();
        return rows.stream().findFirst().map(row -> toJournalEntry((Object[]) row));
    }

    private static String selectColumns() {
        return "SELECT operation_id, reservation_id, operation_type, demo_actor_id, idempotency_key_hash, "
                + "request_fingerprint, ticket_item_id, quantity, fence_version, state, "
                + "result_code, result_stock_after FROM inventory_operation_journal";
    }

    private static String terminalResultCode(OperationType operationType) {
        return switch (operationType) {
            case RELEASE -> "RELEASED";
            case EXPIRE -> "EXPIRED";
            default -> throw new IllegalArgumentException(
                    "pending terminal lookup requires release or expiry operation type");
        };
    }

    private static JournalEntry toJournalEntry(Object[] row) {
        return new JournalEntry(
                PersistenceValueSupport.uuid(row[0]),
                PersistenceValueSupport.uuid(row[1]),
                OperationType.valueOf(String.valueOf(row[2])),
                row[3] == null ? null : java.util.UUID.fromString(String.valueOf(row[3])),
                row[4] == null ? null : PersistenceValueSupport.hex(row[4]),
                PersistenceValueSupport.hex(row[5]),
                ((Number) row[6]).longValue(),
                ((Number) row[7]).intValue(),
                ((Number) row[8]).longValue(),
                JournalState.valueOf(String.valueOf(row[9])),
                row[10] == null ? null : String.valueOf(row[10]),
                row[11] == null ? null : ((Number) row[11]).intValue());
    }
}
