package com.xxxx.ddd.infrastructure.reservation.mysql;

import com.xxxx.ddd.application.MQ.OutboxService;
import com.xxxx.ddd.application.reservation.CreateReservationCommand;
import com.xxxx.ddd.application.reservation.CreateReservationResult;
import com.xxxx.ddd.application.reservation.CreateReservationService;
import com.xxxx.ddd.application.reservation.port.InventoryRepository;
import com.xxxx.ddd.application.reservation.port.OperationJournalRepository;
import com.xxxx.ddd.application.reservation.port.ReservationRepository;
import com.xxxx.ddd.application.reservation.port.ReservationStockPort;
import com.xxxx.ddd.application.reservation.strategy.ReservationCoordinationStrategy;
import com.xxxx.ddd.application.reservation.strategy.ReservationStrategy;
import com.xxxx.ddd.domain.reservation.InventorySnapshot;
import com.xxxx.ddd.domain.reservation.Reservation;
import com.xxxx.ddd.domain.reservation.ReservationStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * MySQL conditional comparison lane for the shared reservation state machine.
 *
 * <p>MySQL makes the admission decision with one conditional stock update. The durable
 * reservation and outbox event are committed before the same operation is applied to Redis as
 * the fast mirror. Keeping the journal in RECEIVED until that mirror step completes means a
 * crash is recovered by the existing create-recovery path without double-decrementing MySQL.
 *
 * <p><strong>LOCAL COMPARISON BASELINE ONLY:</strong>
 * The per-ticket lock used here ({@link ReentrantLock}) is deliberately process-local 
 * comparison-lane coordination. This class MUST NOT be presented or used as a multi-replica 
 * production strategy until a distributed lock or an equivalent cross-instance ordering contract 
 * is measured and enabled. It exists solely to serve as a local baseline comparison against Redis.
 */
@Component("localMySqlConditionalReservationStrategy")
public class LocalMySqlConditionalReservationStrategy implements ReservationCoordinationStrategy {

    private final OperationJournalRepository journal;
    private final InventoryRepository inventory;
    private final ReservationRepository reservations;
    private final ReservationStockPort stock;
    private final OutboxService outbox;
    private final TransactionTemplate transaction;
    private final ConcurrentHashMap<Long, ReentrantLock> ticketLocks = new ConcurrentHashMap<>();

    public LocalMySqlConditionalReservationStrategy(
            OperationJournalRepository journal,
            InventoryRepository inventory,
            ReservationRepository reservations,
            ReservationStockPort stock,
            OutboxService outbox,
            PlatformTransactionManager transactionManager
    ) {
        this.journal = journal;
        this.inventory = inventory;
        this.reservations = reservations;
        this.stock = stock;
        this.outbox = outbox;
        this.transaction = requiresNew(transactionManager);
    }

    @Override
    public ReservationStrategy strategy() {
        return ReservationStrategy.MYSQL_CONDITIONAL;
    }

    @Override
    public CreateReservationResult create(CreateReservationCommand command) {
        ReentrantLock ticketLock = ticketLocks.computeIfAbsent(command.ticketItemId(), ignored -> new ReentrantLock());
        ticketLock.lock();
        try {
            return createInternal(command);
        } finally {
            ticketLock.unlock();
        }
    }

