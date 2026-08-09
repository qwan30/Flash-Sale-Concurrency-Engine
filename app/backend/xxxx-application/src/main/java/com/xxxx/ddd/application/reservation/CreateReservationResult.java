package com.xxxx.ddd.application.reservation;

import com.xxxx.ddd.application.reservation.port.OperationJournalRepository;
import com.xxxx.ddd.domain.reservation.InventorySnapshot;
import com.xxxx.ddd.domain.reservation.Reservation;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

public record CreateReservationResult(
        Outcome outcome,
        UUID operationId,
        UUID reservationId,
        Optional<Reservation> reservation,
        Optional<InventorySnapshot> stockSnapshot,
        Optional<OperationJournalRepository.JournalState> journalState,
        String resultCode,
        OptionalInt stockAfter) {

    public CreateReservationResult {
        Objects.requireNonNull(outcome, "outcome must not be null");
        Objects.requireNonNull(operationId, "operationId must not be null");
        Objects.requireNonNull(reservationId, "reservationId must not be null");
        Objects.requireNonNull(reservation, "reservation must not be null");
        Objects.requireNonNull(stockSnapshot, "stockSnapshot must not be null");
        Objects.requireNonNull(journalState, "journalState must not be null");
        Objects.requireNonNull(stockAfter, "stockAfter must not be null");
        if (resultCode == null || resultCode.isBlank()) {
            throw new IllegalArgumentException("resultCode is required");
        }
    }

    public enum Outcome {
        NEW,
        REPLAYED,
        PROCESSING,
        SOLD_OUT,
        FENCE_STALE,
        REJECTED,
        CONFLICT
    }
}
