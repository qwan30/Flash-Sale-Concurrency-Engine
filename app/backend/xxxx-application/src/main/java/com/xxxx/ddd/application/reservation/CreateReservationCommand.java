package com.xxxx.ddd.application.reservation;

import java.util.Objects;
import java.util.UUID;

public record CreateReservationCommand(
        long ticketItemId,
        int quantity,
        UUID demoActorId,
        String idempotencyKey) {

    public CreateReservationCommand {
        if (ticketItemId <= 0) {
            throw new IllegalArgumentException("ticketItemId must be positive");
        }
        if (quantity < 1 || quantity > 4) {
            throw new IllegalArgumentException("quantity must be between 1 and 4");
        }
        Objects.requireNonNull(demoActorId, "demoActorId");
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey is required");
        }
    }
}