    private CreateReservationResult createInternal(CreateReservationCommand command) {
        UUID operationId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        String idempotencyKeyHash = sha256Hex(command.idempotencyKey());
        String requestFingerprint = sha256Hex(
                "ticketItemId=" + command.ticketItemId() + "&quantity=" + command.quantity());
        OptionalLong fence = inventory.findFenceVersion(command.ticketItemId());
        if (fence.isEmpty()) {
            return result(
                    CreateReservationResult.Outcome.REJECTED,
                    operationId,
                    reservationId,
                    null,
                    null,
                    null,
                    "TICKET_ITEM_NOT_FOUND",
                    null);
        }

        OperationJournalRepository.JournalEntry claimed = inTransaction(() -> journal.claimCreate(
                new OperationJournalRepository.JournalEntry(
                        operationId,
                        reservationId,
                        command.demoActorId(),
                        idempotencyKeyHash,
                        requestFingerprint,
                        command.ticketItemId(),
                        command.quantity(),
                        fence.getAsLong(),
                        OperationJournalRepository.JournalState.RECEIVED,
                        null,
                        null)));
        if (!claimed.operationId().equals(operationId)) {
            return replay(command, claimed, requestFingerprint);
        }

        DatabaseCommit commit;
        try {
            commit = inTransaction(() -> commitDatabase(
                    command,
                    reservationId,
                    fence.getAsLong(),
                    idempotencyKeyHash,
                    requestFingerprint));
        } catch (ConditionalAdmissionException admissionFailure) {
            Rejection rejection = rejection(command.ticketItemId(), fence.getAsLong(), admissionFailure.available());
            transitionOrThrow(
                    operationId,
                    OperationJournalRepository.JournalState.RECEIVED,
                    OperationJournalRepository.JournalState.REJECTED,
                    rejection.code(),
                    rejection.stockAfter());
            return result(
                    rejection.outcome(),
                    operationId,
                    reservationId,
                    null,
                    null,
                    OperationJournalRepository.JournalState.REJECTED,
                    rejection.code(),
                    rejection.stockAfter());
        }

        transitionOrThrow(
                operationId,
                OperationJournalRepository.JournalState.RECEIVED,
                OperationJournalRepository.JournalState.REDIS_APPLYING,
                "REDIS_APPLYING",
                null);
        ReservationStockPort.RedisApplyResult mirrored;
        try {
            mirrored = stock.applyOnce(operationId, command.ticketItemId(), command.quantity(), fence.getAsLong());
        } catch (RuntimeException mirrorFailure) {
            markRepairRequired(operationId, OperationJournalRepository.JournalState.REDIS_APPLYING,
                    "REDIS_MIRROR_FAILED");
            return result(
                    CreateReservationResult.Outcome.PROCESSING,
                    operationId,
                    reservationId,
                    commit.reservation(),
                    commit.snapshot(),
                    OperationJournalRepository.JournalState.REPAIR_REQUIRED,
                    "REPAIR_REQUIRED",
                    null);
        }

        if (mirrored.status() != ReservationStockPort.RedisApplyResult.Status.APPLIED
                && mirrored.status() != ReservationStockPort.RedisApplyResult.Status.REPLAYED) {
            markRepairRequired(operationId, OperationJournalRepository.JournalState.REDIS_APPLYING,
                    "REDIS_MIRROR_" + mirrored.status().name());
            return result(
                    CreateReservationResult.Outcome.PROCESSING,
                    operationId,
                    reservationId,
                    commit.reservation(),
                    commit.snapshot(),
                    OperationJournalRepository.JournalState.REPAIR_REQUIRED,
                    "REPAIR_REQUIRED",
                    null);
        }
        if (mirrored.stockAfter() == null
                || !redisMirrorMatchesCurrentDatabase(command.ticketItemId(), mirrored.stockAfter())) {
            markRepairRequired(operationId, OperationJournalRepository.JournalState.REDIS_APPLYING,
                    "REDIS_MYSQL_DRIFT");
            return result(
                    CreateReservationResult.Outcome.PROCESSING,
                    operationId,
                    reservationId,
                    commit.reservation(),
                    commit.snapshot(),
                    OperationJournalRepository.JournalState.REPAIR_REQUIRED,
                    "REPAIR_REQUIRED",
                    null);
        }

        transitionOrThrow(
                operationId,
                OperationJournalRepository.JournalState.REDIS_APPLYING,
                OperationJournalRepository.JournalState.REDIS_APPLIED,
                "REDIS_APPLIED",
                mirrored.stockAfter());
        transitionOrThrow(
                operationId,
                OperationJournalRepository.JournalState.REDIS_APPLIED,
                OperationJournalRepository.JournalState.COMMITTED,
                "NEW",
                mirrored.stockAfter());
        return result(
                CreateReservationResult.Outcome.NEW,
                operationId,
                reservationId,
                commit.reservation(),
                commit.snapshot(),
                OperationJournalRepository.JournalState.COMMITTED,
                "NEW",
                mirrored.stockAfter());
    }

