package com.xxxx.ddd.controller.http.reservation;

import java.time.Instant;
import java.util.UUID;

public record ReservationResponse(
        UUID reservationId,
        UUID operationId,
        Long ticketItemId,
        UUID demoActorId,
        Integer quantity,
        String status,
        Instant expiresAt,
        Instant terminalAt,
        UUID orderId,
        String outcome,
        String resultCode,
        Integer stockAfter
) {
}
