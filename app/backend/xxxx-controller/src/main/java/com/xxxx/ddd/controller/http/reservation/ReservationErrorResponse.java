package com.xxxx.ddd.controller.http.reservation;

public record ReservationErrorResponse(
        String code,
        String message,
        boolean retryable,
        String traceId,
        Integer stockAfter
) {
}
