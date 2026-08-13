package com.xxxx.ddd.infrastructure.reservation.persistence;

import com.xxxx.ddd.application.reservation.port.ReservationRepository;
import com.xxxx.ddd.domain.reservation.Reservation;
import com.xxxx.ddd.domain.reservation.ReservationStatus;
import com.xxxx.ddd.domain.reservation.ReservationTransition;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JpaReservationRepositoryAdapter implements ReservationRepository {

    private final EntityManager entityManager;

    public JpaReservationRepositoryAdapter(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    public Optional<Reservation> findById(UUID reservationId) {
        List<?> rows = entityManager.createNativeQuery(
                        "SELECT id, ticket_item_id, demo_actor_id, quantity, status, expires_at, terminal_at "
                                + "FROM inventory_reservation WHERE id = UUID_TO_BIN(:reservationId)")
                .setParameter("reservationId", reservationId.toString())
                .getResultList();
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(toReservation((Object[]) rows.get(0)));
    }

    @Override
    public List<Reservation> findDueReserved(int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be positive");
        }
        List<?> rows = entityManager.createNativeQuery(
                        "SELECT id, ticket_item_id, demo_actor_id, quantity, status, expires_at, terminal_at "
                                + "FROM inventory_reservation "
                                + "WHERE status = 'RESERVED' AND expires_at <= UTC_TIMESTAMP(6) "
                                + "ORDER BY expires_at, id")
                .setMaxResults(limit)
                .getResultList();
        return rows.stream().map(row -> toReservation((Object[]) row)).toList();
    }

    @Override
    public boolean insertReserved(
            Reservation reservation,
            long fenceVersion,
            String idempotencyKeyHash,
            String requestFingerprint
    ) {
        PersistenceValueSupport.requireHash(idempotencyKeyHash, "idempotencyKeyHash");
        PersistenceValueSupport.requireHash(requestFingerprint, "requestFingerprint");
        int inserted = entityManager.createNativeQuery(
                        "INSERT INTO inventory_reservation "
                                + "(id, ticket_item_id, demo_actor_id, quantity, status, expires_at, terminal_at, "
                                + "idempotency_key_hash, request_fingerprint, version) "
                                + "SELECT UUID_TO_BIN(:reservationId), :ticketItemId, :demoActorId, :quantity, "
                                + "'RESERVED', :expiresAt, NULL, UNHEX(:idempotencyKeyHash), "
                                + "UNHEX(:requestFingerprint), 0 FROM DUAL WHERE EXISTS (SELECT 1 "
                                + "FROM inventory_stock_account "
                                + "WHERE ticket_item_id = :ticketItemId AND admission_state = 'OPEN' "
                                + "AND fence_version = :fenceVersion)")
                .setParameter("reservationId", reservation.id().toString())
                .setParameter("ticketItemId", reservation.ticketItemId())
                .setParameter("demoActorId", reservation.demoActorId().toString())
                .setParameter("quantity", reservation.quantity())
                .setParameter("expiresAt", Timestamp.from(reservation.expiresAt()))
                .setParameter("idempotencyKeyHash", idempotencyKeyHash)
                .setParameter("requestFingerprint", requestFingerprint)
                .setParameter("fenceVersion", fenceVersion)
                .executeUpdate();
        return inserted == 1;
    }

    @Override
    public Optional<Reservation> transitionIfCurrent(
            UUID reservationId,
            ReservationStatus expectedStatus,
            ReservationStatus targetStatus,
            Instant terminalAt,
            long fenceVersion
    ) {
        if (expectedStatus != ReservationStatus.RESERVED
                || !ReservationTransition.canTransition(expectedStatus, targetStatus)) {
            throw new IllegalArgumentException("unsupported reservation transition");
        }

        String expiryCondition = switch (targetStatus) {
            case CONFIRMED, RELEASED -> "AND r.expires_at > UTC_TIMESTAMP(6) ";
            case EXPIRED -> "AND r.expires_at <= UTC_TIMESTAMP(6) ";
            default -> "";
        };
        int transitioned = entityManager.createNativeQuery(
                        "UPDATE inventory_reservation r "
                                + "JOIN inventory_stock_account s ON s.ticket_item_id = r.ticket_item_id "
                                + "SET r.status = :targetStatus, r.terminal_at = UTC_TIMESTAMP(6), "
                                + "r.version = r.version + 1 "
                                + "WHERE r.id = UUID_TO_BIN(:reservationId) "
                                + "AND r.status = :expectedStatus "
                                + "AND s.admission_state = 'OPEN' "
                                + "AND s.fence_version = :fenceVersion "
                                + expiryCondition)
                .setParameter("targetStatus", targetStatus.name())
                .setParameter("expectedStatus", expectedStatus.name())
                .setParameter("reservationId", reservationId.toString())
                .setParameter("fenceVersion", fenceVersion)
                .executeUpdate();
        if (transitioned != 1) {
            return Optional.empty();
        }

        if (targetStatus == ReservationStatus.RELEASED || targetStatus == ReservationStatus.EXPIRED) {
            int restored = entityManager.createNativeQuery(
                            "UPDATE inventory_stock_account s "
                                    + "JOIN inventory_reservation r ON r.ticket_item_id = s.ticket_item_id "
                                    + "SET s.available_quantity = s.available_quantity + r.quantity, "
                                    + "s.version = s.version + 1 "
                                    + "WHERE r.id = UUID_TO_BIN(:reservationId) "
                                    + "AND s.admission_state = 'OPEN' "
                                    + "AND s.fence_version = :fenceVersion "
                                    + "AND s.available_quantity + r.quantity <= s.initial_quantity")
                    .setParameter("reservationId", reservationId.toString())
                    .setParameter("fenceVersion", fenceVersion)
                    .executeUpdate();
            if (restored != 1) {
                throw new IllegalStateException("terminal reservation transition could not restore stock");
            }
        }
        return findById(reservationId);
    }

    private static Reservation toReservation(Object[] row) {
        return new Reservation(
                PersistenceValueSupport.uuid(row[0]),
                ((Number) row[1]).longValue(),
                UUID.fromString(String.valueOf(row[2])),
                ((Number) row[3]).intValue(),
                ReservationStatus.valueOf(String.valueOf(row[4])),
                PersistenceValueSupport.instant(row[5]),
                PersistenceValueSupport.instant(row[6]));
    }
}
