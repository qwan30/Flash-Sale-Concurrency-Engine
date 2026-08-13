package com.xxxx.ddd.application.reservation.port;

import java.util.Objects;

/**
 * Redis storage boundary for the local reservation workload fixture.
 */
public interface ReservationFixtureCache {

    void reset(long ticketItemId, int initial, int available, int reserved, int confirmed,
               long fenceVersion, String admissionState);

    CacheState read(long ticketItemId);

    EvidenceState readEvidence(long ticketItemId);

    record CacheState(
            long ticketItemId,
            int initial,
            int available,
            int reserved,
            int confirmed,
            long fenceVersion,
            String admissionState
    ) {

        public CacheState {
            if (ticketItemId <= 0 || initial < 0 || available < 0 || reserved < 0 || confirmed < 0
                    || available > initial || initial != available + reserved + confirmed
                    || fenceVersion < 0) {
                throw new IllegalArgumentException("invalid Redis reservation fixture state");
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
            String admissionState
    ) {

        public EvidenceState {
            if (ticketItemId <= 0 || initial < 0 || reserved < 0 || confirmed < 0 || fenceVersion < 0) {
                throw new IllegalArgumentException("invalid Redis reservation fixture evidence state");
            }
            Objects.requireNonNull(admissionState, "admissionState must not be null");
        }
    }
}
