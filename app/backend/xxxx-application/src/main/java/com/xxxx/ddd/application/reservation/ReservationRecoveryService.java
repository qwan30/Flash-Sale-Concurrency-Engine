package com.xxxx.ddd.application.reservation;

import com.xxxx.ddd.application.MQ.OutboxService;
import com.xxxx.ddd.application.reservation.port.InventoryRepository;
import com.xxxx.ddd.application.reservation.port.NoOpReservationTelemetry;
import com.xxxx.ddd.application.reservation.port.OperationJournalRepository;
import com.xxxx.ddd.application.reservation.port.ReservationRepository;
import com.xxxx.ddd.application.reservation.port.ReservationRepairRepository;
import com.xxxx.ddd.application.reservation.port.ReservationStockPort;
import com.xxxx.ddd.application.reservation.port.ReservationTelemetryPort;
import com.xxxx.ddd.domain.reservation.Reservation;
import com.xxxx.ddd.domain.reservation.ReservationStatus;
import io.micrometer.observation.annotation.Observed;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Replays journaled Redis operations after a worker or dependency failure.
 *
 * <p>Recovery is deliberately fail-closed: a changed fence or an exhausted retry budget
 * moves the operation to {@code REPAIR_REQUIRED}; the old operation token is never retried
 * against a new inventory epoch.
 */
@Service
@Slf4j
public class ReservationRecoveryService {

    public static final int MAX_ATTEMPTS = 5;
    public static final int BATCH_SIZE = 50;
    public static final Duration LEASE = Duration.ofSeconds(30);
    public static final List<Duration> RETRY_DELAYS = List.of(
            Duration.ofSeconds(1),
            Duration.ofSeconds(2),
            Duration.ofSeconds(4),
            Duration.ofSeconds(8),
            Duration.ofSeconds(16));

    private final OperationJournalRepository journal;
    private final InventoryRepository inventory;
    private final ReservationRepository reservations;
    private final ReservationStockPort stock;
    private final OutboxService outbox;
    private final ReservationRepairRepository repairs;
    private final TransactionTemplate transaction;
    private ReservationTelemetryPort telemetry = new NoOpReservationTelemetry();

