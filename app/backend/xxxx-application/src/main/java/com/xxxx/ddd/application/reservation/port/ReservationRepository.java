package com.xxxx.ddd.application.reservation.port;

import com.xxxx.ddd.domain.reservation.Reservation;
import com.xxxx.ddd.domain.reservation.ReservationStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReservationRepository {

    Optional<Reservation> findById(UUID reservationId);

    List<Reservation> findDueReserved(int limit);

    boolean insertReserved(
            Reservation reservation,
            long fenceVersion,
            String idempotencyKeyHash,
            String requestFingerprint
    );

    Optional<Reservation> transitionIfCurrent(
            UUID reservationId,
            ReservationStatus expectedStatus,
            ReservationStatus targetStatus,
            Instant terminalAt,
            long fenceVersion
    );
}
