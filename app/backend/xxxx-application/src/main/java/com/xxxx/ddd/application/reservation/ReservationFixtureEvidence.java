package com.xxxx.ddd.application.reservation;

import java.util.Objects;

/**
 * Read-only correctness and convergence snapshot for one local reservation fixture.
 *
 * <p>{@code invariantPass} is the durable MySQL bucket invariant. {@code parityPass} covers
 * the Redis mirror contract (initial/available/fence/admission); reserved and confirmed are
 * durable lifecycle buckets and are refreshed by fenced repair rather than every Lua admit.
 */
public record ReservationFixtureEvidence(
        long ticketItemId,
        int initial,
        int durableAvailable,
        int durableReserved,
        int durableConfirmed,
        long durableFenceVersion,
        String durableAdmissionState,
        int redisInitial,
        int redisAvailable,
        int redisReserved,
        int redisConfirmed,
        long redisFenceVersion,
        String redisAdmissionState,
        long pendingJournal,
        long pendingOutbox,
        double oldestOutboxAgeSeconds,
        long duplicateReservations,
        long duplicateOrders,
        long oversoldUnits,
        long negativeStockUnits,
        long finalDriftUnits,
        long acceptedUnits,
        boolean invariantPass,
        boolean parityPass,
        long capturedAtEpochMillis
) {

    public ReservationFixtureEvidence {
        if (ticketItemId <= 0 || initial < 0 || durableReserved < 0 || durableConfirmed < 0
                || redisInitial < 0 || redisReserved < 0 || redisConfirmed < 0
                || durableFenceVersion < 0 || redisFenceVersion < 0
                || pendingJournal < 0 || pendingOutbox < 0 || oldestOutboxAgeSeconds < 0
                || duplicateReservations < 0 || duplicateOrders < 0 || oversoldUnits < 0
                || negativeStockUnits < 0 || finalDriftUnits < 0 || acceptedUnits < 0
                || capturedAtEpochMillis <= 0) {
            throw new IllegalArgumentException("invalid reservation fixture evidence");
        }
        Objects.requireNonNull(durableAdmissionState, "durableAdmissionState must not be null");
        Objects.requireNonNull(redisAdmissionState, "redisAdmissionState must not be null");
    }
}
