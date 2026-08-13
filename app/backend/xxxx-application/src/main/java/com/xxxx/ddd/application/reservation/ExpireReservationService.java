package com.xxxx.ddd.application.reservation;

import com.xxxx.ddd.application.MQ.OutboxService;
import com.xxxx.ddd.application.reservation.port.InventoryRepository;
import com.xxxx.ddd.application.reservation.port.NoOpReservationTelemetry;
import com.xxxx.ddd.application.reservation.port.OperationJournalRepository;
import com.xxxx.ddd.application.reservation.port.FaultInjectionPort;
import com.xxxx.ddd.application.reservation.port.NoOpFaultInjection;
import com.xxxx.ddd.application.reservation.port.ReservationRepository;
import com.xxxx.ddd.application.reservation.port.ReservationStockPort;
import com.xxxx.ddd.application.reservation.port.ReservationTelemetryPort;
import com.xxxx.ddd.domain.reservation.Reservation;
import com.xxxx.ddd.domain.reservation.ReservationStatus;
import io.micrometer.observation.annotation.Observed;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.function.Supplier;

@Service
public class ExpireReservationService {

    private static final String RESERVATION_AGGREGATE_TYPE = "Reservation";
    private static final String RESERVATION_EXPIRED_EVENT = "reservation.expired";

    private final ReservationRepository reservations;
    private final InventoryRepository inventory;
    private final OperationJournalRepository journal;
    private final ReservationStockPort stock;
    private final OutboxService outbox;
    private final TransactionTemplate databaseTransaction;
    private FaultInjectionPort faults = new NoOpFaultInjection();
    private ReservationTelemetryPort telemetry = new NoOpReservationTelemetry();

    public ExpireReservationService(
            ReservationRepository reservations,
            InventoryRepository inventory,
            OperationJournalRepository journal,
            ReservationStockPort stock,
            OutboxService outbox,
            PlatformTransactionManager transactionManager
    ) {
        this.reservations = Objects.requireNonNull(reservations, "reservations must not be null");
        this.inventory = Objects.requireNonNull(inventory, "inventory must not be null");
        this.journal = Objects.requireNonNull(journal, "journal must not be null");
        this.stock = Objects.requireNonNull(stock, "stock must not be null");
        this.outbox = Objects.requireNonNull(outbox, "outbox must not be null");
        this.databaseTransaction = requiresNew(Objects.requireNonNull(transactionManager,
                "transactionManager must not be null"));
    }

    @Autowired(required = false)
    public void setFaultInjectionPort(FaultInjectionPort faults) {
        this.faults = Objects.requireNonNull(faults, "faults must not be null");
    }

    @Autowired(required = false)
    public void setReservationTelemetryPort(ReservationTelemetryPort telemetry) {
        this.telemetry = Objects.requireNonNull(telemetry, "telemetry must not be null");
    }

    @Observed(name = "flashsale.reservation.expire")
    public ReservationLifecycleResult expire(UUID reservationId) {
        Instant startedAt = Instant.now();
        try {
            ReservationLifecycleResult result = expireInternal(reservationId);
            recordTelemetry(result.outcome().name(), result.outcome().name(), startedAt);
            return result;
        } catch (RuntimeException exception) {
            recordTelemetry("EXCEPTION", "UNHANDLED", startedAt);
            throw exception;
        }
    }

