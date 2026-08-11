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
public class ReleaseReservationService {

    private static final String RESERVATION_AGGREGATE_TYPE = "Reservation";
    private static final String RESERVATION_RELEASED_EVENT = "reservation.released";

    private final ReservationRepository reservations;
    private final InventoryRepository inventory;
    private final OperationJournalRepository journal;
    private final ReservationStockPort stock;
    private final OutboxService outbox;
    private final ExpireReservationService expiration;
    private final TransactionTemplate databaseTransaction;
    private FaultInjectionPort faults = new NoOpFaultInjection();
    private ReservationTelemetryPort telemetry = new NoOpReservationTelemetry();

    public ReleaseReservationService(
            ReservationRepository reservations,
            InventoryRepository inventory,
            OperationJournalRepository journal,
            ReservationStockPort stock,
            OutboxService outbox,
            ExpireReservationService expiration,
            PlatformTransactionManager transactionManager
    ) {
        this.reservations = Objects.requireNonNull(reservations, "reservations must not be null");
        this.inventory = Objects.requireNonNull(inventory, "inventory must not be null");
        this.journal = Objects.requireNonNull(journal, "journal must not be null");
        this.stock = Objects.requireNonNull(stock, "stock must not be null");
        this.outbox = Objects.requireNonNull(outbox, "outbox must not be null");
        this.expiration = Objects.requireNonNull(expiration, "expiration must not be null");
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

    @Observed(name = "flashsale.reservation.release")
    public ReservationLifecycleResult release(UUID reservationId) {
        Instant startedAt = Instant.now();
        try {
            ReservationLifecycleResult result = releaseInternal(reservationId);
            recordTelemetry(result.outcome().name(), result.outcome().name(), startedAt);
            return result;
        } catch (RuntimeException exception) {
            recordTelemetry("EXCEPTION", "UNHANDLED", startedAt);
            throw exception;
        }
    }

    private ReservationLifecycleResult releaseInternal(UUID reservationId) {
        Objects.requireNonNull(reservationId, "reservationId must not be null");
        ReleaseCommit commit = inTransaction(databaseTransaction, () -> releaseInDatabase(reservationId));
        if (commit.requiresFreshStateResolution()) {
            commit = inTransaction(databaseTransaction, () -> resolveAfterLostTransition(reservationId));
        }
        if (!commit.requiresMirror()) {
            return resolvePossibleExpiry(reservationId, commit.result());
        }

        ReleaseCommit mirrorCommit = commit;
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
                    "RELEASED",
                    null));
            if (markedCommitted) {
                return result(ReservationLifecycleResult.Outcome.RELEASED,
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
        telemetry.record("release", outcome, reason, Duration.between(startedAt, Instant.now()));
    }

    private ReleaseCommit releaseInDatabase(UUID reservationId) {
        Optional<Reservation> existing = reservations.findById(reservationId);
        if (existing.isEmpty()) {
            Optional<OperationJournalRepository.JournalEntry> createJournal = journal.findByReservationId(
                    reservationId).filter(entry -> entry.operationType()
                    == OperationJournalRepository.OperationType.CREATE);
            if (createJournal.filter(entry -> entry.state()
                    == OperationJournalRepository.JournalState.REPAIR_REQUIRED).isPresent()) {
                return ReleaseCommit.complete(result(
                        ReservationLifecycleResult.Outcome.REPAIR_REQUIRED,
                        null,
                        createJournal.orElseThrow().operationId()));
            }
            return ReleaseCommit.complete(result(ReservationLifecycleResult.Outcome.NOT_FOUND, null, null));
        }
        Reservation reservation = existing.orElseThrow();
        Optional<OperationJournalRepository.JournalEntry> reservationJournal = journal.findByReservationId(
                        reservation.id())
                .filter(entry -> entry.operationType() == OperationJournalRepository.OperationType.CREATE);
        if (reservationJournal.filter(entry -> entry.state()
                == OperationJournalRepository.JournalState.REPAIR_REQUIRED).isPresent()) {
            return ReleaseCommit.complete(pendingResult(reservation, reservationJournal.orElseThrow()));
        }
        if (reservation.status() == ReservationStatus.RESERVED
                && reservationJournal.filter(entry -> isUnconverged(entry.state())).isPresent()) {
            return ReleaseCommit.complete(result(
                    ReservationLifecycleResult.Outcome.PROCESSING,
                    reservation,
                    reservationJournal.orElseThrow().operationId()));
        }
        if (reservation.status() == ReservationStatus.RELEASED) {
            Optional<OperationJournalRepository.JournalEntry> pending = journal.findPendingTerminal(
                    reservation.id(),
                    OperationJournalRepository.OperationType.RELEASE);
            if (pending.isPresent()) {
                return existingTerminalCommit(reservation, pending.orElseThrow());
            }
            return ReleaseCommit.complete(result(ReservationLifecycleResult.Outcome.REPLAYED, reservation, null));
        }
        if (reservation.status() == ReservationStatus.EXPIRED) {
            return ReleaseCommit.complete(result(ReservationLifecycleResult.Outcome.LATE_CONFLICT, reservation, null));
        }
        if (reservation.status() != ReservationStatus.RESERVED) {
            return ReleaseCommit.complete(result(ReservationLifecycleResult.Outcome.CONFLICT, reservation, null));
        }

        OptionalLong fenceVersion = inventory.findFenceVersion(reservation.ticketItemId());
        if (fenceVersion.isEmpty()) {
            return ReleaseCommit.complete(result(ReservationLifecycleResult.Outcome.PROCESSING, reservation, null));
        }
        Optional<Reservation> released = reservations.transitionIfCurrent(
                reservationId,
                ReservationStatus.RESERVED,
                ReservationStatus.RELEASED,
                Instant.now(),
                fenceVersion.getAsLong());
        if (released.isEmpty()) {
            return ReleaseCommit.freshStateResolutionRequired();
        }

        Reservation terminalReservation = released.orElseThrow();
        OperationJournalRepository.JournalEntry createJournal = journal.findByReservationId(
                        terminalReservation.id())
                .filter(entry -> entry.operationType() == OperationJournalRepository.OperationType.CREATE)
                .filter(entry -> entry.state() == OperationJournalRepository.JournalState.COMMITTED)
                .orElseThrow(() -> new IllegalStateException(
                        "committed create journal is required before terminal release"));
        if (!journal.transition(
                createJournal.operationId(),
                OperationJournalRepository.JournalState.COMMITTED,
                OperationJournalRepository.JournalState.MIRROR_PENDING,
                "RELEASED",
                null)) {
            throw new IllegalStateException("create journal terminal transition was rejected");
        }
        outbox.record(
                RESERVATION_AGGREGATE_TYPE,
                terminalReservation.id().toString(),
                RESERVATION_RELEASED_EVENT,
                new TerminalReservationPayload(
                        terminalReservation.id(),
                        terminalReservation.ticketItemId(),
                        terminalReservation.quantity(),
                        createJournal.operationId()));
        return ReleaseCommit.pendingMirror(
                terminalReservation,
                fenceVersion.getAsLong(),
                createJournal.operationId());
    }

    private ReservationLifecycleResult resolvePossibleExpiry(UUID reservationId, ReservationLifecycleResult result) {
        boolean unresolvedReserved = result.outcome() == ReservationLifecycleResult.Outcome.PROCESSING
                && result.reservation().map(reservation -> reservation.status() == ReservationStatus.RESERVED)
                .orElse(false);
        boolean alreadyExpired = result.reservation()
                .map(reservation -> reservation.status() == ReservationStatus.EXPIRED)
                .orElse(false);
        if (!unresolvedReserved && !alreadyExpired) {
            return result;
        }
        ReservationLifecycleResult expiry = expiration.expire(reservationId);
        if (isPendingOrRepair(expiry.outcome())) {
            return expiry;
        }
        if (expiry.reservation().map(reservation -> reservation.status() == ReservationStatus.EXPIRED).orElse(false)) {
            return new ReservationLifecycleResult(
                    ReservationLifecycleResult.Outcome.LATE_CONFLICT,
                    expiry.reservation(),
                    Optional.empty(),
                    expiry.operationId());
        }
        return result;
    }

    private ReleaseCommit resolveAfterLostTransition(UUID reservationId) {
        Optional<Reservation> current = reservations.findById(reservationId);
        if (current.isEmpty()) {
            return ReleaseCommit.complete(result(ReservationLifecycleResult.Outcome.NOT_FOUND, null, null));
        }
        Reservation reservation = current.orElseThrow();
        if (reservation.status() == ReservationStatus.RELEASED) {
            Optional<OperationJournalRepository.JournalEntry> pending = journal.findPendingTerminal(
                    reservation.id(),
                    OperationJournalRepository.OperationType.RELEASE);
            if (pending.isPresent()) {
                return existingTerminalCommit(reservation, pending.orElseThrow());
            }
            return ReleaseCommit.complete(result(ReservationLifecycleResult.Outcome.REPLAYED, reservation, null));
        }
        if (reservation.status() == ReservationStatus.EXPIRED) {
            return ReleaseCommit.complete(result(ReservationLifecycleResult.Outcome.LATE_CONFLICT, reservation, null));
        }
        if (reservation.status() == ReservationStatus.RESERVED) {
            return ReleaseCommit.complete(result(ReservationLifecycleResult.Outcome.PROCESSING, reservation, null));
        }
        return ReleaseCommit.complete(result(ReservationLifecycleResult.Outcome.CONFLICT, reservation, null));
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

    private ReservationLifecycleResult resolveMirrorTransitionRace(ReleaseCommit commit) {
        Optional<OperationJournalRepository.JournalEntry> current = inTransaction(
                databaseTransaction,
                () -> journal.findByOperationId(commit.operationId()));
        if (current.isPresent()
                && current.orElseThrow().state() == OperationJournalRepository.JournalState.COMMITTED) {
            return result(
                    ReservationLifecycleResult.Outcome.RELEASED,
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

    private static ReleaseCommit existingTerminalCommit(
            Reservation reservation,
            OperationJournalRepository.JournalEntry journalEntry
    ) {
        if (journalEntry.state() == OperationJournalRepository.JournalState.REPAIR_REQUIRED) {
            return ReleaseCommit.complete(pendingResult(reservation, journalEntry));
        }
        return ReleaseCommit.pendingMirror(
                reservation,
                journalEntry.fenceVersion(),
                journalEntry.operationId());
    }

    private static boolean isPendingOrRepair(ReservationLifecycleResult.Outcome outcome) {
        return outcome == ReservationLifecycleResult.Outcome.MIRROR_PENDING
                || outcome == ReservationLifecycleResult.Outcome.REPAIR_REQUIRED;
    }

    private static boolean isUnconverged(OperationJournalRepository.JournalState state) {
        return state == OperationJournalRepository.JournalState.RECEIVED
                || state == OperationJournalRepository.JournalState.REDIS_APPLYING
                || state == OperationJournalRepository.JournalState.REDIS_APPLIED
                || state == OperationJournalRepository.JournalState.COMPENSATION_PENDING
                || state == OperationJournalRepository.JournalState.MIRROR_PENDING
                || state == OperationJournalRepository.JournalState.REPAIR_REQUIRED;
    }

    private static TransactionTemplate requiresNew(PlatformTransactionManager transactionManager) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template;
    }

    private static <T> T inTransaction(TransactionTemplate template, Supplier<T> action) {
        T result = template.execute(status -> action.get());
        return Objects.requireNonNull(result, "transaction callback returned null");
    }

    private record ReleaseCommit(
            ReservationLifecycleResult result,
            Reservation reservation,
            long fenceVersion,
            UUID operationId,
            boolean requiresMirror,
            boolean requiresFreshStateResolution) {

        private static ReleaseCommit complete(ReservationLifecycleResult result) {
            return new ReleaseCommit(result, null, 0L, null, false, false);
        }

        private static ReleaseCommit pendingMirror(Reservation reservation, long fenceVersion, UUID operationId) {
            return new ReleaseCommit(null, reservation, fenceVersion, operationId, true, false);
        }

        private static ReleaseCommit freshStateResolutionRequired() {
            return new ReleaseCommit(
                    ReleaseReservationService.result(ReservationLifecycleResult.Outcome.PROCESSING, null, null),
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
