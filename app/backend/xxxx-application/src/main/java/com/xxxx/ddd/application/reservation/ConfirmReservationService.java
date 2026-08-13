package com.xxxx.ddd.application.reservation;

import com.xxxx.ddd.application.MQ.OutboxService;
import com.xxxx.ddd.application.reservation.port.InventoryRepository;
import com.xxxx.ddd.application.reservation.port.NoOpReservationTelemetry;
import com.xxxx.ddd.application.reservation.port.OperationJournalRepository;
import com.xxxx.ddd.application.reservation.port.FaultInjectionPort;
import com.xxxx.ddd.application.reservation.port.NoOpFaultInjection;
import com.xxxx.ddd.application.reservation.port.ReservationOrderRepository;
import com.xxxx.ddd.application.reservation.port.ReservationRepository;
import com.xxxx.ddd.application.reservation.port.ReservationTelemetryPort;
import com.xxxx.ddd.domain.reservation.Reservation;
import com.xxxx.ddd.domain.reservation.ReservationOrder;
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
public class ConfirmReservationService {

    private static final String RESERVATION_AGGREGATE_TYPE = "Reservation";
    private static final String RESERVATION_CONFIRMED_EVENT = "reservation.confirmed";

    private final ReservationRepository reservations;
    private final InventoryRepository inventory;
    private final OperationJournalRepository journal;
    private final ReservationOrderRepository orders;
    private final OutboxService outbox;
    private final ExpireReservationService expiration;
    private final TransactionTemplate databaseTransaction;
    private FaultInjectionPort faults = new NoOpFaultInjection();
    private ReservationTelemetryPort telemetry = new NoOpReservationTelemetry();

