package com.xxxx.ddd.application.reservation.port;

import com.xxxx.ddd.domain.reservation.Reservation;
import com.xxxx.ddd.domain.reservation.ReservationOrder;

import java.util.Optional;
import java.util.UUID;

public interface ReservationOrderRepository {

    Optional<ReservationOrder> findByReservationId(UUID reservationId);

    ReservationOrder create(UUID orderId, Reservation confirmedReservation);
}
