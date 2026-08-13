package com.xxxx.ddd.application.reservation.port;

import java.util.Objects;

/**
 * Durable storage boundary for the local reservation workload fixture.
 */
public interface ReservationFixtureRepository {

    DurableState reset(long ticketItemId, int stock);

    EvidenceState evidence(long ticketItemId);

    record DurableState(
            long ticketItemId,
            int initial,
            int available,
            int reserved,
            int confirmed,
            long fenceVersion,
            String admissionState
    ) {

        public DurableState {
            if (ticketItemId <= 0 || initial < 0 || available < 0 || reserved < 0 || confirmed < 0
                    || available > initial || initial != available + reserved + confirmed
                    || fenceVersion < 0) {
                throw new IllegalArgumentException("invalid durable reservation fixture state");
            }
            Objects.requireNonNull(admissionState, "admissionState must not be null");
        }
    }

    record EvidenceState(
            long ticketItemId,
            int initial,
            int available,
            int reserved,
            int confirmed,
            long fenceVersion,
            String admissionState,
            long pendingJournal,
            long pendingOutbox,
            double oldestOutboxAgeSeconds,
            long duplicateReservations,
            long duplicateOrders
    ) {

        public EvidenceState {
            if (ticketItemId <= 0 || initial < 0 || reserved < 0 || confirmed < 0
                    || fenceVersion < 0 || pendingJournal < 0 || pendingOutbox < 0
                    || oldestOutboxAgeSeconds < 0 || duplicateReservations < 0 || duplicateOrders < 0) {
                throw new IllegalArgumentException("invalid reservation fixture evidence state");
            }
            Objects.requireNonNull(admissionState, "admissionState must not be null");
        }
    }
}