    public ConfirmReservationService(
            ReservationRepository reservations,
            InventoryRepository inventory,
            OperationJournalRepository journal,
            ReservationOrderRepository orders,
            OutboxService outbox,
            ExpireReservationService expiration,
            PlatformTransactionManager transactionManager
    ) {
        this.reservations = Objects.requireNonNull(reservations, "reservations must not be null");
        this.inventory = Objects.requireNonNull(inventory, "inventory must not be null");
        this.journal = Objects.requireNonNull(journal, "journal must not be null");
        this.orders = Objects.requireNonNull(orders, "orders must not be null");
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

    @Observed(name = "flashsale.reservation.confirm")
    public ReservationLifecycleResult confirm(UUID reservationId) {
        Instant startedAt = Instant.now();
        try {
            ReservationLifecycleResult result = confirmInternal(reservationId);
            recordTelemetry(result.outcome().name(), result.outcome().name(), startedAt);
            return result;
        } catch (RuntimeException exception) {
            recordTelemetry("EXCEPTION", "UNHANDLED", startedAt);
            throw exception;
        }
    }

    private ReservationLifecycleResult confirmInternal(UUID reservationId) {
        Objects.requireNonNull(reservationId, "reservationId must not be null");
        ConfirmationAttempt attempt = inTransaction(databaseTransaction,
                () -> confirmInDatabase(reservationId));
        if (attempt.requiresFreshStateResolution()) {
            attempt = inTransaction(databaseTransaction, () -> resolveAfterLostTransition(reservationId));
        }
        if (!attempt.requiresExpiryDecision()) {
            return resolvePendingExpiry(reservationId, attempt.result());
        }

        faults.hit(FaultInjectionPort.FaultPoint.CONFIRM_EXPIRE_RACE, reservationId);
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
        return expiry;
    }

    private void recordTelemetry(String outcome, String reason, Instant startedAt) {
        telemetry.record("confirm", outcome, reason, Duration.between(startedAt, Instant.now()));
    }

    private ReservationLifecycleResult resolvePendingExpiry(
            UUID reservationId,
            ReservationLifecycleResult result
    ) {
        boolean expired = result.reservation()
                .map(reservation -> reservation.status() == ReservationStatus.EXPIRED)
                .orElse(false);
        if (!expired) {
            return result;
        }
        ReservationLifecycleResult retry = expiration.expire(reservationId);
        return isPendingOrRepair(retry.outcome())
                ? retry
                : result;
    }

    private ConfirmationAttempt confirmInDatabase(UUID reservationId) {
        Optional<Reservation> existing = reservations.findById(reservationId);
        if (existing.isEmpty()) {
            Optional<OperationJournalRepository.JournalEntry> createJournal = journal.findByReservationId(
                    reservationId).filter(entry -> entry.operationType()
                    == OperationJournalRepository.OperationType.CREATE);
            if (createJournal.filter(entry -> entry.state()
                    == OperationJournalRepository.JournalState.REPAIR_REQUIRED).isPresent()) {
                return ConfirmationAttempt.complete(new ReservationLifecycleResult(
                        ReservationLifecycleResult.Outcome.REPAIR_REQUIRED,
                        Optional.empty(),
                        Optional.empty(),
                        Optional.of(createJournal.orElseThrow().operationId())));
            }
            return ConfirmationAttempt.complete(result(ReservationLifecycleResult.Outcome.NOT_FOUND, null, null));
        }
        Reservation reservation = existing.orElseThrow();
        Optional<OperationJournalRepository.JournalEntry> createJournal = journal.findByReservationId(
                        reservation.id())
                .filter(entry -> entry.operationType() == OperationJournalRepository.OperationType.CREATE);
        if (createJournal.filter(entry -> entry.state()
                == OperationJournalRepository.JournalState.REPAIR_REQUIRED).isPresent()) {
            return ConfirmationAttempt.complete(journalResult(
                    reservation,
                    createJournal.orElseThrow(),
                    ReservationLifecycleResult.Outcome.REPAIR_REQUIRED));
        }
        if (reservation.status() == ReservationStatus.RESERVED
                && createJournal.filter(entry -> isUnconverged(entry.state())).isPresent()) {
            return ConfirmationAttempt.complete(journalResult(
                    reservation,
                    createJournal.orElseThrow(),
                    ReservationLifecycleResult.Outcome.PROCESSING));
        }
        if (reservation.status() == ReservationStatus.CONFIRMED) {
            ReservationOrder order = orders.findByReservationId(reservation.id())
                    .orElseThrow(() -> new IllegalStateException("confirmed reservation has no order"));
            return ConfirmationAttempt.complete(result(ReservationLifecycleResult.Outcome.REPLAYED, reservation, order));
        }
        if (reservation.status() == ReservationStatus.EXPIRED) {
            return ConfirmationAttempt.complete(result(ReservationLifecycleResult.Outcome.LATE_CONFLICT, reservation, null));
        }
        if (reservation.status() == ReservationStatus.RELEASED) {
            return pendingReleaseOr(
                    reservation,
                    ConfirmationAttempt.complete(result(ReservationLifecycleResult.Outcome.CONFLICT, reservation, null)));
        }
        if (reservation.status() != ReservationStatus.RESERVED) {
            return ConfirmationAttempt.complete(result(ReservationLifecycleResult.Outcome.CONFLICT, reservation, null));
        }

        OptionalLong fenceVersion = inventory.findFenceVersion(reservation.ticketItemId());
        if (fenceVersion.isEmpty()) {
            return ConfirmationAttempt.complete(result(ReservationLifecycleResult.Outcome.PROCESSING, reservation, null));
        }
        Optional<Reservation> confirmed = reservations.transitionIfCurrent(
                reservationId,
                ReservationStatus.RESERVED,
                ReservationStatus.CONFIRMED,
                Instant.now(),
                fenceVersion.getAsLong());
        if (confirmed.isEmpty()) {
            return ConfirmationAttempt.freshStateResolutionRequired();
        }

        Reservation terminalReservation = confirmed.orElseThrow();
        ReservationOrder order = orders.create(UUID.randomUUID(), terminalReservation);
        outbox.record(
                RESERVATION_AGGREGATE_TYPE,
                terminalReservation.id().toString(),
                RESERVATION_CONFIRMED_EVENT,
                new ConfirmationPayload(
                        order.id(),
                        terminalReservation.id(),
                        terminalReservation.ticketItemId(),
                        terminalReservation.quantity(),
                        order.confirmedAt()));
        return ConfirmationAttempt.complete(result(ReservationLifecycleResult.Outcome.CONFIRMED, terminalReservation, order));
    }

    private ConfirmationAttempt resolveAfterLostTransition(UUID reservationId) {
        Optional<Reservation> current = reservations.findById(reservationId);
        if (current.isEmpty()) {
            return ConfirmationAttempt.complete(result(ReservationLifecycleResult.Outcome.NOT_FOUND, null, null));
        }
        Reservation reservation = current.orElseThrow();
        if (reservation.status() == ReservationStatus.CONFIRMED) {
            ReservationOrder order = orders.findByReservationId(reservation.id())
                    .orElseThrow(() -> new IllegalStateException("confirmed reservation has no order"));
            return ConfirmationAttempt.complete(result(ReservationLifecycleResult.Outcome.REPLAYED, reservation, order));
        }
        if (reservation.status() == ReservationStatus.EXPIRED) {
            return ConfirmationAttempt.complete(result(ReservationLifecycleResult.Outcome.LATE_CONFLICT, reservation, null));
        }
        if (reservation.status() == ReservationStatus.RELEASED) {
            return pendingReleaseOr(
                    reservation,
                    ConfirmationAttempt.complete(result(ReservationLifecycleResult.Outcome.CONFLICT, reservation, null)));
        }
        if (reservation.status() == ReservationStatus.RESERVED) {
            return ConfirmationAttempt.requiresExpiryDecision(result(
                    ReservationLifecycleResult.Outcome.PROCESSING,
                    reservation,
                    null));
        }
        return ConfirmationAttempt.complete(result(ReservationLifecycleResult.Outcome.CONFLICT, reservation, null));
    }

    private ConfirmationAttempt pendingReleaseOr(
            Reservation reservation,
            ConfirmationAttempt fallback
    ) {
        return journal.findPendingTerminal(reservation.id(), OperationJournalRepository.OperationType.RELEASE)
                .map(pending -> ConfirmationAttempt.complete(new ReservationLifecycleResult(
                        pending.state() == OperationJournalRepository.JournalState.REPAIR_REQUIRED
                                ? ReservationLifecycleResult.Outcome.REPAIR_REQUIRED
                                : ReservationLifecycleResult.Outcome.MIRROR_PENDING,
                        Optional.of(reservation),
                        Optional.empty(),
                        Optional.of(pending.operationId()))))
                .orElse(fallback);
    }

    private static boolean isPendingOrRepair(ReservationLifecycleResult.Outcome outcome) {
        return outcome == ReservationLifecycleResult.Outcome.MIRROR_PENDING
                || outcome == ReservationLifecycleResult.Outcome.REPAIR_REQUIRED;
    }

    private static boolean isUnconverged(OperationJournalRepository.JournalState state) {
        return state == OperationJournalRepository.JournalState.RECEIVED
                || state == OperationJournalRepository.JournalState.REDIS_APPLIED
                || state == OperationJournalRepository.JournalState.COMPENSATION_PENDING
                || state == OperationJournalRepository.JournalState.MIRROR_PENDING
                || state == OperationJournalRepository.JournalState.REPAIR_REQUIRED;
    }

    private static ReservationLifecycleResult journalResult(
            Reservation reservation,
            OperationJournalRepository.JournalEntry entry,
            ReservationLifecycleResult.Outcome outcome
    ) {
        return new ReservationLifecycleResult(
                outcome,
                Optional.of(reservation),
                Optional.empty(),
                Optional.of(entry.operationId()));
    }

    private static ReservationLifecycleResult result(
            ReservationLifecycleResult.Outcome outcome,
            Reservation reservation,
            ReservationOrder order
    ) {
        return new ReservationLifecycleResult(
                outcome,
                Optional.ofNullable(reservation),
                Optional.ofNullable(order),
                Optional.empty());
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

    private record ConfirmationAttempt(
            ReservationLifecycleResult result,
            boolean requiresExpiryDecision,
            boolean requiresFreshStateResolution
    ) {

        private static ConfirmationAttempt complete(ReservationLifecycleResult result) {
            return new ConfirmationAttempt(result, false, false);
        }

        private static ConfirmationAttempt requiresExpiryDecision(ReservationLifecycleResult result) {
            return new ConfirmationAttempt(result, true, false);
        }

        private static ConfirmationAttempt freshStateResolutionRequired() {
            return new ConfirmationAttempt(
                    ConfirmReservationService.result(ReservationLifecycleResult.Outcome.PROCESSING, null, null),
                    false,
                    true);
        }
    }

    private record ConfirmationPayload(
            UUID orderId,
            UUID reservationId,
            long ticketItemId,
            int quantity,
            Instant confirmedAt) {
    }
}