    private ReservationLifecycleResult expireInternal(UUID reservationId) {
        Objects.requireNonNull(reservationId, "reservationId must not be null");
        ExpiryCommit commit = inTransaction(databaseTransaction, () -> expireInDatabase(reservationId));
        if (commit.requiresFreshStateResolution()) {
            commit = inTransaction(databaseTransaction, () -> resolveAfterLostTransition(reservationId));
        }
        if (!commit.requiresMirror()) {
            return commit.result();
        }

        ExpiryCommit mirrorCommit = commit;
        try {
            faults.hit(FaultInjectionPort.FaultPoint.REDIS_MIRROR_TIMEOUT, mirrorCommit.operationId());
            stock.mirrorTerminalOnce(
                    mirrorCommit.operationId(),
                    mirrorCommit.reservation().ticketItemId(),
                    mirrorCommit.reservation().quantity(),
                    mirrorCommit.fenceVersion());
            boolean markedCommitted = inTransaction(databaseTransaction, () -> journal.transition(
                    mirrorCommit.operationId(),
                    OperationJournalRepository.JournalState.MIRROR_PENDING,
                    OperationJournalRepository.JournalState.COMMITTED,
                    "EXPIRED",
                    null));
            if (markedCommitted) {
                return result(
                        ReservationLifecycleResult.Outcome.EXPIRED,
                        mirrorCommit.reservation(),
                        mirrorCommit.operationId());
            }
            return resolveMirrorTransitionRace(mirrorCommit);
        } catch (RuntimeException ignored) {
            // The durable terminal state and MIRROR_PENDING journal row are intentionally retained for recovery.
        }
        return result(ReservationLifecycleResult.Outcome.MIRROR_PENDING,
                mirrorCommit.reservation(),
                mirrorCommit.operationId());
    }

    private void recordTelemetry(String outcome, String reason, Instant startedAt) {
        telemetry.record("expire", outcome, reason, Duration.between(startedAt, Instant.now()));
    }

    private ExpiryCommit expireInDatabase(UUID reservationId) {
        Optional<Reservation> existing = reservations.findById(reservationId);
        if (existing.isEmpty()) {
            Optional<OperationJournalRepository.JournalEntry> createJournal = journal.findByReservationId(
                    reservationId).filter(entry -> entry.operationType()
                    == OperationJournalRepository.OperationType.CREATE);
            if (createJournal.filter(entry -> entry.state()
                    == OperationJournalRepository.JournalState.REPAIR_REQUIRED).isPresent()) {
                return ExpiryCommit.complete(result(
                        ReservationLifecycleResult.Outcome.REPAIR_REQUIRED,
                        null,
                        createJournal.orElseThrow().operationId()));
            }
            return ExpiryCommit.complete(result(ReservationLifecycleResult.Outcome.NOT_FOUND, null, null));
        }
        Reservation reservation = existing.orElseThrow();
        if (reservation.status() == ReservationStatus.RESERVED) {
            Optional<OperationJournalRepository.JournalEntry> reservationJournal = journal.findByReservationId(
                            reservation.id())
                    .filter(entry -> entry.operationType() == OperationJournalRepository.OperationType.CREATE);
            if (reservationJournal.filter(entry -> entry.state()
                    == OperationJournalRepository.JournalState.REPAIR_REQUIRED).isPresent()) {
                return ExpiryCommit.complete(pendingResult(reservation, reservationJournal.orElseThrow()));
            }
            if (reservationJournal.filter(entry -> isUnconverged(entry.state())).isPresent()) {
                return ExpiryCommit.complete(result(
                        ReservationLifecycleResult.Outcome.PROCESSING,
                        reservation,
                        reservationJournal.orElseThrow().operationId()));
            }
        }
        if (reservation.status() == ReservationStatus.EXPIRED) {
            Optional<OperationJournalRepository.JournalEntry> pending = journal.findPendingTerminal(
                    reservation.id(),
                    OperationJournalRepository.OperationType.EXPIRE);
            if (pending.isPresent()) {
                return existingTerminalCommit(reservation, pending.orElseThrow());
            }
            return ExpiryCommit.complete(result(ReservationLifecycleResult.Outcome.REPLAYED, reservation, null));
        }
        if (reservation.status() == ReservationStatus.RELEASED) {
            return pendingReleaseOr(
                    reservation,
                    ExpiryCommit.complete(result(ReservationLifecycleResult.Outcome.CONFLICT, reservation, null)));
        }
        if (reservation.status() != ReservationStatus.RESERVED) {
            return ExpiryCommit.complete(result(ReservationLifecycleResult.Outcome.CONFLICT, reservation, null));
        }

        OptionalLong fenceVersion = inventory.findFenceVersion(reservation.ticketItemId());
        if (fenceVersion.isEmpty()) {
            return ExpiryCommit.complete(result(ReservationLifecycleResult.Outcome.PROCESSING, reservation, null));
        }
        Optional<Reservation> expired = reservations.transitionIfCurrent(
                reservationId,
                ReservationStatus.RESERVED,
                ReservationStatus.EXPIRED,
                Instant.now(),
                fenceVersion.getAsLong());
        if (expired.isEmpty()) {
            return ExpiryCommit.freshStateResolutionRequired();
        }

        Reservation terminalReservation = expired.orElseThrow();
        OperationJournalRepository.JournalEntry createJournal = journal.findByReservationId(
                        terminalReservation.id())
                .filter(entry -> entry.operationType() == OperationJournalRepository.OperationType.CREATE)
                .filter(entry -> entry.state() == OperationJournalRepository.JournalState.COMMITTED)
                .orElseThrow(() -> new IllegalStateException(
                        "committed create journal is required before terminal expiry"));
        if (!journal.transition(
                createJournal.operationId(),
                OperationJournalRepository.JournalState.COMMITTED,
                OperationJournalRepository.JournalState.MIRROR_PENDING,
                "EXPIRED",
                null)) {
            throw new IllegalStateException("create journal terminal transition was rejected");
        }
        outbox.record(
                RESERVATION_AGGREGATE_TYPE,
                terminalReservation.id().toString(),
                RESERVATION_EXPIRED_EVENT,
                new TerminalReservationPayload(
                        terminalReservation.id(),
                        terminalReservation.ticketItemId(),
                        terminalReservation.quantity(),
                        createJournal.operationId()));
        return ExpiryCommit.pendingMirror(
                terminalReservation,
                fenceVersion.getAsLong(),
                createJournal.operationId());
    }

