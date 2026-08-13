package com.xxxx.ddd.domain.reservation;

public record InventorySnapshot(
        long ticketItemId,
        int initial,
        int available,
        int reserved,
        int confirmed) {

    public boolean invariantHolds() {
        return (long) initial == (long) available + reserved + confirmed;
    }
}
