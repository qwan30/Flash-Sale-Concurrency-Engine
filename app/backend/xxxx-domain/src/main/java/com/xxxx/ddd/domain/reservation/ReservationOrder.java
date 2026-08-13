package com.xxxx.ddd.domain.reservation;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record ReservationOrder(
        UUID id,
        UUID reservationId,
        long ticketItemId,
        UUID demoActorId,
        int quantity,
        Instant confirmedAt) {

    public ReservationOrder {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(reservationId, "reservationId must not be null");
        Objects.requireNonNull(demoActorId, "demoActorId must not be null");
        Objects.requireNonNull(confirmedAt, "confirmedAt must not be null");
        if (ticketItemId <= 0) {
            throw new IllegalArgumentException("ticketItemId must be positive");
        }
        if (quantity < 1 || quantity > 4) {
            throw new IllegalArgumentException("quantity must be between 1 and 4");
        }
    }
}
