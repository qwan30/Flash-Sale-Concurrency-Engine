package com.xxxx.ddd.application.reservation;

/**
 * Evidence returned by the local reservation fixture reset control.
 *
 * <p>The durable and Redis values are reported separately because a workload must
 * not start unless both sides prove the same fresh account.
 */
public record ReservationFixtureResult(
        boolean success,
        boolean reservationFixtureReset,
        long ticketItemId,
        int stock,
        int reservationStockAfter,
        int reservationRedisStockAfter,
        int reserved,
        int confirmed,
        long fenceVersion,
        String admissionState,
        String strategy,
        String message
) {

    public static ReservationFixtureResult success(
            long ticketItemId,
            int stock,
            long fenceVersion,
            String admissionState
    ) {
        return new ReservationFixtureResult(
                true,
                true,
                ticketItemId,
                stock,
                stock,
                stock,
                0,
                0,
                fenceVersion,
                admissionState,
                "TEST",
                "Reservation fixture reset");
    }

    public static ReservationFixtureResult failed(
            long ticketItemId,
            int stock,
            String strategy,
            String message
    ) {
        return new ReservationFixtureResult(
                false,
                false,
                ticketItemId,
                stock,
                -1,
                -1,
                -1,
                -1,
                -1,
                "UNKNOWN",
                strategy,
                message);
    }
}
