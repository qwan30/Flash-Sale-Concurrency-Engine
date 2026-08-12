package com.xxxx.ddd.application.reservation;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CreateReservationCommandTest {

    @Test
    void rejectsANonPositiveTicketItemId() {
        assertThatThrownBy(() -> new CreateReservationCommand(
                0L, 1, UUID.randomUUID(), "idempotency-key"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("ticketItemId must be positive");
    }

    @Test
    void rejectsQuantityOutsideTheReservationBound() {
        assertThatThrownBy(() -> new CreateReservationCommand(
                42L, 5, UUID.randomUUID(), "idempotency-key"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("quantity must be between 1 and 4");
    }

    @Test
    void rejectsAMissingDemoActor() {
        assertThatThrownBy(() -> new CreateReservationCommand(
                42L, 1, null, "idempotency-key"))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("demoActorId");
    }

    @Test
    void rejectsABlankIdempotencyKey() {
        assertThatThrownBy(() -> new CreateReservationCommand(
                42L, 1, UUID.randomUUID(), "  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("idempotencyKey is required");
    }
}
