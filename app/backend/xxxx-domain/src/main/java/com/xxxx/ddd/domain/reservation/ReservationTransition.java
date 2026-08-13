package com.xxxx.ddd.domain.reservation;

import java.util.EnumSet;

public final class ReservationTransition {

    private ReservationTransition() {
    }

    public static boolean canTransition(ReservationStatus from, ReservationStatus to) {
        return from == ReservationStatus.RESERVED
                && EnumSet.of(
                ReservationStatus.CONFIRMED,
                ReservationStatus.RELEASED,
                ReservationStatus.EXPIRED
        ).contains(to);
    }
}
