package com.xxxx.ddd.application.reservation;

import com.xxxx.ddd.application.MQ.OutboxService;
import com.xxxx.ddd.application.reservation.port.FaultInjectionPort;
import com.xxxx.ddd.application.reservation.port.InventoryRepository;
import com.xxxx.ddd.application.reservation.port.OperationJournalRepository;
import com.xxxx.ddd.application.reservation.port.ReservationRepository;
import com.xxxx.ddd.application.reservation.port.ReservationStockPort;
import com.xxxx.ddd.application.reservation.port.ReservationTelemetryPort;
import com.xxxx.ddd.domain.reservation.InventorySnapshot;
import com.xxxx.ddd.domain.reservation.Reservation;
import com.xxxx.ddd.domain.reservation.ReservationStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CreateReservationServiceTest {

    private static final long TICKET_ITEM_ID = 42L;
    private static final int QUANTITY = 2;
    private static final long FENCE_VERSION = 7L;
    private static final UUID ACTOR_ID = UUID.fromString("7d8d4ed5-4f32-49c6-bc05-78ac4e23a4d2");

    private final OperationJournalRepository journal = mock(OperationJournalRepository.class);
    private final InventoryRepository inventory = mock(InventoryRepository.class);
    private final ReservationRepository reservations = mock(ReservationRepository.class);
    private final ReservationStockPort stock = mock(ReservationStockPort.class);
    private final ReservationTelemetryPort telemetry = mock(ReservationTelemetryPort.class);
    private final FaultInjectionPort faults = mock(FaultInjectionPort.class);
    private final OutboxService outbox = mock(OutboxService.class);
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    private final TransactionStatus transactionStatus = mock(TransactionStatus.class);

    private CreateReservationService service;

    @BeforeEach
    void setUp() {
        when(transactionManager.getTransaction(any(TransactionDefinition.class))).thenReturn(transactionStatus);
        when(inventory.findFenceVersion(TICKET_ITEM_ID)).thenReturn(OptionalLong.of(FENCE_VERSION));
        when(journal.claimCreate(any(OperationJournalRepository.JournalEntry.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(journal.transition(any(), any(), any(), any(), nullable(Integer.class))).thenReturn(true);
        service = new CreateReservationService(
                journal,
                inventory,
                reservations,
                stock,
                telemetry,
                faults,
                outbox,
                transactionManager);
    }

    @Test
    void createsJournaledReservationInRedisFirstOrder() {
        CreateReservationCommand command = command("create-1", TICKET_ITEM_ID, QUANTITY);
        when(stock.applyOnce(any(), eq(TICKET_ITEM_ID), eq(QUANTITY), eq(FENCE_VERSION)))
                .thenReturn(ReservationStockPort.RedisApplyResult.applied(8));
        when(inventory.decrementIfAvailable(TICKET_ITEM_ID, QUANTITY, FENCE_VERSION)).thenReturn(true);
        when(reservations.insertReserved(any(), eq(FENCE_VERSION), any(), any())).thenReturn(true);
        when(inventory.findSnapshot(TICKET_ITEM_ID))
                .thenReturn(Optional.of(new InventorySnapshot(TICKET_ITEM_ID, 10, 8, 2, 0)));

        CreateReservationResult result = service.create(command);

        assertThat(result.outcome()).isEqualTo(CreateReservationResult.Outcome.NEW);
        assertThat(result.reservation()).isPresent();
        assertThat(result.reservation().orElseThrow().status()).isEqualTo(ReservationStatus.RESERVED);
        assertThat(result.stockSnapshot()).contains(new InventorySnapshot(TICKET_ITEM_ID, 10, 8, 2, 0));
        assertThat(result.operationId()).isNotNull();
        assertThat(result.reservationId()).isEqualTo(result.reservation().orElseThrow().id());

        var order = inOrder(inventory, journal, stock, faults, reservations, outbox);
        order.verify(inventory).findFenceVersion(TICKET_ITEM_ID);
        order.verify(journal).claimCreate(any(OperationJournalRepository.JournalEntry.class));
        order.verify(journal).transition(
                any(),
                eq(OperationJournalRepository.JournalState.RECEIVED),
                eq(OperationJournalRepository.JournalState.REDIS_APPLYING),
                eq("REDIS_APPLYING"),
                eq(null));
        order.verify(stock).applyOnce(any(), eq(TICKET_ITEM_ID), eq(QUANTITY), eq(FENCE_VERSION));
        order.verify(faults).hit(eq(FaultInjectionPort.FaultPoint.AFTER_REDIS_BEFORE_DB), any());
        order.verify(journal).transition(
                any(),
                eq(OperationJournalRepository.JournalState.REDIS_APPLYING),
                eq(OperationJournalRepository.JournalState.REDIS_APPLIED),
                eq("REDIS_APPLIED"),
                eq(8));
        order.verify(inventory).decrementIfAvailable(TICKET_ITEM_ID, QUANTITY, FENCE_VERSION);
        order.verify(reservations).insertReserved(any(), eq(FENCE_VERSION), any(), any());
        order.verify(outbox).record(eq("Reservation"), any(), eq("reservation.created"), any());
        order.verify(journal).transition(
                any(),
                eq(OperationJournalRepository.JournalState.REDIS_APPLIED),
                eq(OperationJournalRepository.JournalState.COMMITTED),
                eq("NEW"),
                eq(8));
        verify(faults).hit(eq(FaultInjectionPort.FaultPoint.AFTER_DB_COMMIT_BEFORE_RESPONSE), any());
    }

    @Test
    void closedTicketAdmissionReturnsRepairRequiredBeforeClaimingANewOperation() {
        when(inventory.findAdmissionState(TICKET_ITEM_ID)).thenReturn(Optional.of("CLOSED"));

        CreateReservationResult result = service.create(command("create-during-repair", TICKET_ITEM_ID, QUANTITY));

        assertThat(result.outcome()).isEqualTo(CreateReservationResult.Outcome.PROCESSING);
        assertThat(result.journalState())
                .contains(OperationJournalRepository.JournalState.REPAIR_REQUIRED);
        assertThat(result.resultCode()).isEqualTo("REPAIR_REQUIRED");
        verifyNoInteractions(journal, stock, reservations, outbox, faults);
    }

    @Test
    void sameFingerprintCommittedClaimReplaysWithoutRedis() {
        CreateReservationCommand command = command("create-replay", TICKET_ITEM_ID, QUANTITY);
        UUID operationId = UUID.fromString("c92f4e7b-7c0a-4a85-b7f5-3b16ac3cfd47");
        UUID reservationId = UUID.fromString("62d7487a-13b6-4b25-8f5d-74a6f5cdbed1");
        Reservation reservation = reservation(reservationId, TICKET_ITEM_ID, QUANTITY);
        OperationJournalRepository.JournalEntry existing = journalEntry(
                operationId,
                reservationId,
                command,
                OperationJournalRepository.JournalState.COMMITTED,
                "NEW",
                8);
        when(journal.claimCreate(any())).thenReturn(existing);
        when(reservations.findById(reservationId)).thenReturn(Optional.of(reservation));
        when(inventory.findSnapshot(TICKET_ITEM_ID))
                .thenReturn(Optional.of(new InventorySnapshot(TICKET_ITEM_ID, 10, 8, 2, 0)));

        CreateReservationResult result = service.create(command);

        assertThat(result.outcome()).isEqualTo(CreateReservationResult.Outcome.REPLAYED);
        assertThat(result.operationId()).isEqualTo(operationId);
        assertThat(result.reservation()).contains(reservation);
        verifyNoInteractions(stock, faults, outbox);
    }

    @Test
    void sameIdempotencyKeyWithDifferentFingerprintReturnsConflict() {
        CreateReservationCommand command = command("create-conflict", TICKET_ITEM_ID, QUANTITY);
        OperationJournalRepository.JournalEntry existing = journalEntry(
                UUID.randomUUID(),
                UUID.randomUUID(),
                command("create-conflict", 99L, 1),
                OperationJournalRepository.JournalState.RECEIVED,
                null,
                null);
        when(journal.claimCreate(any())).thenReturn(existing);

        CreateReservationResult result = service.create(command);

        assertThat(result.outcome()).isEqualTo(CreateReservationResult.Outcome.CONFLICT);
        assertThat(result.resultCode()).isEqualTo("IDEMPOTENCY_CONFLICT");
        verifyNoInteractions(stock, faults, reservations, outbox);
    }

    @Test
    void persistsSoldOutAsNonRetryableRejection() {
        CreateReservationCommand command = command("create-sold-out", TICKET_ITEM_ID, QUANTITY);
        when(stock.applyOnce(any(), eq(TICKET_ITEM_ID), eq(QUANTITY), eq(FENCE_VERSION)))
                .thenReturn(ReservationStockPort.RedisApplyResult.soldOut(0));

        CreateReservationResult result = service.create(command);

        assertThat(result.outcome()).isEqualTo(CreateReservationResult.Outcome.SOLD_OUT);
        assertThat(result.resultCode()).isEqualTo("SOLD_OUT");
        assertThat(result.stockAfter()).hasValue(0);
        verify(journal).transition(
                any(),
                eq(OperationJournalRepository.JournalState.REDIS_APPLYING),
                eq(OperationJournalRepository.JournalState.REJECTED),
                eq("SOLD_OUT"),
                eq(0));
        verifyNoInteractions(reservations, outbox);
    }

    @Test
    void persistsFenceStaleWithoutExposingRedisStock() {
        CreateReservationCommand command = command("create-stale", TICKET_ITEM_ID, QUANTITY);
        when(stock.applyOnce(any(), eq(TICKET_ITEM_ID), eq(QUANTITY), eq(FENCE_VERSION)))
                .thenReturn(ReservationStockPort.RedisApplyResult.staleFence());

        CreateReservationResult result = service.create(command);

        assertThat(result.outcome()).isEqualTo(CreateReservationResult.Outcome.FENCE_STALE);
        assertThat(result.resultCode()).isEqualTo("FENCE_STALE");
        assertThat(result.stockAfter()).isEmpty();
        verify(journal).transition(
                any(),
                eq(OperationJournalRepository.JournalState.REDIS_APPLYING),
                eq(OperationJournalRepository.JournalState.REJECTED),
                eq("FENCE_STALE"),
                eq(null));
        verifyNoInteractions(reservations, outbox);
    }

    @Test
    void databaseFailureCompensatesRedisAndMarksCompensated() {
        CreateReservationCommand command = command("create-db-failure", TICKET_ITEM_ID, QUANTITY);
        when(stock.applyOnce(any(), eq(TICKET_ITEM_ID), eq(QUANTITY), eq(FENCE_VERSION)))
                .thenReturn(ReservationStockPort.RedisApplyResult.applied(8));
        when(inventory.decrementIfAvailable(TICKET_ITEM_ID, QUANTITY, FENCE_VERSION))
                .thenThrow(new IllegalStateException("database unavailable"));
        when(stock.compensateOnce(any(), eq(TICKET_ITEM_ID), eq(QUANTITY), eq(FENCE_VERSION)))
                .thenReturn(ReservationStockPort.RedisCompensationResult.compensated(10));

        CreateReservationResult result = service.create(command);

        assertThat(result.outcome()).isEqualTo(CreateReservationResult.Outcome.REJECTED);
        assertThat(result.resultCode()).isEqualTo("DATABASE_FAILURE");
        assertThat(result.journalState()).contains(OperationJournalRepository.JournalState.COMPENSATED);
        verify(stock).compensateOnce(any(), eq(TICKET_ITEM_ID), eq(QUANTITY), eq(FENCE_VERSION));
        verify(journal).transition(
                any(),
                eq(OperationJournalRepository.JournalState.REDIS_APPLIED),
                eq(OperationJournalRepository.JournalState.COMPENSATED),
                eq("DATABASE_FAILURE"),
                eq(10));
        verifyNoInteractions(outbox);
    }

    @Test
    void databaseFailureWithDurableReservationDoesNotCompensateRedisAgain() {
        CreateReservationCommand command = command("create-db-commit-ambiguous", TICKET_ITEM_ID, QUANTITY);
        UUID reservationId = UUID.randomUUID();
        Reservation persisted = reservation(reservationId, TICKET_ITEM_ID, QUANTITY);
        when(stock.applyOnce(any(), eq(TICKET_ITEM_ID), eq(QUANTITY), eq(FENCE_VERSION)))
                .thenReturn(ReservationStockPort.RedisApplyResult.applied(8));
        when(inventory.decrementIfAvailable(TICKET_ITEM_ID, QUANTITY, FENCE_VERSION))
                .thenThrow(new IllegalStateException("database commit result is ambiguous"));
        when(reservations.findById(any())).thenReturn(Optional.of(persisted));
        when(inventory.findSnapshot(TICKET_ITEM_ID))
                .thenReturn(Optional.of(new InventorySnapshot(TICKET_ITEM_ID, 10, 8, 2, 0)));

        CreateReservationResult result = service.create(command);

        assertThat(result.outcome()).isEqualTo(CreateReservationResult.Outcome.NEW);
        assertThat(result.reservation()).contains(persisted);
        assertThat(result.journalState()).contains(OperationJournalRepository.JournalState.COMMITTED);
        verify(stock, org.mockito.Mockito.never())
                .compensateOnce(any(), eq(TICKET_ITEM_ID), eq(QUANTITY), eq(FENCE_VERSION));
    }

    @Test
    void failedCompensationRemainsProcessingForRecovery() {
        CreateReservationCommand command = command("create-compensation-pending", TICKET_ITEM_ID, QUANTITY);
        when(stock.applyOnce(any(), eq(TICKET_ITEM_ID), eq(QUANTITY), eq(FENCE_VERSION)))
                .thenReturn(ReservationStockPort.RedisApplyResult.applied(8));
        when(inventory.decrementIfAvailable(TICKET_ITEM_ID, QUANTITY, FENCE_VERSION))
                .thenReturn(false);
        when(stock.compensateOnce(any(), eq(TICKET_ITEM_ID), eq(QUANTITY), eq(FENCE_VERSION)))
                .thenReturn(ReservationStockPort.RedisCompensationResult.conflict());

        CreateReservationResult result = service.create(command);

        assertThat(result.outcome()).isEqualTo(CreateReservationResult.Outcome.PROCESSING);
        assertThat(result.journalState())
                .contains(OperationJournalRepository.JournalState.COMPENSATION_PENDING);
        verify(journal).transition(
                any(),
                eq(OperationJournalRepository.JournalState.REDIS_APPLIED),
                eq(OperationJournalRepository.JournalState.COMPENSATION_PENDING),
                eq("COMPENSATION_PENDING"),
                eq(null));
    }

    @Test
    void afterRedisFaultStopsBeforeDatabaseTransaction() {
        CreateReservationCommand command = command("create-after-redis-fault", TICKET_ITEM_ID, QUANTITY);
        when(stock.applyOnce(any(), eq(TICKET_ITEM_ID), eq(QUANTITY), eq(FENCE_VERSION)))
                .thenReturn(ReservationStockPort.RedisApplyResult.applied(8));
        doAnswer(invocation -> {
            throw new IllegalStateException("injected fault");
        }).when(faults).hit(eq(FaultInjectionPort.FaultPoint.AFTER_REDIS_BEFORE_DB), any());

        assertThatThrownBy(() -> service.create(command))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("injected fault");
        verifyNoInteractions(reservations, outbox);
        verify(journal).transition(
                any(),
                eq(OperationJournalRepository.JournalState.RECEIVED),
                eq(OperationJournalRepository.JournalState.REDIS_APPLYING),
                eq("REDIS_APPLYING"),
                eq(null));
    }

    @Test
    void afterDatabaseCommitFaultDoesNotTriggerCompensation() {
        CreateReservationCommand command = command("create-after-db-fault", TICKET_ITEM_ID, QUANTITY);
        when(stock.applyOnce(any(), eq(TICKET_ITEM_ID), eq(QUANTITY), eq(FENCE_VERSION)))
                .thenReturn(ReservationStockPort.RedisApplyResult.applied(8));
        when(inventory.decrementIfAvailable(TICKET_ITEM_ID, QUANTITY, FENCE_VERSION)).thenReturn(true);
        when(reservations.insertReserved(any(), eq(FENCE_VERSION), any(), any())).thenReturn(true);
        when(inventory.findSnapshot(TICKET_ITEM_ID))
                .thenReturn(Optional.of(new InventorySnapshot(TICKET_ITEM_ID, 10, 8, 2, 0)));
        doAnswer(invocation -> {
            if (invocation.getArgument(0) == FaultInjectionPort.FaultPoint.AFTER_DB_COMMIT_BEFORE_RESPONSE) {
                throw new IllegalStateException("post-commit fault");
            }
            return null;
        }).when(faults).hit(any(), any());

        assertThatThrownBy(() -> service.create(command))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("post-commit fault");

        verify(journal).transition(
                any(),
                eq(OperationJournalRepository.JournalState.REDIS_APPLIED),
                eq(OperationJournalRepository.JournalState.COMMITTED),
                eq("NEW"),
                eq(8));
        verify(transactionManager, times(4)).commit(transactionStatus);
        verify(stock, org.mockito.Mockito.never()).compensateOnce(any(), anyLong(), anyInt(), anyLong());
    }

    private static CreateReservationCommand command(String idempotencyKey, long ticketItemId, int quantity) {
        return new CreateReservationCommand(ticketItemId, quantity, ACTOR_ID, idempotencyKey);
    }

    private static Reservation reservation(UUID reservationId, long ticketItemId, int quantity) {
        return new Reservation(
                reservationId,
                ticketItemId,
                ACTOR_ID,
                quantity,
                ReservationStatus.RESERVED,
                Instant.now().plusSeconds(120),
                null);
    }

    private static OperationJournalRepository.JournalEntry journalEntry(
            UUID operationId,
            UUID reservationId,
            CreateReservationCommand command,
            OperationJournalRepository.JournalState state,
            String resultCode,
            Integer stockAfter
    ) {
        return new OperationJournalRepository.JournalEntry(
                operationId,
                reservationId,
                ACTOR_ID,
                sha256Hex(command.idempotencyKey()),
                fingerprint(command),
                command.ticketItemId(),
                command.quantity(),
                FENCE_VERSION,
                state,
                resultCode,
                stockAfter);
    }

    private static String fingerprint(CreateReservationCommand command) {
        return sha256Hex("ticketItemId=" + command.ticketItemId() + "&quantity=" + command.quantity());
    }

    private static String sha256Hex(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }
}
