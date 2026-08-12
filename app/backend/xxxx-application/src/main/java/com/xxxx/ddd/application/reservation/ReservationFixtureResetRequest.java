package com.xxxx.ddd.application.reservation;

/**
 * Explicit local-lab request for reseeding the reservation benchmark fixture.
 *
 * <p>The reservation flag is intentionally part of the contract so a caller cannot
 * accidentally use the endpoint as a generic order benchmark reset.
 */
public record ReservationFixtureResetRequest(
        long ticketItemId,
        int stock,
        String strategy,
        boolean reservationFixture
) {
}