    private DatabaseCommit commitDatabase(
            CreateReservationCommand command,
            UUID reservationId,
            long fenceVersion,
            String idempotencyKeyHash,
            String requestFingerprint
    ) {
        if (!inventory.decrementIfAvailable(command.ticketItemId(), command.quantity(), fenceVersion)) {
            Integer available = currentAvailable(command.ticketItemId());
            throw new ConditionalAdmissionException(available);
        }

        Reservation reservation = new Reservation(
                reservationId,
                command.ticketItemId(),
                command.demoActorId(),
                command.quantity(),
                ReservationStatus.RESERVED,
                Instant.now().plus(CreateReservationService.RESERVATION_TTL),
                null);
        if (!reservations.insertReserved(reservation, fenceVersion, idempotencyKeyHash, requestFingerprint)) {
            throw new IllegalStateException("conditional reservation insert was rejected after stock admission");
        }

        InventorySnapshot snapshot = inventory.findSnapshot(command.ticketItemId())
                .orElseThrow(() -> new IllegalStateException("conditional inventory snapshot disappeared"));
        outbox.record(
                CreateReservationService.RESERVATION_AGGREGATE_TYPE,
                reservationId.toString(),
                CreateReservationService.RESERVATION_CREATED_EVENT,
                new ReservationCreatedPayload(
                        reservation.id(),
                        reservation.ticketItemId(),
                        reservation.demoActorId(),
                        reservation.quantity(),
                        reservation.expiresAt(),
                        snapshot.available()));
        return new DatabaseCommit(reservation, snapshot);
    }

    private CreateReservationResult replay(
            CreateReservationCommand command,
            OperationJournalRepository.JournalEntry claimed,
            String requestFingerprint
    ) {
        if (!claimed.requestFingerprint().equals(requestFingerprint)) {
            return result(
                    CreateReservationResult.Outcome.CONFLICT,
                    claimed.operationId(),
                    claimed.reservationId(),
                    null,
                    null,
                    claimed.state(),
                    "IDEMPOTENCY_CONFLICT",
                    null);
        }
        return switch (claimed.state()) {
            case COMMITTED -> replayCommitted(command, claimed);
            case REJECTED, COMPENSATED -> result(
                    CreateReservationResult.Outcome.REPLAYED,
                    claimed.operationId(),
                    claimed.reservationId(),
                    null,
                    null,
                    claimed.state(),
                    claimed.resultCode() == null ? claimed.state().name() : claimed.resultCode(),
                    claimed.resultStockAfter());
            case RECEIVED, REDIS_APPLYING, REDIS_APPLIED, COMPENSATION_PENDING, MIRROR_PENDING, REPAIR_REQUIRED -> result(
                    CreateReservationResult.Outcome.PROCESSING,
                    claimed.operationId(),
                    claimed.reservationId(),
                    null,
                    null,
                    claimed.state(),
                    claimed.state().name(),
                    claimed.resultStockAfter());
        };
    }

    private CreateReservationResult replayCommitted(
            CreateReservationCommand command,
            OperationJournalRepository.JournalEntry claimed
    ) {
        Optional<Reservation> reservation = reservations.findById(claimed.reservationId());
        Optional<InventorySnapshot> snapshot = reservation.isPresent()
                ? inventory.findSnapshot(command.ticketItemId())
                : Optional.empty();
        if (reservation.isEmpty()) {
            return result(
                    CreateReservationResult.Outcome.PROCESSING,
                    claimed.operationId(),
                    claimed.reservationId(),
                    null,
                    null,
                    claimed.state(),
                    "COMMITTED",
                    claimed.resultStockAfter());
        }
        return result(
                CreateReservationResult.Outcome.REPLAYED,
                claimed.operationId(),
                claimed.reservationId(),
                reservation.orElseThrow(),
                snapshot.orElse(null),
                claimed.state(),
                claimed.resultCode() == null ? claimed.state().name() : claimed.resultCode(),
                claimed.resultStockAfter());
    }

