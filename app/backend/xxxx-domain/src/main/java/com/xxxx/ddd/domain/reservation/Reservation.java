package com.xxxx.ddd.domain.reservation;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record Reservation(
        UUID id,
        long ticketItemId,
        UUID demoActorId,
        int quantity,
        ReservationStatus status,
        Instant expiresAt,
        Instant terminalAt) {

    public Reservation {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(demoActorId, "demoActorId must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(expiresAt, "expiresAt must not be null");
        if (ticketItemId <= 0) {
            throw new IllegalArgumentException("ticketItemId must be positive");
        }
        if (quantity < 1 || quantity > 4) {
            throw new IllegalArgumentException("quantity must be between 1 and 4");
        }
    }
}