    public ReservationRecoveryService(
            OperationJournalRepository journal,
            InventoryRepository inventory,
            ReservationRepository reservations,
            ReservationStockPort stock,
            OutboxService outbox,
            PlatformTransactionManager transactionManager,
            ReservationRepairRepository repairs
    ) {
        this.journal = Objects.requireNonNull(journal, "journal must not be null");
        this.inventory = Objects.requireNonNull(inventory, "inventory must not be null");
        this.reservations = Objects.requireNonNull(reservations, "reservations must not be null");
        this.stock = Objects.requireNonNull(stock, "stock must not be null");
        this.outbox = Objects.requireNonNull(outbox, "outbox must not be null");
        this.repairs = Objects.requireNonNull(repairs, "repairs must not be null");
        this.transaction = requiresNew(Objects.requireNonNull(transactionManager,
                "transactionManager must not be null"));
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setReservationTelemetryPort(ReservationTelemetryPort telemetry) {
        this.telemetry = Objects.requireNonNull(telemetry, "telemetry must not be null");
    }

    @Observed(name = "flashsale.reservation.recover")
    public void recover(OperationJournalRepository.JournalEntry entry) {
        Objects.requireNonNull(entry, "entry must not be null");
        Instant startedAt = Instant.now();
        try {
            switch (entry.state()) {
                case COMPENSATION_PENDING -> recoverCompensation(entry);
                case MIRROR_PENDING -> recoverMirror(entry);
                case RECEIVED, REDIS_APPLYING, REDIS_APPLIED -> recoverCreate(entry);
                case REPAIR_REQUIRED -> recoverRepair(entry);
                default -> log.debug(
                        "RESERVATION_RECOVERY: operation={} state={} is not recoverable",
                        entry.operationId(), entry.state());
            }
            String disposition = journal.findByOperationId(entry.operationId())
                    .map(current -> current.state().name())
                    .orElse(entry.state().name());
            telemetry.record("recover", disposition, disposition, Duration.between(startedAt, Instant.now()));
        } catch (RuntimeException exception) {
            telemetry.record("recover", "EXCEPTION", "UNHANDLED", Duration.between(startedAt, Instant.now()));
            log.error("RESERVATION_RECOVERY: operation={} failed unexpectedly",
                    entry.operationId(), exception);
            retryOrRepair(entry, "UNHANDLED_RECOVERY_FAILURE");
        }
    }

    private void recoverCreate(OperationJournalRepository.JournalEntry entry) {
        if (entry.operationType() != OperationJournalRepository.OperationType.CREATE
                || entry.demoActorId() == null
                || entry.idempotencyKeyHash() == null) {
            repairRequired(entry, "INVALID_CREATE_JOURNAL");
            return;
        }

        boolean durableReservationExists = reservations.findById(entry.reservationId()).isPresent();
        Optional<ReservationStockPort.RedisOperationState> operationState =
                stock.operationState(entry.operationId());
        if (operationState.isPresent() && !matchesOperationIdentity(entry, operationState.orElseThrow())) {
            repairRequired(entry, "REDIS_OPERATION_STATE_INVALID");
            return;
        }
        if (operationState.map(state -> state.status()
                == ReservationStockPort.RedisOperationState.Status.SOLD_OUT).orElse(false)) {
            Integer stockAfter = operationState.orElseThrow().stockAfter();
            if (stockAfter == null) {
                repairRequired(entry, "REDIS_OPERATION_STATE_INVALID");
                return;
            }
            if (durableReservationExists) {
                repairRequired(entry, "REDIS_SOLD_OUT_WITH_DURABLE_RESERVATION");
                return;
            }
            if (entry.state() == OperationJournalRepository.JournalState.RECEIVED
                    || entry.state() == OperationJournalRepository.JournalState.REDIS_APPLYING) {
                transition(entry, entry.state(), OperationJournalRepository.JournalState.REJECTED,
                        "SOLD_OUT", stockAfter);
            } else {
                repairRequired(entry, "REDIS_OPERATION_STATE_INVALID");
            }
            return;
        }

        OptionalLong currentFence = inventory.findFenceVersion(entry.ticketItemId());
        if (currentFence.isEmpty()) {
            if (durableReservationExists
                    || (entry.state() == OperationJournalRepository.JournalState.RECEIVED
                    && operationState.map(state -> state.status()
                    == ReservationStockPort.RedisOperationState.Status.APPLIED).orElse(false))) {
                repairRequired(entry, "TICKET_ITEM_NOT_FOUND");
            } else if (entry.state() == OperationJournalRepository.JournalState.RECEIVED) {
                transition(entry, OperationJournalRepository.JournalState.REJECTED,
                        "TICKET_ITEM_NOT_FOUND", null);
            } else {
                repairRequired(entry, "TICKET_ITEM_NOT_FOUND");
            }
            return;
        }
        if (currentFence.getAsLong() != entry.fenceVersion()) {
            if (durableReservationExists) {
                repairRequired(entry, "FENCE_STALE");
            } else if (entry.state() == OperationJournalRepository.JournalState.RECEIVED
                    && !operationState.map(state -> state.status()
                    == ReservationStockPort.RedisOperationState.Status.APPLIED).orElse(false)) {
                transition(entry, OperationJournalRepository.JournalState.REJECTED,
                        "FENCE_STALE", null);
            } else {
                repairRequired(entry, "FENCE_STALE");
            }
            return;
        }

        ReservationStockPort.RedisApplyResult applied;
        OperationJournalRepository.JournalState redisApplyState = entry.state();
        if (operationState.isEmpty()) {
            if (entry.state() != OperationJournalRepository.JournalState.RECEIVED) {
                repairRequired(entry, "REDIS_OPERATION_STATE_MISSING");
                return;
            }
            if (!transition(
                    entry,
                    OperationJournalRepository.JournalState.RECEIVED,
                    OperationJournalRepository.JournalState.REDIS_APPLYING,
                    "REDIS_APPLYING",
                    null)) {
                return;
            }
            redisApplyState = OperationJournalRepository.JournalState.REDIS_APPLYING;
            try {
                applied = stock.applyOnce(
                        entry.operationId(),
                        entry.ticketItemId(),
                        entry.quantity(),
                        entry.fenceVersion());
            } catch (RuntimeException retryFailure) {
                repairRequired(entry, redisApplyState, "REDIS_APPLY_RETRY_FAILED");
                return;
            }
            if (applied == null) {
                repairRequired(entry, redisApplyState, "REDIS_OPERATION_STATE_INVALID");
                return;
            }
        } else if (operationState.get().status() == ReservationStockPort.RedisOperationState.Status.SOLD_OUT) {
            Integer stockAfter = operationState.get().stockAfter();
            if (stockAfter == null) {
                repairRequired(entry, "REDIS_OPERATION_STATE_INVALID");
                return;
            }
            applied = ReservationStockPort.RedisApplyResult.soldOut(stockAfter);
        } else if (operationState.get().status() == ReservationStockPort.RedisOperationState.Status.APPLIED) {
            Integer stockAfter = operationState.get().stockAfter();
            if (stockAfter == null) {
                repairRequired(entry, "REDIS_OPERATION_STATE_INVALID");
                return;
            }
            applied = ReservationStockPort.RedisApplyResult.replayed(stockAfter);
        } else {
            repairRequired(entry, "REDIS_OPERATION_STATE_INVALID");
            return;
        }

        switch (applied.status()) {
            case SOLD_OUT -> {
                if (durableReservationExists) {
                    repairRequired(entry, redisApplyState, "REDIS_SOLD_OUT_WITH_DURABLE_RESERVATION");
                } else {
                    transition(entry, redisApplyState, OperationJournalRepository.JournalState.REJECTED,
                            "SOLD_OUT", applied.stockAfter());
                }
                return;
            }
            case STALE_FENCE -> {
                if (durableReservationExists) {
                    repairRequired(entry, redisApplyState, "FENCE_STALE");
                } else if (redisApplyState == OperationJournalRepository.JournalState.RECEIVED
                        || redisApplyState == OperationJournalRepository.JournalState.REDIS_APPLYING) {
                    transition(entry, redisApplyState, OperationJournalRepository.JournalState.REJECTED,
                            "FENCE_STALE", null);
                } else {
                    repairRequired(entry, "FENCE_STALE");
                }
                return;
            }
            case CONFLICT -> {
                repairRequired(entry, redisApplyState, "REDIS_OPERATION_CONFLICT");
                return;
            }
            case APPLIED, REPLAYED -> {
                // Continue to the durable commit below.
            }
        }

        if (entry.state() == OperationJournalRepository.JournalState.RECEIVED
                || entry.state() == OperationJournalRepository.JournalState.REDIS_APPLYING) {
            if (!transition(entry,
                    redisApplyState,
                    OperationJournalRepository.JournalState.REDIS_APPLIED,
                    "REDIS_APPLIED",
                    applied.stockAfter())) {
                return;
            }
        }

        try {
            inTransaction(transaction, () -> {
                Optional<Reservation> existing = reservations.findById(entry.reservationId());
                if (existing.isEmpty()) {
                    if (!inventory.decrementIfAvailable(
                            entry.ticketItemId(), entry.quantity(), entry.fenceVersion())) {
                        throw new IllegalStateException("durable inventory admission was rejected during recovery");
                    }
                    Reservation recovered = new Reservation(
                            entry.reservationId(),
                            entry.ticketItemId(),
                            entry.demoActorId(),
                            entry.quantity(),
                            ReservationStatus.RESERVED,
                            Instant.now().plus(CreateReservationService.RESERVATION_TTL),
                            null);
                    if (!reservations.insertReserved(
                            recovered,
                            entry.fenceVersion(),
                            entry.idempotencyKeyHash(),
                            entry.requestFingerprint())) {
                        throw new IllegalStateException("durable reservation insert was rejected during recovery");
                    }
                    outbox.record(
                            CreateReservationService.RESERVATION_AGGREGATE_TYPE,
                            entry.reservationId().toString(),
                            CreateReservationService.RESERVATION_CREATED_EVENT,
                            new CreateReservationService.ReservationCreatedPayload(
                                    recovered.id(),
                                    recovered.ticketItemId(),
                                    recovered.demoActorId(),
                                    recovered.quantity(),
                                    recovered.expiresAt(),
                                    applied.stockAfter()));
                }
                if (!journal.transition(
                        entry.operationId(),
                        OperationJournalRepository.JournalState.REDIS_APPLIED,
                        OperationJournalRepository.JournalState.COMMITTED,
                        "RECOVERED",
                        applied.stockAfter())) {
                    throw new IllegalStateException("recovery journal commit was rejected");
                }
                return Boolean.TRUE;
            });
        } catch (RuntimeException databaseFailure) {
            Optional<OperationJournalRepository.JournalEntry> current = journal.findByOperationId(
                    entry.operationId());
            if (current.isPresent()
                    && current.orElseThrow().state() == OperationJournalRepository.JournalState.REDIS_APPLIED) {
                compensateCreate(current.orElseThrow(), OperationJournalRepository.JournalState.REDIS_APPLIED);
            } else {
                log.warn("RESERVATION_RECOVERY: operation={} commit race/state is no longer compensatable",
                        entry.operationId());
            }
        }
    }

    private void compensateCreate(
            OperationJournalRepository.JournalEntry entry,
            OperationJournalRepository.JournalState expectedState
    ) {
        Optional<ReservationStockPort.RedisOperationState> operationState =
                stock.operationState(entry.operationId());
        if (operationState.isEmpty()) {
            repairRequired(entry, expectedState, "REDIS_OPERATION_STATE_MISSING");
            return;
        }
        if (!matchesOperationIdentity(entry, operationState.orElseThrow())) {
            repairRequired(entry, expectedState, "REDIS_OPERATION_STATE_INVALID");
            return;
        }
        if (operationState.get().status() != ReservationStockPort.RedisOperationState.Status.APPLIED
                && operationState.get().status() != ReservationStockPort.RedisOperationState.Status.COMPENSATED) {
            repairRequired(entry, expectedState, "REDIS_OPERATION_STATE_INVALID");
            return;
        }
        try {
            ReservationStockPort.RedisCompensationResult compensation = stock.compensateOnce(
                    entry.operationId(),
                    entry.ticketItemId(),
                    entry.quantity(),
                    entry.fenceVersion());
            switch (compensation.status()) {
                case COMPENSATED, REPLAYED -> transition(
                        entry,
                        expectedState,
                        OperationJournalRepository.JournalState.COMPENSATED,
                        "DATABASE_FAILURE",
                        compensation.stockAfter());
                case NOT_APPLIED -> transition(
                        entry,
                        expectedState,
                        OperationJournalRepository.JournalState.COMPENSATED,
                        "COMPENSATION_NOT_APPLIED",
                        null);
                case STALE_FENCE -> repairRequired(
                        entry,
                        expectedState,
                        "COMPENSATION_STALE_FENCE");
                case CONFLICT -> repairRequired(
                        entry,
                        expectedState,
                        "COMPENSATION_CONFLICT");
            }
        } catch (RuntimeException exception) {
            transition(entry,
                    expectedState,
                    OperationJournalRepository.JournalState.COMPENSATION_PENDING,
                    "COMPENSATION_PENDING",
                    null);
        }
    }

    private void recoverCompensation(OperationJournalRepository.JournalEntry entry) {
        if (!hasCurrentFence(entry)) {
            repairRequired(entry, "FENCE_STALE");
            return;
        }

        Optional<ReservationStockPort.RedisOperationState> operationState =
                stock.operationState(entry.operationId());
        if (operationState.isEmpty()) {
            repairRequired(entry, "REDIS_OPERATION_STATE_MISSING");
            return;
        }
        if (!matchesOperationIdentity(entry, operationState.orElseThrow())) {
            repairRequired(entry, "REDIS_OPERATION_STATE_INVALID");
            return;
        }
        if (operationState.get().status() != ReservationStockPort.RedisOperationState.Status.APPLIED
                && operationState.get().status() != ReservationStockPort.RedisOperationState.Status.COMPENSATED) {
            repairRequired(entry, "REDIS_OPERATION_STATE_INVALID");
            return;
        }

        try {
            ReservationStockPort.RedisCompensationResult result = stock.compensateOnce(
                    entry.operationId(),
                    entry.ticketItemId(),
                    entry.quantity(),
                    entry.fenceVersion());
            switch (result.status()) {
                case COMPENSATED, REPLAYED -> transition(
                        entry,
                        OperationJournalRepository.JournalState.COMPENSATED,
                        "COMPENSATED",
                        result.stockAfter());
                case NOT_APPLIED -> transition(
                        entry,
                        OperationJournalRepository.JournalState.COMPENSATED,
                        "COMPENSATION_NOT_APPLIED",
                        null);
                case STALE_FENCE, CONFLICT -> repairRequired(entry, result.status().name());
            }
        } catch (RuntimeException exception) {
            retryOrRepair(entry, "COMPENSATION_RETRY_FAILED");
        }
    }

    private void recoverMirror(OperationJournalRepository.JournalEntry entry) {
        Optional<ReservationStockPort.RedisOperationState> operationState =
                stock.operationState(entry.operationId());
        if (operationState.isEmpty()) {
            repairRequired(entry, "REDIS_OPERATION_STATE_MISSING");
            return;
        }
        if (!matchesOperationIdentity(entry, operationState.orElseThrow())) {
            repairRequired(entry, "REDIS_OPERATION_STATE_INVALID");
            return;
        }
        ReservationStockPort.RedisOperationState.Status status = operationState.orElseThrow().status();
        if (status != ReservationStockPort.RedisOperationState.Status.APPLIED
                && status != ReservationStockPort.RedisOperationState.Status.MIRRORED) {
            repairRequired(entry, "REDIS_OPERATION_STATE_INVALID");
            return;
        }
        if (status == ReservationStockPort.RedisOperationState.Status.APPLIED
                && !hasCurrentFence(entry)) {
            repairRequired(entry, "FENCE_STALE");
            return;
        }

        try {
            stock.mirrorTerminalOnce(
                    entry.operationId(),
                    entry.ticketItemId(),
                    entry.quantity(),
                    entry.fenceVersion());
            transition(
                    entry,
                    OperationJournalRepository.JournalState.COMMITTED,
                terminalResultCode(entry),
                null);
        } catch (RuntimeException exception) {
            Optional<ReservationStockPort.RedisOperationState> state = stock.operationState(entry.operationId());
            if (state.isEmpty()) {
                repairRequired(entry, "REDIS_OPERATION_STATE_MISSING");
            } else if (state.map(value -> value.status()
                    == ReservationStockPort.RedisOperationState.Status.STALE_FENCE).orElse(false)
                    || !hasCurrentFence(entry)) {
                repairRequired(entry, "FENCE_STALE");
            } else if (state.map(value -> value.status()
                    != ReservationStockPort.RedisOperationState.Status.APPLIED
                    && value.status() != ReservationStockPort.RedisOperationState.Status.MIRRORED).orElse(true)) {
                repairRequired(entry, "REDIS_OPERATION_STATE_INVALID");
            } else {
                retryOrRepair(entry, "MIRROR_RETRY_FAILED");
            }
        }
    }

    private boolean hasCurrentFence(OperationJournalRepository.JournalEntry entry) {
        OptionalLong currentFence = inventory.findFenceVersion(entry.ticketItemId());
        return currentFence.isPresent() && currentFence.getAsLong() == entry.fenceVersion();
    }

    private static boolean matchesOperationIdentity(
            OperationJournalRepository.JournalEntry entry,
            ReservationStockPort.RedisOperationState state
    ) {
        return state.ticketItemId() != null
                && state.ticketItemId() == entry.ticketItemId()
                && state.quantity() != null
                && state.quantity() == entry.quantity()
                && state.fenceVersion() != null
                && state.fenceVersion() == entry.fenceVersion();
    }

    private void retryOrRepair(OperationJournalRepository.JournalEntry entry, String errorCode) {
        int attempts = journal.attempts(entry.operationId());
        if (attempts >= MAX_ATTEMPTS) {
            recordRepairBudgetAlert();
            if (entry.state() == OperationJournalRepository.JournalState.REPAIR_REQUIRED) {
                log.error("RESERVATION_RECOVERY: operation={} repair retry budget exhausted; manual repair remains required",
                        entry.operationId());
                return;
            }
            repairRequired(entry, "MAX_ATTEMPTS_EXCEEDED");
            return;
        }
        Duration delay = RETRY_DELAYS.get(Math.min(Math.max(attempts - 1, 0), RETRY_DELAYS.size() - 1));
        boolean scheduled = inTransaction(transaction, () -> journal.scheduleRetry(
                entry.operationId(), entry.state(), errorCode, Instant.now().plus(delay)));
        if (!scheduled) {
            log.debug("RESERVATION_RECOVERY: operation={} changed state before retry scheduling",
                    entry.operationId());
        }
    }

    private void scheduleRetry(OperationJournalRepository.JournalEntry entry, String errorCode, String message) {
        log.debug("RESERVATION_RECOVERY: operation={} deferred: {}", entry.operationId(), message);
        retryOrRepair(entry, errorCode);
    }

    private void repairRequired(OperationJournalRepository.JournalEntry entry, String reason) {
        repairRequired(entry, entry.state(), reason);
    }

    private void repairRequired(
            OperationJournalRepository.JournalEntry entry,
            OperationJournalRepository.JournalState expectedState,
            String reason
    ) {
        boolean transitioned = transition(
                entry,
                expectedState,
                OperationJournalRepository.JournalState.REPAIR_REQUIRED,
                reason,
                null);
        if (transitioned) {
            startRepair(entry, reason);
            log.error("RESERVATION_RECOVERY: operation={} entered REPAIR_REQUIRED reason={}",
                    entry.operationId(), reason);
        }
    }

    private void startRepair(OperationJournalRepository.JournalEntry entry, String reason) {
        Optional<UUID> existingRepairId = journal.repairId(entry.operationId());
        if (existingRepairId != null && existingRepairId.isPresent()) {
            return;
        }
        UUID repairId = stableRepairId(entry);
        Optional<ReservationRepairRepository.RepairContext> started = repairs.start(
                repairId, entry.ticketItemId(), reason);
        if (started == null || started.isEmpty()) {
            return;
        }
        ReservationRepairRepository.RepairContext context = started.orElseThrow();
        if (context.state() == ReservationRepairRepository.RepairState.STARTED) {
            requirePublished(stock.publishFence(
                    context.ticketItemId(), context.newFenceVersion(), "DRAINING"));
        }
        journal.attachRepairId(
                entry.operationId(),
                OperationJournalRepository.JournalState.REPAIR_REQUIRED,
                repairId,
                reason);
    }

    private static UUID stableRepairId(OperationJournalRepository.JournalEntry entry) {
        return UUID.nameUUIDFromBytes(("flashsale-reservation-repair:" + entry.operationId())
                .getBytes(StandardCharsets.UTF_8));
    }

    private void recoverRepair(OperationJournalRepository.JournalEntry entry) {
        int attempts = journal.attempts(entry.operationId());
        if (attempts > MAX_ATTEMPTS) {
            recordRepairBudgetAlert();
            log.error("RESERVATION_RECOVERY: operation={} repair retry budget exhausted; manual repair remains required",
                    entry.operationId());
            return;
        }

        Optional<UUID> repairId = journal.repairId(entry.operationId());
        if (repairId == null || repairId.isEmpty()) {
            startRepair(entry, entry.resultCode() == null ? "REPAIR_REQUIRED" : entry.resultCode());
            Optional<UUID> attachedRepairId = journal.repairId(entry.operationId());
            if (attachedRepairId == null || attachedRepairId.isEmpty()) {
                retryOrRepair(entry, "REPAIR_CONTEXT_NOT_FOUND");
            }
            return;
        }

        Optional<ReservationRepairRepository.RepairContext> context = repairs.find(repairId.get());
        if (context == null || context.isEmpty()) {
            retryOrRepair(entry, "REPAIR_CONTEXT_NOT_FOUND");
            return;
        }

        try {
            ReservationRepairRepository.RepairContext current = context.get();
            if (current.state() == ReservationRepairRepository.RepairState.FAILED) {
                if (!repairs.restart(current.repairId(), current.disposition())) {
                    current = repairs.find(current.repairId()).orElseThrow(
                            () -> new IllegalStateException("repair context disappeared during restart"));
                    if (current.state() != ReservationRepairRepository.RepairState.STARTED) {
                        throw new IllegalStateException("failed repair context was not restarted");
                    }
                } else {
                    current = repairs.find(current.repairId()).orElseThrow(
                            () -> new IllegalStateException("restarted repair context disappeared"));
                }
            }
            String redisDisposition = repairRedisDisposition(entry, current);
            if (current.state() == ReservationRepairRepository.RepairState.STARTED) {
                requirePublished(stock.publishFence(
                        current.ticketItemId(), current.newFenceVersion(), "DRAINING"));
                if (!repairs.close(current.repairId())) {
                    throw new IllegalStateException("repair admission close was rejected");
                }
                requirePublished(stock.publishFence(
                        current.ticketItemId(), current.newFenceVersion(), "CLOSED"));
                requireRepaired(stock.repairMirror(
                        current.repairId(),
                        current.ticketItemId(),
                        current.newFenceVersion(),
                        current.snapshot().initial(),
                        current.snapshot().available(),
                        current.snapshot().reserved(),
                        current.snapshot().confirmed(),
                        redisDisposition));
                if (!repairs.markVerified(current.repairId(), redisDisposition)) {
                    throw new IllegalStateException("repair verification state was rejected");
                }
            }

            if (current.state() == ReservationRepairRepository.RepairState.STARTED
                    || current.state() == ReservationRepairRepository.RepairState.VERIFIED) {
                if (!repairs.open(current.repairId())) {
                    throw new IllegalStateException("repair admission reopen was rejected");
                }
                requirePublished(stock.publishFence(
                        current.ticketItemId(), current.newFenceVersion(), "OPEN"));
                if (!repairs.complete(current.repairId(), redisDisposition)) {
                    throw new IllegalStateException("repair completion was rejected");
                }
                resolveRepairedJournal(entry, current, redisDisposition);
            } else if (current.state() == ReservationRepairRepository.RepairState.COMPLETED) {
                resolveRepairedJournal(entry, current, redisDisposition);
            }
        } catch (RuntimeException exception) {
            log.warn("RESERVATION_RECOVERY: repair operation={} deferred after {}",
                    entry.operationId(), exception.getClass().getSimpleName());
            retryOrRepair(entry, "REPAIR_RETRY_FAILED");
        }
    }

    private void resolveRepairedJournal(
            OperationJournalRepository.JournalEntry entry,
            ReservationRepairRepository.RepairContext context,
            String redisDisposition
    ) {
        OperationJournalRepository.JournalState nextState;
        if (entry.operationType() == OperationJournalRepository.OperationType.CREATE) {
            boolean reservationExists = reservations.findById(entry.reservationId()).isPresent();
            nextState = reservationExists
                    ? OperationJournalRepository.JournalState.COMMITTED
                    : "REJECTED".equals(redisDisposition)
                    ? OperationJournalRepository.JournalState.REJECTED
                    : OperationJournalRepository.JournalState.COMPENSATED;
        } else {
            nextState = OperationJournalRepository.JournalState.COMMITTED;
        }
        String resultCode = entry.operationType() == OperationJournalRepository.OperationType.CREATE
                ? nextState == OperationJournalRepository.JournalState.COMMITTED
                ? "NEW"
                : nextState == OperationJournalRepository.JournalState.REJECTED
                ? context.disposition()
                : redisDisposition
                : terminalResultCode(entry);
        transition(entry, nextState, resultCode, null);
    }

    private String repairRedisDisposition(
            OperationJournalRepository.JournalEntry entry,
            ReservationRepairRepository.RepairContext context
    ) {
        if (entry.operationType() != OperationJournalRepository.OperationType.CREATE) {
            return "COMMITTED";
        }
        if (reservations.findById(entry.reservationId()).isPresent()) {
            return "COMMITTED";
        }
        return isUnadmittedStale(context.disposition()) ? "REJECTED" : "COMPENSATED";
    }

    private static boolean isUnadmittedStale(String disposition) {
        return "FENCE_STALE".equals(disposition)
                || "TICKET_ITEM_NOT_FOUND".equals(disposition)
                || "INVALID_CREATE".equals(disposition);
    }

    private static void requirePublished(String result) {
        if (!"PUBLISHED".equals(result) && !"REPLAYED".equals(result)) {
            throw new IllegalStateException("fence publication rejected: " + result);
        }
    }

    private static void requireRepaired(String result) {
        if (!"REPAIRED".equals(result) && !"REPLAYED".equals(result)) {
            throw new IllegalStateException("repair mirror rejected: " + result);
        }
    }

    private void recordRepairBudgetAlert() {
        telemetry.record("recover", "REPAIR_REQUIRED", "MAX_ATTEMPTS_EXCEEDED", Duration.ZERO);
    }

    private boolean transition(
            OperationJournalRepository.JournalEntry entry,
            OperationJournalRepository.JournalState nextState,
            String resultCode,
            Integer resultStockAfter
    ) {
        return transition(entry, entry.state(), nextState, resultCode, resultStockAfter);
    }

    private boolean transition(
            OperationJournalRepository.JournalEntry entry,
            OperationJournalRepository.JournalState expectedState,
            OperationJournalRepository.JournalState nextState,
            String resultCode,
            Integer resultStockAfter
    ) {
        return inTransaction(transaction, () -> journal.transition(
                entry.operationId(),
                expectedState,
                nextState,
                resultCode,
                resultStockAfter));
    }

    private static String terminalResultCode(OperationJournalRepository.JournalEntry entry) {
        if ("RELEASED".equals(entry.resultCode()) || "EXPIRED".equals(entry.resultCode())) {
            return entry.resultCode();
        }
        return switch (entry.operationType()) {
            case RELEASE -> "RELEASED";
            case EXPIRE -> "EXPIRED";
            default -> "MIRRORED";
        };
    }

    private static TransactionTemplate requiresNew(PlatformTransactionManager transactionManager) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template;
    }

    private static <T> T inTransaction(TransactionTemplate template, Supplier<T> action) {
        return Objects.requireNonNull(template.execute(status -> action.get()),
                "transaction callback returned null");
    }
}