    private ExpiryCommit resolveAfterLostTransition(UUID reservationId) {
        Optional<Reservation> current = reservations.findById(reservationId);
        if (current.isEmpty()) {
            return ExpiryCommit.complete(result(ReservationLifecycleResult.Outcome.NOT_FOUND, null, null));
        }
        Reservation reservation = current.orElseThrow();
        if (reservation.status() == ReservationStatus.EXPIRED) {
            Optional<OperationJournalRepository.JournalEntry> pending = journal.findPendingTerminal(
                    reservation.id(),
                    OperationJournalRepository.OperationType.EXPIRE);
            if (pending.isPresent()) {
                return existingTerminalCommit(reservation, pending.orElseThrow());
            }
            return ExpiryCommit.complete(result(ReservationLifecycleResult.Outcome.REPLAYED, reservation, null));
        }
        if (reservation.status() == ReservationStatus.RELEASED) {
            return pendingReleaseOr(
                    reservation,
                    ExpiryCommit.complete(result(ReservationLifecycleResult.Outcome.CONFLICT, reservation, null)));
        }
        if (reservation.status() == ReservationStatus.RESERVED) {
            return ExpiryCommit.complete(result(ReservationLifecycleResult.Outcome.PROCESSING, reservation, null));
        }
        return ExpiryCommit.complete(result(ReservationLifecycleResult.Outcome.CONFLICT, reservation, null));
    }

    private ExpiryCommit pendingReleaseOr(
            Reservation reservation,
            ExpiryCommit fallback
    ) {
        return journal.findPendingTerminal(reservation.id(), OperationJournalRepository.OperationType.RELEASE)
                .map(pending -> ExpiryCommit.complete(pendingResult(reservation, pending)))
                .orElse(fallback);
    }

    private static ReservationLifecycleResult result(
            ReservationLifecycleResult.Outcome outcome,
            Reservation reservation,
            UUID operationId
    ) {
        return new ReservationLifecycleResult(
                outcome,
                Optional.ofNullable(reservation),
                Optional.empty(),
                Optional.ofNullable(operationId));
    }

