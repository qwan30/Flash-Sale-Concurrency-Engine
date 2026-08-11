package com.xxxx.ddd.controller.http.reservation;

public record InventoryResponse(
        long ticketItemId,
        int initial,
        int available,
        int reserved,
        int confirmed
) {
}
