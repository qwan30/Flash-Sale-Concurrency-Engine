package com.xxxx.ddd.application.reservation;

import com.xxxx.ddd.application.MQ.OutboxService;
import com.xxxx.ddd.application.reservation.port.FaultInjectionPort;
import com.xxxx.ddd.application.reservation.port.InventoryRepository;
import com.xxxx.ddd.application.reservation.port.OperationJournalRepository;
import com.xxxx.ddd.application.reservation.port.ReservationRepository;
import com.xxxx.ddd.application.reservation.port.ReservationStockPort;
import com.xxxx.ddd.application.reservation.port.ReservationTelemetryPort;
import com.xxxx.ddd.application.reservation.strategy.ReservationCoordinationStrategy;
import com.xxxx.ddd.application.reservation.strategy.ReservationStrategy;
import com.xxxx.ddd.domain.reservation.InventorySnapshot;
import com.xxxx.ddd.domain.reservation.Reservation;
import com.xxxx.ddd.domain.reservation.ReservationStatus;
import io.micrometer.observation.annotation.Observed;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.function.Supplier;

@Service
public class CreateReservationService implements ReservationCoordinationStrategy {

    public static final Duration RESERVATION_TTL = Duration.ofSeconds(120);
    public static final String RESERVATION_AGGREGATE_TYPE = "Reservation";
    public static final String RESERVATION_CREATED_EVENT = "reservation.created";

    private final OperationJournalRepository journal;
    private final InventoryRepository inventory;
    private final ReservationRepository reservations;
    private final ReservationStockPort stock;
    private final ReservationTelemetryPort telemetry;
    private final FaultInjectionPort faults;
    private final OutboxService outbox;
    private final TransactionTemplate claimTransaction;
    private final TransactionTemplate databaseTransaction;

    public CreateReservationService(
            OperationJournalRepository journal,
            InventoryRepository inventory,
            ReservationRepository reservations,
            ReservationStockPort stock,
            ReservationTelemetryPort telemetry,
            FaultInjectionPort faults,
            OutboxService outbox,
            PlatformTransactionManager transactionManager
    ) {
        this.journal = Objects.requireNonNull(journal, "journal must not be null");
        this.inventory = Objects.requireNonNull(inventory, "inventory must not be null");
        this.reservations = Objects.requireNonNull(reservations, "reservations must not be null");
        this.stock = Objects.requireNonNull(stock, "stock must not be null");
        this.telemetry = Objects.requireNonNull(telemetry, "telemetry must not be null");
        this.faults = Objects.requireNonNull(faults, "faults must not be null");
        this.outbox = Objects.requireNonNull(outbox, "outbox must not be null");
        this.claimTransaction = requiresNew(Objects.requireNonNull(transactionManager,
                "transactionManager must not be null"));
        this.databaseTransaction = requiresNew(transactionManager);
    }

    @Override
    public ReservationStrategy strategy() {
        return ReservationStrategy.REDIS_FIRST;
    }

    @Observed(name = "flashsale.reservation.create")
    public CreateReservationResult create(CreateReservationCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        Instant startedAt = Instant.now();
        try {
            CreateReservationResult result = createInternal(command);
            recordTelemetry(result.outcome().name(), result.resultCode(), startedAt);
            return result;
        } catch (RuntimeException exception) {
            recordTelemetry("EXCEPTION", "UNHANDLED", startedAt);
            throw exception;
        }
    }