    private static ReservationLifecycleResult pendingResult(
            Reservation reservation,
            OperationJournalRepository.JournalEntry journalEntry
    ) {
        ReservationLifecycleResult.Outcome outcome = journalEntry.state()
                == OperationJournalRepository.JournalState.REPAIR_REQUIRED
                ? ReservationLifecycleResult.Outcome.REPAIR_REQUIRED
                : ReservationLifecycleResult.Outcome.MIRROR_PENDING;
        return result(outcome, reservation, journalEntry.operationId());
    }

    private ReservationLifecycleResult resolveMirrorTransitionRace(ExpiryCommit commit) {
        Optional<OperationJournalRepository.JournalEntry> current = inTransaction(
                databaseTransaction,
                () -> journal.findByOperationId(commit.operationId()));
        if (current.isPresent()
                && current.orElseThrow().state() == OperationJournalRepository.JournalState.COMMITTED) {
            return result(
                    ReservationLifecycleResult.Outcome.EXPIRED,
                    commit.reservation(),
                    commit.operationId());
        }
        if (current.isPresent()
                && current.orElseThrow().state() == OperationJournalRepository.JournalState.REPAIR_REQUIRED) {
            return result(
                    ReservationLifecycleResult.Outcome.REPAIR_REQUIRED,
                    commit.reservation(),
                    commit.operationId());
        }
        return result(
                ReservationLifecycleResult.Outcome.MIRROR_PENDING,
                commit.reservation(),
                commit.operationId());
    }

    private static ExpiryCommit existingTerminalCommit(
            Reservation reservation,
            OperationJournalRepository.JournalEntry journalEntry
    ) {
        if (journalEntry.state() == OperationJournalRepository.JournalState.REPAIR_REQUIRED) {
            return ExpiryCommit.complete(pendingResult(reservation, journalEntry));
        }
        return ExpiryCommit.pendingMirror(
                reservation,
                journalEntry.fenceVersion(),
                journalEntry.operationId());
    }

    private static TransactionTemplate requiresNew(PlatformTransactionManager transactionManager) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template;
    }

    private static boolean isUnconverged(OperationJournalRepository.JournalState state) {
        return state == OperationJournalRepository.JournalState.RECEIVED
                || state == OperationJournalRepository.JournalState.REDIS_APPLYING
                || state == OperationJournalRepository.JournalState.REDIS_APPLIED
                || state == OperationJournalRepository.JournalState.COMPENSATION_PENDING
                || state == OperationJournalRepository.JournalState.MIRROR_PENDING
                || state == OperationJournalRepository.JournalState.REPAIR_REQUIRED;
    }

    private static <T> T inTransaction(TransactionTemplate template, Supplier<T> action) {
        T result = template.execute(status -> action.get());
        return Objects.requireNonNull(result, "transaction callback returned null");
    }

    private record ExpiryCommit(
            ReservationLifecycleResult result,
            Reservation reservation,
            long fenceVersion,
            UUID operationId,
            boolean requiresMirror,
            boolean requiresFreshStateResolution) {

        private static ExpiryCommit complete(ReservationLifecycleResult result) {
            return new ExpiryCommit(result, null, 0L, null, false, false);
        }

        private static ExpiryCommit pendingMirror(Reservation reservation, long fenceVersion, UUID operationId) {
            return new ExpiryCommit(null, reservation, fenceVersion, operationId, true, false);
        }

        private static ExpiryCommit freshStateResolutionRequired() {
            return new ExpiryCommit(
                    ExpireReservationService.result(ReservationLifecycleResult.Outcome.PROCESSING, null, null),
                    null,
                    0L,
                    null,
                    false,
                    true);
        }
    }

    private record TerminalReservationPayload(
            UUID reservationId,
            long ticketItemId,
            int quantity,
            UUID operationId) {
    }
}
