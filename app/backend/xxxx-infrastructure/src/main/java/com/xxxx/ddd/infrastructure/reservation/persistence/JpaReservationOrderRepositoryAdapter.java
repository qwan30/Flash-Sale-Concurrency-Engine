package com.xxxx.ddd.infrastructure.reservation.persistence;

import com.xxxx.ddd.application.reservation.port.ReservationOrderRepository;
import com.xxxx.ddd.domain.reservation.Reservation;
import com.xxxx.ddd.domain.reservation.ReservationOrder;
import com.xxxx.ddd.domain.reservation.ReservationStatus;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JpaReservationOrderRepositoryAdapter implements ReservationOrderRepository {

    private final EntityManager entityManager;

    public JpaReservationOrderRepositoryAdapter(EntityManager entityManager) {
        this.entityManager = Objects.requireNonNull(entityManager, "entityManager must not be null");
    }

    @Override
    public Optional<ReservationOrder> findByReservationId(UUID reservationId) {
        Objects.requireNonNull(reservationId, "reservationId must not be null");
        List<?> rows = entityManager.createNativeQuery(
                        "SELECT id, reservation_id, ticket_item_id, demo_actor_id, quantity, confirmed_at "
                                + "FROM reservation_order WHERE reservation_id = UUID_TO_BIN(:reservationId)")
                .setParameter("reservationId", reservationId.toString())
                .getResultList();
        return rows.stream().findFirst().map(row -> toOrder((Object[]) row));
    }

    @Override
    public ReservationOrder create(UUID orderId, Reservation confirmedReservation) {
        Objects.requireNonNull(orderId, "orderId must not be null");
        Objects.requireNonNull(confirmedReservation, "confirmedReservation must not be null");
        if (confirmedReservation.status() != ReservationStatus.CONFIRMED) {
            throw new IllegalArgumentException("only confirmed reservations can create orders");
        }

        int inserted = entityManager.createNativeQuery(
                        "INSERT INTO reservation_order "
                                + "(id, reservation_id, ticket_item_id, demo_actor_id, quantity, confirmed_at) "
                                + "VALUES (UUID_TO_BIN(:orderId), UUID_TO_BIN(:reservationId), :ticketItemId, "
                                + ":demoActorId, :quantity, UTC_TIMESTAMP(6))")
                .setParameter("orderId", orderId.toString())
                .setParameter("reservationId", confirmedReservation.id().toString())
                .setParameter("ticketItemId", confirmedReservation.ticketItemId())
                .setParameter("demoActorId", confirmedReservation.demoActorId().toString())
                .setParameter("quantity", confirmedReservation.quantity())
                .executeUpdate();
        if (inserted != 1) {
            throw new IllegalStateException("reservation order was not inserted");
        }
        return findByReservationId(confirmedReservation.id())
                .orElseThrow(() -> new IllegalStateException("reservation order disappeared after insert"));
    }

    private static ReservationOrder toOrder(Object[] row) {
        return new ReservationOrder(
                PersistenceValueSupport.uuid(row[0]),
                PersistenceValueSupport.uuid(row[1]),
                ((Number) row[2]).longValue(),
                UUID.fromString(String.valueOf(row[3])),
                ((Number) row[4]).intValue(),
                PersistenceValueSupport.instant(row[5]));
    }
}
