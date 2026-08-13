package com.xxxx.ddd.controller.http.reservation;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;

public record CreateReservationRequest(
        @Positive long ticketItemId,
        @Min(1) @Max(4) int quantity
) {
}
