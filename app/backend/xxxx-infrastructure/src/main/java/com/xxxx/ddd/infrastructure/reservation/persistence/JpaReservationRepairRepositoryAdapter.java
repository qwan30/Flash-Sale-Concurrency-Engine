package com.xxxx.ddd.infrastructure.reservation.persistence;

import com.xxxx.ddd.application.reservation.port.ReservationRepairRepository;
import com.xxxx.ddd.application.reservation.port.ReservationStockPort;
import com.xxxx.ddd.domain.reservation.InventorySnapshot;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JpaReservationRepairRepositoryAdapter implements ReservationRepairRepository {

    private final EntityManager entityManager;
    private final ReservationStockPort stock;

    public JpaReservationRepairRepositoryAdapter(EntityManager entityManager, ReservationStockPort stock) {
        this.entityManager = Objects.requireNonNull(entityManager, "entityManager must not be null");
        this.stock = Objects.requireNonNull(stock, "stock must not be null");
    }

    @Override
    @Transactional
    public Optional<RepairContext> start(UUID repairId, long ticketItemId, String disposition) {
        requireRepairInput(repairId, ticketItemId, disposition);
        Optional<RepairContext> existing = find(repairId);
        if (existing.isPresent()) {
            return existing;
        }

        List<?> stockRows = entityManager.createNativeQuery(
                        "SELECT initial_quantity, available_quantity, fence_version, admission_state "
                                + "FROM inventory_stock_account "
                                + "WHERE ticket_item_id = :ticketItemId FOR UPDATE")
                .setParameter("ticketItemId", ticketItemId)
                .getResultList();
        if (stockRows.isEmpty()) {
            return Optional.empty();
        }

        Optional<RepairContext> existingAfterLock = find(repairId);
        if (existingAfterLock.isPresent()) {
            return existingAfterLock;
        }

        Object[] stockRow = (Object[]) stockRows.get(0);
        int initial = ((Number) stockRow[0]).intValue();
        int available = ((Number) stockRow[1]).intValue();
        long previousFence = ((Number) stockRow[2]).longValue();
        if (!"OPEN".equals(String.valueOf(stockRow[3]))) {
            return Optional.empty();
        }

        List<?> activeRepairs = entityManager.createNativeQuery(
                        "SELECT repair_id FROM inventory_repair_journal "
                                + "WHERE ticket_item_id = :ticketItemId "
                                + "AND state IN ('STARTED', 'VERIFIED') FOR UPDATE")
                .setParameter("ticketItemId", ticketItemId)
                .getResultList();
        if (!activeRepairs.isEmpty()) {
            return Optional.empty();
        }

        long newFence = previousFence + 1;
        boolean redisFenceAttempted = false;
        try {
            redisFenceAttempted = true;
            String fencePublication = stock.publishFence(ticketItemId, newFence, "DRAINING");
            if (!"PUBLISHED".equals(fencePublication) && !"REPLAYED".equals(fencePublication)) {
                throw new IllegalStateException("repair fence publication was rejected: " + fencePublication);
            }
            int[] buckets = reservationBuckets(ticketItemId);

            int admissionUpdated = entityManager.createNativeQuery(
                            "UPDATE inventory_stock_account "
                                    + "SET fence_version = :newFence, admission_state = 'DRAINING', version = version + 1 "
                                    + "WHERE ticket_item_id = :ticketItemId AND fence_version = :previousFence "
                                    + "AND admission_state = 'OPEN'")
                    .setParameter("newFence", newFence)
                    .setParameter("previousFence", previousFence)
                    .setParameter("ticketItemId", ticketItemId)
                    .executeUpdate();
            if (admissionUpdated != 1) {
                throw new IllegalStateException("repair admission claim was lost");
            }

            int journalInserted = entityManager.createNativeQuery(
                            "INSERT INTO inventory_repair_journal "
                                    + "(repair_id, ticket_item_id, previous_fence_version, new_fence_version, state, "
                                    + "disposition, mysql_available_snapshot) "
                                    + "VALUES (UUID_TO_BIN(:repairId), :ticketItemId, :previousFence, :newFence, "
                                    + "'STARTED', :disposition, :available)")
                    .setParameter("repairId", repairId.toString())
                    .setParameter("ticketItemId", ticketItemId)
                    .setParameter("previousFence", previousFence)
                    .setParameter("newFence", newFence)
                    .setParameter("disposition", disposition)
                    .setParameter("available", available)
                    .executeUpdate();
            if (journalInserted != 1) {
                throw new IllegalStateException("repair journal insert was lost");
            }

            return Optional.of(new RepairContext(
                    repairId,
                    ticketItemId,
                    previousFence,
                    newFence,
                    new InventorySnapshot(ticketItemId, initial, available, buckets[0], buckets[1]),
                    RepairState.STARTED,
                    disposition));
        } catch (RuntimeException failure) {
            if (redisFenceAttempted) {
                try {
                    String rollback = stock.rollbackFence(ticketItemId, previousFence, newFence);
                    if (!"ROLLED_BACK".equals(rollback) && !"REPLAYED".equals(rollback)) {
                        failure.addSuppressed(new IllegalStateException(
                                "repair fence rollback was rejected: " + rollback));
                    }
                } catch (RuntimeException rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
            }
            throw failure;
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<RepairContext> find(UUID repairId) {
        if (repairId == null) {
            throw new IllegalArgumentException("repairId is required");
        }
        List<?> rows = entityManager.createNativeQuery(
                        "SELECT r.repair_id, r.ticket_item_id, r.previous_fence_version, r.new_fence_version, "
                                + "r.state, COALESCE(r.disposition, 'UNKNOWN'), s.initial_quantity, "
                                + "s.available_quantity, "
                                + "COALESCE(SUM(CASE WHEN v.status = 'RESERVED' THEN v.quantity ELSE 0 END), 0), "
                                + "COALESCE(SUM(CASE WHEN v.status = 'CONFIRMED' THEN v.quantity ELSE 0 END), 0) "
                                + "FROM inventory_repair_journal r "
                                + "JOIN inventory_stock_account s ON s.ticket_item_id = r.ticket_item_id "
                                + "LEFT JOIN inventory_reservation v ON v.ticket_item_id = r.ticket_item_id "
                                + "WHERE r.repair_id = UUID_TO_BIN(:repairId) "
                                + "GROUP BY r.repair_id, r.ticket_item_id, r.previous_fence_version, "
                                + "r.new_fence_version, r.state, r.disposition, s.initial_quantity, s.available_quantity")
                .setParameter("repairId", repairId.toString())
                .getResultList();
        return rows.stream().findFirst().map(row -> toContext((Object[]) row));
    }

    @Override
    @Transactional
    public boolean markVerified(UUID repairId, String disposition) {
        requireDisposition(disposition);
        return updateState(repairId, "STARTED", "VERIFIED", disposition) == 1;
    }

    @Override
    @Transactional
    public boolean close(UUID repairId) {
        RepairContext context = find(repairId)
                .orElseThrow(() -> new IllegalStateException("repair context not found"));
        int updated = entityManager.createNativeQuery(
                        "UPDATE inventory_stock_account SET admission_state = 'CLOSED', version = version + 1 "
                                + "WHERE ticket_item_id = :ticketItemId AND fence_version = :newFence "
                                + "AND admission_state = 'DRAINING'")
                .setParameter("ticketItemId", context.ticketItemId())
                .setParameter("newFence", context.newFenceVersion())
                .executeUpdate();
        if (updated == 1) {
            return true;
        }
        List<?> rows = entityManager.createNativeQuery(
                        "SELECT admission_state, fence_version FROM inventory_stock_account "
                                + "WHERE ticket_item_id = :ticketItemId")
                .setParameter("ticketItemId", context.ticketItemId())
                .getResultList();
        return rows.stream().map(row -> (Object[]) row).anyMatch(row ->
                "CLOSED".equals(String.valueOf(row[0]))
                        && ((Number) row[1]).longValue() == context.newFenceVersion());
    }

    @Override
    @Transactional
    public boolean open(UUID repairId) {
        RepairContext context = find(repairId)
                .orElseThrow(() -> new IllegalStateException("repair context not found"));
        int updated = entityManager.createNativeQuery(
                        "UPDATE inventory_stock_account SET admission_state = 'OPEN', version = version + 1 "
                                + "WHERE ticket_item_id = :ticketItemId AND fence_version = :newFence")
                .setParameter("ticketItemId", context.ticketItemId())
                .setParameter("newFence", context.newFenceVersion())
                .executeUpdate();
        if (updated == 1) {
            return true;
        }
        List<?> rows = entityManager.createNativeQuery(
                        "SELECT admission_state, fence_version FROM inventory_stock_account "
                                + "WHERE ticket_item_id = :ticketItemId")
                .setParameter("ticketItemId", context.ticketItemId())
                .getResultList();
        return rows.stream().map(row -> (Object[]) row).anyMatch(row ->
                "OPEN".equals(String.valueOf(row[0]))
                        && ((Number) row[1]).longValue() == context.newFenceVersion());
    }

    @Override
    @Transactional
    public boolean complete(UUID repairId, String disposition) {
        requireDisposition(disposition);
        int updated = updateState(repairId, "VERIFIED", "COMPLETED", disposition);
        if (updated == 1) {
            return true;
        }
        return find(repairId)
                .map(context -> context.state() == RepairState.COMPLETED
                        && disposition.equals(context.disposition()))
                .orElse(false);
    }

    @Override
    @Transactional
    public boolean restart(UUID repairId, String disposition) {
        requireDisposition(disposition);
        int updated = updateState(repairId, "FAILED", "STARTED", disposition);
        if (updated == 1) {
            return true;
        }
        return find(repairId)
                .map(context -> context.state() == RepairState.STARTED
                        && disposition.equals(context.disposition()))
                .orElse(false);
    }

    @Override
    @Transactional
    public boolean fail(UUID repairId, String disposition) {
        requireDisposition(disposition);
        return updateState(repairId, "STARTED", "FAILED", disposition) == 1
                || updateState(repairId, "VERIFIED", "FAILED", disposition) == 1;
    }

    private int updateState(UUID repairId, String expected, String next, String disposition) {
        return entityManager.createNativeQuery(
                        "UPDATE inventory_repair_journal SET state = :nextState, disposition = :disposition, "
                                + "completed_at = CASE WHEN :nextState IN ('COMPLETED', 'FAILED') "
                                + "THEN UTC_TIMESTAMP(6) ELSE completed_at END "
                                + "WHERE repair_id = UUID_TO_BIN(:repairId) AND state = :expectedState")
                .setParameter("nextState", next)
                .setParameter("disposition", disposition)
                .setParameter("repairId", repairId.toString())
                .setParameter("expectedState", expected)
                .executeUpdate();
    }

    private int[] reservationBuckets(long ticketItemId) {
        List<?> rows = entityManager.createNativeQuery(
                        "SELECT "
                                + "COALESCE(SUM(CASE WHEN status = 'RESERVED' THEN quantity ELSE 0 END), 0), "
                                + "COALESCE(SUM(CASE WHEN status = 'CONFIRMED' THEN quantity ELSE 0 END), 0) "
                                + "FROM inventory_reservation WHERE ticket_item_id = :ticketItemId")
                .setParameter("ticketItemId", ticketItemId)
                .getResultList();
        Object[] row = (Object[]) rows.get(0);
        return new int[]{((Number) row[0]).intValue(), ((Number) row[1]).intValue()};
    }

    private static RepairContext toContext(Object[] row) {
        return new RepairContext(
                PersistenceValueSupport.uuid(row[0]),
                ((Number) row[1]).longValue(),
                ((Number) row[2]).longValue(),
                ((Number) row[3]).longValue(),
                new InventorySnapshot(
                        ((Number) row[1]).longValue(),
                        ((Number) row[6]).intValue(),
                        ((Number) row[7]).intValue(),
                        ((Number) row[8]).intValue(),
                        ((Number) row[9]).intValue()),
                RepairState.valueOf(String.valueOf(row[4])),
                String.valueOf(row[5]));
    }

    private static void requireRepairInput(UUID repairId, long ticketItemId, String disposition) {
        if (repairId == null || ticketItemId <= 0) {
            throw new IllegalArgumentException("repair id and ticket item are required");
        }
        requireDisposition(disposition);
    }

    private static void requireDisposition(String disposition) {
        if (disposition == null || disposition.isBlank() || disposition.length() > 64) {
            throw new IllegalArgumentException("repair disposition must be 1..64 characters");
        }
    }
}
