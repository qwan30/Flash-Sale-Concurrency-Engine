package com.xxxx.ddd.controller.http.reservation;

import java.util.UUID;

public record ReservationProcessingResponse(
        UUID reservationId,
        UUID operationId,
        String status,
        String journalState,
        int retryAfterSeconds,
        String traceId
) {
}