    private CreateReservationResult createInternal(CreateReservationCommand command) {
        UUID operationId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        String idempotencyKeyHash = sha256Hex(command.idempotencyKey());
        String requestFingerprint = requestFingerprint(command);

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

        Optional<String> admissionState = inventory.findAdmissionState(command.ticketItemId());
        if (admissionState.isPresent() && !"OPEN".equals(admissionState.orElseThrow())) {
            return result(
                    CreateReservationResult.Outcome.PROCESSING,
                    operationId,
                    reservationId,
                    null,
                    null,
                    OperationJournalRepository.JournalState.REPAIR_REQUIRED,
                    "REPAIR_REQUIRED",
                    null);
        }

        OperationJournalRepository.JournalEntry candidate = new OperationJournalRepository.JournalEntry(
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
                null);
        OperationJournalRepository.JournalEntry claimed = inTransaction(
                claimTransaction,
                () -> journal.claimCreate(candidate));

        if (!claimed.operationId().equals(operationId)) {
            return replayClaim(command, claimed, requestFingerprint);
        }

        transitionInNewTransactionOrThrow(
                operationId,
                OperationJournalRepository.JournalState.RECEIVED,
                OperationJournalRepository.JournalState.REDIS_APPLYING,
                "REDIS_APPLYING",
                null);
        ReservationStockPort.RedisApplyResult applied = stock.applyOnce(
                operationId,
                command.ticketItemId(),
                command.quantity(),
                fence.getAsLong());
        if (applied.status() == ReservationStockPort.RedisApplyResult.Status.SOLD_OUT) {
            persistRejected(operationId, OperationJournalRepository.JournalState.REDIS_APPLYING,
                    "SOLD_OUT", applied.stockAfter());
            return result(
                    CreateReservationResult.Outcome.SOLD_OUT,
                    operationId,
                    reservationId,
                    null,
                    null,
                    OperationJournalRepository.JournalState.REJECTED,
                    "SOLD_OUT",
                    applied.stockAfter());
        }
        if (applied.status() == ReservationStockPort.RedisApplyResult.Status.STALE_FENCE) {
            persistRejected(operationId, OperationJournalRepository.JournalState.REDIS_APPLYING,
                    "FENCE_STALE", null);
            return result(
                    CreateReservationResult.Outcome.FENCE_STALE,
                    operationId,
                    reservationId,
                    null,
                    null,
                    OperationJournalRepository.JournalState.REJECTED,
                    "FENCE_STALE",
                    null);
        }
        if (applied.status() == ReservationStockPort.RedisApplyResult.Status.CONFLICT) {
            persistRejected(operationId, OperationJournalRepository.JournalState.REDIS_APPLYING,
                    "CONFLICT", null);
            return result(
                    CreateReservationResult.Outcome.CONFLICT,
                    operationId,
                    reservationId,
                    null,
                    null,
                    OperationJournalRepository.JournalState.REJECTED,
                    "CONFLICT",
                    null);
        }

        faults.hit(FaultInjectionPort.FaultPoint.AFTER_REDIS_BEFORE_DB, operationId);
        transitionInNewTransactionOrThrow(
                operationId,
                OperationJournalRepository.JournalState.REDIS_APPLYING,
                OperationJournalRepository.JournalState.REDIS_APPLIED,
                "REDIS_APPLIED",
                applied.stockAfter());

        DatabaseCommit commit;
        try {
            commit = inTransaction(databaseTransaction, () -> commitDatabase(
                    command,
                    operationId,
                    reservationId,
                    fence.getAsLong(),
                    idempotencyKeyHash,
                    requestFingerprint,
                    applied.stockAfter()));
        } catch (RuntimeException databaseFailure) {
            return compensateAfterDatabaseFailure(command, operationId, reservationId, fence.getAsLong());
        }

        faults.hit(FaultInjectionPort.FaultPoint.AFTER_DB_COMMIT_BEFORE_RESPONSE, operationId);
        return result(
                CreateReservationResult.Outcome.NEW,
                operationId,
                reservationId,
                commit.reservation(),
                commit.snapshot(),
                OperationJournalRepository.JournalState.COMMITTED,
                "NEW",
                applied.stockAfter());
    }

    private DatabaseCommit commitDatabase(
            CreateReservationCommand command,
            UUID operationId,
            UUID reservationId,
            long fenceVersion,
            String idempotencyKeyHash,
            String requestFingerprint,
            int redisStockAfter
    ) {
        if (!inventory.decrementIfAvailable(command.ticketItemId(), command.quantity(), fenceVersion)) {
            throw new IllegalStateException("durable inventory admission was rejected");
        }

        Reservation reservation = new Reservation(
                reservationId,
                command.ticketItemId(),
                command.demoActorId(),
                command.quantity(),
                ReservationStatus.RESERVED,
                Instant.now().plus(RESERVATION_TTL),
                null);
        if (!reservations.insertReserved(reservation, fenceVersion, idempotencyKeyHash, requestFingerprint)) {
            throw new IllegalStateException("durable reservation insert was rejected");
        }

        outbox.record(
                RESERVATION_AGGREGATE_TYPE,
                reservationId.toString(),
                RESERVATION_CREATED_EVENT,
                new ReservationCreatedPayload(
                        reservation.id(),
                        reservation.ticketItemId(),
                        reservation.demoActorId(),
                        reservation.quantity(),
                        reservation.expiresAt(),
                        redisStockAfter));
        transitionInCurrentTransactionOrThrow(
                operationId,
                OperationJournalRepository.JournalState.REDIS_APPLIED,
                OperationJournalRepository.JournalState.COMMITTED,
                "NEW",
                redisStockAfter);

        InventorySnapshot snapshot = inventory.findSnapshot(command.ticketItemId())
                .orElseThrow(() -> new IllegalStateException("durable inventory snapshot disappeared"));
        return new DatabaseCommit(reservation, snapshot);
    }