    private void markRepairRequired(
            UUID operationId,
            OperationJournalRepository.JournalState expectedState,
            String reason
    ) {
        if (!inTransaction(() -> journal.transition(
                operationId,
                expectedState,
                OperationJournalRepository.JournalState.REPAIR_REQUIRED,
                reason,
                null))) {
            throw new IllegalStateException("conditional strategy could not record repair requirement");
        }
    }

    private void transitionOrThrow(
            UUID operationId,
            OperationJournalRepository.JournalState expected,
            OperationJournalRepository.JournalState next,
            String resultCode,
            Integer stockAfter
    ) {
        if (!inTransaction(() -> journal.transition(operationId, expected, next, resultCode, stockAfter))) {
            throw new IllegalStateException("conditional strategy journal transition was lost");
        }
    }

    private Integer currentAvailable(long ticketItemId) {
        return inventory.findSnapshot(ticketItemId).map(InventorySnapshot::available).orElse(null);
    }

    private boolean redisMirrorMatchesCurrentDatabase(long ticketItemId, int redisAvailable) {
        for (int attempt = 0; attempt < 20; attempt++) {
            if (inventory.findSnapshot(ticketItemId)
                    .map(snapshot -> snapshot.available() == redisAvailable)
                    .orElse(false)) {
                return true;
            }
            try {
                TimeUnit.MILLISECONDS.sleep(10);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private Rejection rejection(long ticketItemId, long expectedFence, Integer available) {
        OptionalLong currentFence = inventory.findFenceVersion(ticketItemId);
        Optional<String> admissionState = inventory.findAdmissionState(ticketItemId);
        if (currentFence.isPresent() && currentFence.getAsLong() != expectedFence) {
            return new Rejection(CreateReservationResult.Outcome.FENCE_STALE, "FENCE_STALE", null);
        }
        if (admissionState.isPresent() && !"OPEN".equals(admissionState.orElseThrow())) {
            return new Rejection(CreateReservationResult.Outcome.REJECTED, "ADMISSION_"
                    + admissionState.orElseThrow(), null);
        }
        return new Rejection(CreateReservationResult.Outcome.SOLD_OUT, "SOLD_OUT", available);
    }

    private <T> T inTransaction(Supplier<T> action) {
        T result = transaction.execute(status -> action.get());
        return java.util.Objects.requireNonNull(result, "transaction callback returned null");
    }

    private static TransactionTemplate requiresNew(PlatformTransactionManager transactionManager) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template;
    }

    private static CreateReservationResult result(
            CreateReservationResult.Outcome outcome,
            UUID operationId,
            UUID reservationId,
            Reservation reservation,
            InventorySnapshot snapshot,
            OperationJournalRepository.JournalState journalState,
            String resultCode,
            Integer stockAfter
    ) {
        return new CreateReservationResult(
                outcome,
                operationId,
                reservationId,
                Optional.ofNullable(reservation),
                Optional.ofNullable(snapshot),
                journalState == null ? Optional.empty() : Optional.of(journalState),
                resultCode,
                stockAfter == null ? OptionalInt.empty() : OptionalInt.of(stockAfter));
    }

    private static String sha256Hex(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private record DatabaseCommit(Reservation reservation, InventorySnapshot snapshot) {
    }

    private record Rejection(CreateReservationResult.Outcome outcome, String code, Integer stockAfter) {
    }

    private record ReservationCreatedPayload(
            UUID reservationId,
            long ticketItemId,
            UUID demoActorId,
            int quantity,
            Instant expiresAt,
            int stockAfter
    ) {
    }

    private static final class ConditionalAdmissionException extends RuntimeException {
        private final Integer available;

        private ConditionalAdmissionException(Integer available) {
            super("conditional inventory admission was rejected");
            this.available = available;
        }

        private Integer available() {
            return available;
        }
    }
}
