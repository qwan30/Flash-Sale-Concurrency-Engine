package com.xxxx.ddd.infrastructure.reservation.persistence;

import com.xxxx.ddd.application.reservation.port.InventoryRepository;
import com.xxxx.ddd.domain.reservation.InventorySnapshot;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

@Repository
public class JpaInventoryRepositoryAdapter implements InventoryRepository {

    private final EntityManager entityManager;

    public JpaInventoryRepositoryAdapter(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    public Optional<InventorySnapshot> findSnapshot(long ticketItemId) {
        List<?> rows = entityManager.createNativeQuery(
                        "SELECT s.ticket_item_id, s.initial_quantity, s.available_quantity, "
                                + "COALESCE(SUM(CASE WHEN r.status = 'RESERVED' THEN r.quantity ELSE 0 END), 0), "
                                + "COALESCE(SUM(CASE WHEN r.status = 'CONFIRMED' THEN r.quantity ELSE 0 END), 0) "
                                + "FROM inventory_stock_account s "
                                + "LEFT JOIN inventory_reservation r ON r.ticket_item_id = s.ticket_item_id "
                                + "WHERE s.ticket_item_id = :ticketItemId "
                                + "GROUP BY s.ticket_item_id, s.initial_quantity, s.available_quantity")
                .setParameter("ticketItemId", ticketItemId)
                .getResultList();
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Object[] row = (Object[]) rows.get(0);
        return Optional.of(new InventorySnapshot(
                ((Number) row[0]).longValue(),
                ((Number) row[1]).intValue(),
                ((Number) row[2]).intValue(),
                ((Number) row[3]).intValue(),
                ((Number) row[4]).intValue()));
    }

    @Override
    public OptionalLong findFenceVersion(long ticketItemId) {
        List<?> rows = entityManager.createNativeQuery(
                        "SELECT fence_version FROM inventory_stock_account "
                                + "WHERE ticket_item_id = :ticketItemId")
                .setParameter("ticketItemId", ticketItemId)
                .getResultList();
        if (rows.isEmpty()) {
            return OptionalLong.empty();
        }
        return OptionalLong.of(((Number) rows.get(0)).longValue());
    }

    @Override
    public Optional<String> findAdmissionState(long ticketItemId) {
        List<?> rows = entityManager.createNativeQuery(
                        "SELECT admission_state FROM inventory_stock_account "
                                + "WHERE ticket_item_id = :ticketItemId")
                .setParameter("ticketItemId", ticketItemId)
                .getResultList();
        return rows.stream().findFirst().map(String::valueOf);
    }

    @Override
    public boolean decrementIfAvailable(long ticketItemId, int quantity, long fenceVersion) {
        requireQuantity(quantity);
        int updated = entityManager.createNativeQuery(
                        "UPDATE inventory_stock_account "
                                + "SET available_quantity = available_quantity - :quantity, version = version + 1 "
                                + "WHERE ticket_item_id = :ticketItemId "
                                + "AND admission_state = 'OPEN' "
                                + "AND fence_version = :fenceVersion "
                                + "AND available_quantity >= :quantity")
                .setParameter("quantity", quantity)
                .setParameter("ticketItemId", ticketItemId)
                .setParameter("fenceVersion", fenceVersion)
                .executeUpdate();
        return updated == 1;
    }

    @Override
    public boolean restoreIfAdmitted(long ticketItemId, int quantity, long fenceVersion) {
        requireQuantity(quantity);
        int updated = entityManager.createNativeQuery(
                        "UPDATE inventory_stock_account "
                                + "SET available_quantity = available_quantity + :quantity, version = version + 1 "
                                + "WHERE ticket_item_id = :ticketItemId "
                                + "AND admission_state = 'OPEN' "
                                + "AND fence_version = :fenceVersion "
                                + "AND available_quantity + :quantity <= initial_quantity")
                .setParameter("quantity", quantity)
                .setParameter("ticketItemId", ticketItemId)
                .setParameter("fenceVersion", fenceVersion)
                .executeUpdate();
        return updated == 1;
    }

    private static void requireQuantity(int quantity) {
        if (quantity < 1 || quantity > 4) {
            throw new IllegalArgumentException("quantity must be between 1 and 4");
        }
    }
}