    private CreateReservationResult compensateAfterDatabaseFailure(
            CreateReservationCommand command,
            UUID operationId,
            UUID reservationId,
            long fenceVersion
    ) {
        Optional<CreateReservationResult> committed = reconcileDurableReservation(
                command,
                operationId,
                reservationId);
        if (committed.isPresent()) {
            return committed.orElseThrow();
        }

        try {
            ReservationStockPort.RedisCompensationResult compensation = stock.compensateOnce(
                    operationId,
                    command.ticketItemId(),
                    command.quantity(),
                    fenceVersion);
            if (compensation.status() == ReservationStockPort.RedisCompensationResult.Status.COMPENSATED
                    || compensation.status() == ReservationStockPort.RedisCompensationResult.Status.REPLAYED) {
                transitionInNewTransactionOrThrow(
                        operationId,
                        OperationJournalRepository.JournalState.REDIS_APPLIED,
                        OperationJournalRepository.JournalState.COMPENSATED,
                        "DATABASE_FAILURE",
                        compensation.stockAfter());
                return result(
                        CreateReservationResult.Outcome.REJECTED,
                        operationId,
                        reservationId,
                        null,
                        null,
                        OperationJournalRepository.JournalState.COMPENSATED,
                        "DATABASE_FAILURE",
                        compensation.stockAfter());
            }
        } catch (RuntimeException ignored) {
            // A second failure is represented durably below and is recovered by the journal worker.
        }

        transitionInNewTransactionOrThrow(
                operationId,
                OperationJournalRepository.JournalState.REDIS_APPLIED,
                OperationJournalRepository.JournalState.COMPENSATION_PENDING,
                "COMPENSATION_PENDING",
                null);
        return result(
                CreateReservationResult.Outcome.PROCESSING,
                operationId,
                reservationId,
                null,
                null,
                OperationJournalRepository.JournalState.COMPENSATION_PENDING,
                "COMPENSATION_PENDING",
                null);
    }

    private Optional<CreateReservationResult> reconcileDurableReservation(
            CreateReservationCommand command,
            UUID operationId,
            UUID reservationId
    ) {
        Optional<Reservation> persisted = reservations.findById(reservationId);
        if (persisted.isEmpty()) {
            return Optional.empty();
        }
        InventorySnapshot snapshot = inventory.findSnapshot(command.ticketItemId()).orElse(null);
        return Optional.of(result(
                CreateReservationResult.Outcome.NEW,
                operationId,
                reservationId,
                persisted.orElseThrow(),
                snapshot,
                OperationJournalRepository.JournalState.COMMITTED,
                "NEW",
                snapshot == null ? null : snapshot.available()));
    }

