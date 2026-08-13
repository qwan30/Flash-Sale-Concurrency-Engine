package com.xxxx.ddd.application.reservation;

import com.xxxx.ddd.domain.reservation.Reservation;
import com.xxxx.ddd.domain.reservation.ReservationOrder;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record ReservationLifecycleResult(
        Outcome outcome,
        Optional<Reservation> reservation,
        Optional<ReservationOrder> order,
        Optional<UUID> operationId) {

    public ReservationLifecycleResult {
        Objects.requireNonNull(outcome, "outcome must not be null");
        Objects.requireNonNull(reservation, "reservation must not be null");
        Objects.requireNonNull(order, "order must not be null");
        Objects.requireNonNull(operationId, "operationId must not be null");
    }

    public enum Outcome {
        CONFIRMED,
        RELEASED,
        EXPIRED,
        REPLAYED,
        LATE_CONFLICT,
        NOT_FOUND,
        CONFLICT,
        PROCESSING,
        MIRROR_PENDING,
        REPAIR_REQUIRED
    }
}