    private CreateReservationResult replayClaim(
            CreateReservationCommand command,
            OperationJournalRepository.JournalEntry claimed,
            String requestFingerprint
    ) {
        if (!claimed.requestFingerprint().equals(requestFingerprint)) {
            return resultWithState(
                    CreateReservationResult.Outcome.CONFLICT,
                    claimed.operationId(),
                    claimed.reservationId(),
                    null,
                    null,
                    Optional.of(claimed.state()),
                    "IDEMPOTENCY_CONFLICT",
                    null);
        }

        return switch (claimed.state()) {
            case COMMITTED -> replayCommitted(command, claimed);
            case REJECTED, COMPENSATED -> resultWithState(
                    CreateReservationResult.Outcome.REPLAYED,
                    claimed.operationId(),
                    claimed.reservationId(),
                    null,
                    null,
                    Optional.of(claimed.state()),
                    codeOrState(claimed),
                    claimed.resultStockAfter());
            case REPAIR_REQUIRED -> resultWithState(
                    CreateReservationResult.Outcome.REJECTED,
                    claimed.operationId(),
                    claimed.reservationId(),
                    null,
                    null,
                    Optional.of(claimed.state()),
                    "REPAIR_REQUIRED",
                    null);
            case RECEIVED, REDIS_APPLYING, REDIS_APPLIED, COMPENSATION_PENDING, MIRROR_PENDING -> resultWithState(
                    CreateReservationResult.Outcome.PROCESSING,
                    claimed.operationId(),
                    claimed.reservationId(),
                    null,
                    null,
                    Optional.of(claimed.state()),
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
            return resultWithState(
                    CreateReservationResult.Outcome.PROCESSING,
                    claimed.operationId(),
                    claimed.reservationId(),
                    null,
                    null,
                    Optional.of(claimed.state()),
                    "COMMITTED",
                    claimed.resultStockAfter());
        }
        return resultWithState(
                CreateReservationResult.Outcome.REPLAYED,
                claimed.operationId(),
                claimed.reservationId(),
                reservation.orElseThrow(),
                snapshot.orElse(null),
                Optional.of(claimed.state()),
                codeOrState(claimed),
                claimed.resultStockAfter());
    }

    private void persistRejected(
            UUID operationId,
            OperationJournalRepository.JournalState expectedState,
            String code,
            Integer stockAfter
    ) {
        transitionInNewTransactionOrThrow(
                operationId,
                expectedState,
                OperationJournalRepository.JournalState.REJECTED,
                code,
                stockAfter);
    }

    private void transitionInNewTransactionOrThrow(
            UUID operationId,
            OperationJournalRepository.JournalState expected,
            OperationJournalRepository.JournalState next,
            String resultCode,
            Integer stockAfter
    ) {
        boolean updated = inTransaction(
                claimTransaction,
                () -> journal.transition(operationId, expected, next, resultCode, stockAfter));
        if (!updated) {
            throw new IllegalStateException("journal transition was not applied");
        }
    }

    private void transitionInCurrentTransactionOrThrow(
            UUID operationId,
            OperationJournalRepository.JournalState expected,
            OperationJournalRepository.JournalState next,
            String resultCode,
            Integer stockAfter
    ) {
        if (!journal.transition(operationId, expected, next, resultCode, stockAfter)) {
            throw new IllegalStateException("journal transition was not applied");
        }
    }

    private CreateReservationResult result(
            CreateReservationResult.Outcome outcome,
            UUID operationId,
            UUID reservationId,
            Reservation reservation,
            InventorySnapshot snapshot,
            OperationJournalRepository.JournalState journalState,
            String resultCode,
            Integer stockAfter
    ) {
        return resultWithState(
                outcome,
                operationId,
                reservationId,
                reservation,
                snapshot,
                journalState == null ? Optional.empty() : Optional.of(journalState),
                resultCode,
                stockAfter);
    }

    private CreateReservationResult resultWithState(
            CreateReservationResult.Outcome outcome,
            UUID operationId,
            UUID reservationId,
            Reservation reservation,
            InventorySnapshot snapshot,
            Optional<OperationJournalRepository.JournalState> journalState,
            String resultCode,
            Integer stockAfter
    ) {
        return new CreateReservationResult(
                outcome,
                operationId,
                reservationId,
                Optional.ofNullable(reservation),
                Optional.ofNullable(snapshot),
                journalState,
                resultCode,
                stockAfter == null ? OptionalInt.empty() : OptionalInt.of(stockAfter));
    }

    private void recordTelemetry(String outcome, String reason, Instant startedAt) {
        telemetry.record(
                "create",
                outcome,
                reason,
                Duration.between(startedAt, Instant.now()));
    }

    private static String codeOrState(OperationJournalRepository.JournalEntry entry) {
        return entry.resultCode() == null ? entry.state().name() : entry.resultCode();
    }

    private static String requestFingerprint(CreateReservationCommand command) {
        return sha256Hex("ticketItemId=" + command.ticketItemId() + "&quantity=" + command.quantity());
    }

    private static String sha256Hex(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
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

    private record DatabaseCommit(Reservation reservation, InventorySnapshot snapshot) {
    }

    record ReservationCreatedPayload(
            UUID reservationId,
            long ticketItemId,
            UUID demoActorId,
            int quantity,
            Instant expiresAt,
            int stockAfter
    ) {
    }
}
