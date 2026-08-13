package com.xxxx.ddd.application.reservation;

import com.xxxx.ddd.application.MQ.OutboxService;
import com.xxxx.ddd.application.reservation.port.InventoryRepository;
import com.xxxx.ddd.application.reservation.port.OperationJournalRepository;
import com.xxxx.ddd.application.reservation.port.ReservationRepository;
import com.xxxx.ddd.application.reservation.port.ReservationStockPort;
import com.xxxx.ddd.application.reservation.port.ReservationRepairRepository;
import com.xxxx.ddd.application.reservation.port.ReservationTelemetryPort;
import com.xxxx.ddd.domain.reservation.InventorySnapshot;
import com.xxxx.ddd.domain.reservation.Reservation;
import com.xxxx.ddd.domain.reservation.ReservationStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

import java.util.OptionalLong;
import java.util.Optional;
import java.util.UUID;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ReservationRecoveryServiceTest {

    private static final UUID OPERATION_ID = UUID.fromString("f0f30dd9-2f8e-4d35-9c1f-5c6c3ee08d7c");
    private static final UUID RESERVATION_ID = UUID.fromString("2f03a82c-ab45-4f87-b8ea-3d407198f5c2");
    private static final long TICKET_ITEM_ID = 42L;
    private static final int QUANTITY = 2;
    private static final long FENCE_VERSION = 7L;
    private static final UUID REPAIR_ID = UUID.fromString("62ed9d04-bf8c-442e-9c1f-0c5a1f0c5e36");

    private final OperationJournalRepository journal = mock(OperationJournalRepository.class);
    private final InventoryRepository inventory = mock(InventoryRepository.class);
    private final ReservationRepository reservations = mock(ReservationRepository.class);
    private final ReservationStockPort stock = mock(ReservationStockPort.class);
    private final OutboxService outbox = mock(OutboxService.class);
    private final ReservationRepairRepository repairs = mock(ReservationRepairRepository.class);
    private final ReservationTelemetryPort telemetry = mock(ReservationTelemetryPort.class);
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    private final TransactionStatus transactionStatus = mock(TransactionStatus.class);

    private ReservationRecoveryService service;

    @BeforeEach
    void setUp() {
        when(transactionManager.getTransaction(any(TransactionDefinition.class))).thenReturn(transactionStatus);
        when(inventory.findFenceVersion(TICKET_ITEM_ID)).thenReturn(OptionalLong.of(FENCE_VERSION));
        when(journal.attempts(OPERATION_ID)).thenReturn(1);
        when(journal.transition(any(), any(), any(), any(), any())).thenReturn(true);
        when(journal.scheduleRetry(any(), any(), any(), any())).thenReturn(true);
        when(stock.operationState(OPERATION_ID)).thenReturn(Optional.of(
                ReservationStockPort.RedisOperationState.applied(
                        TICKET_ITEM_ID, QUANTITY, FENCE_VERSION, 8)));
        service = new ReservationRecoveryService(
                journal, inventory, reservations, stock, outbox, transactionManager, repairs);
        service.setReservationTelemetryPort(telemetry);
    }

    @Test
    void retriesPendingCompensationAndCommitsItWhenRedisConverges() {
        when(stock.compensateOnce(OPERATION_ID, TICKET_ITEM_ID, QUANTITY, FENCE_VERSION))
                .thenReturn(ReservationStockPort.RedisCompensationResult.compensated(10));

        service.recover(entry(OperationJournalRepository.JournalState.COMPENSATION_PENDING));

        verify(journal).transition(
                OPERATION_ID,
                OperationJournalRepository.JournalState.COMPENSATION_PENDING,
                OperationJournalRepository.JournalState.COMPENSATED,
                "COMPENSATED",
                10);
    }

    @Test
    void missingRedisEvidenceForPendingCompensationEntersRepairWithoutMutatingStock() {
        when(stock.operationState(OPERATION_ID)).thenReturn(Optional.empty());

        service.recover(entry(OperationJournalRepository.JournalState.COMPENSATION_PENDING));

        verify(stock, never()).compensateOnce(any(), anyLong(), anyInt(), anyLong());
        verify(journal).transition(
                OPERATION_ID,
                OperationJournalRepository.JournalState.COMPENSATION_PENDING,
                OperationJournalRepository.JournalState.REPAIR_REQUIRED,
                "REDIS_OPERATION_STATE_MISSING",
                null);
    }

    @Test
    void retriesPendingMirrorAndCommitsTheTerminalJournal() {
        service.recover(entry(OperationJournalRepository.JournalState.MIRROR_PENDING));

        verify(stock).mirrorTerminalOnce(OPERATION_ID, TICKET_ITEM_ID, QUANTITY, FENCE_VERSION);
        verify(journal).transition(
                OPERATION_ID,
                OperationJournalRepository.JournalState.MIRROR_PENDING,
                OperationJournalRepository.JournalState.COMMITTED,
                "RELEASED",
                null);
    }

    @Test
    void missingRedisEvidenceForPendingMirrorEntersRepairWithoutMutatingStock() {
        when(stock.operationState(OPERATION_ID)).thenReturn(Optional.empty());

        service.recover(entry(OperationJournalRepository.JournalState.MIRROR_PENDING));

        verify(stock, never()).mirrorTerminalOnce(any(), anyLong(), anyInt(), anyLong());
        verify(journal).transition(
                OPERATION_ID,
                OperationJournalRepository.JournalState.MIRROR_PENDING,
                OperationJournalRepository.JournalState.REPAIR_REQUIRED,
                "REDIS_OPERATION_STATE_MISSING",
                null);
    }

    @Test
    void invalidRedisEvidenceForPendingMirrorEntersRepairWithoutMutatingStock() {
        when(stock.operationState(OPERATION_ID)).thenReturn(Optional.of(
                new ReservationStockPort.RedisOperationState(
                        ReservationStockPort.RedisOperationState.Status.SOLD_OUT,
                        8,
                        TICKET_ITEM_ID,
                        QUANTITY,
                        FENCE_VERSION)));

        service.recover(entry(OperationJournalRepository.JournalState.MIRROR_PENDING));

        verify(stock, never()).mirrorTerminalOnce(any(), anyLong(), anyInt(), anyLong());
        verify(journal).transition(
                OPERATION_ID,
                OperationJournalRepository.JournalState.MIRROR_PENDING,
                OperationJournalRepository.JournalState.REPAIR_REQUIRED,
                "REDIS_OPERATION_STATE_INVALID",
                null);
    }

    @Test
    void recordsTheActualJournalDispositionAfterRecoveryTransition() {
        when(journal.findByOperationId(OPERATION_ID)).thenReturn(Optional.of(
                entry(OperationJournalRepository.JournalState.COMMITTED)));

        service.recover(entry(OperationJournalRepository.JournalState.MIRROR_PENDING));

        verify(telemetry).record(
                eq("recover"),
                eq("COMMITTED"),
                eq("COMMITTED"),
                any(java.time.Duration.class));
    }

    @Test
    void sharedCreateJournalMirrorRetainsTheTerminalResultCode() {
        OperationJournalRepository.JournalEntry pending = new OperationJournalRepository.JournalEntry(
                OPERATION_ID,
                RESERVATION_ID,
                UUID.fromString("73e4b5c9-1d67-4d3a-8db8-2e3e21c27844"),
                "b".repeat(64),
                "a".repeat(64),
                TICKET_ITEM_ID,
                QUANTITY,
                FENCE_VERSION,
                OperationJournalRepository.JournalState.MIRROR_PENDING,
                "RELEASED",
                null);

        service.recover(pending);

        verify(journal).transition(
                OPERATION_ID,
                OperationJournalRepository.JournalState.MIRROR_PENDING,
                OperationJournalRepository.JournalState.COMMITTED,
                "RELEASED",
                null);
    }

    @Test
    void staleFenceMovesPendingMirrorToRepairRequiredWithoutRedisRetry() {
        when(inventory.findFenceVersion(TICKET_ITEM_ID)).thenReturn(OptionalLong.of(FENCE_VERSION + 1));

        service.recover(entry(OperationJournalRepository.JournalState.MIRROR_PENDING));

        verify(stock, never()).mirrorTerminalOnce(any(), anyLong(), anyInt(), anyLong());
        verify(journal).transition(
                OPERATION_ID,
                OperationJournalRepository.JournalState.MIRROR_PENDING,
                OperationJournalRepository.JournalState.REPAIR_REQUIRED,
                "FENCE_STALE",
                null);
    }

    @Test
    void staleFenceMovesPendingCompensationToRepairRequiredWithoutRedisRetry() {
        when(inventory.findFenceVersion(TICKET_ITEM_ID)).thenReturn(OptionalLong.of(FENCE_VERSION + 1));

        service.recover(entry(OperationJournalRepository.JournalState.COMPENSATION_PENDING));

        verify(stock, never()).compensateOnce(any(), anyLong(), anyInt(), anyLong());
        verify(journal).transition(
                OPERATION_ID,
                OperationJournalRepository.JournalState.COMPENSATION_PENDING,
                OperationJournalRepository.JournalState.REPAIR_REQUIRED,
                "FENCE_STALE",
                null);
    }

    @Test
    void transientMirrorFailureSchedulesBoundedRetry() {
        doThrow(new IllegalStateException("redis unavailable"))
                .when(stock).mirrorTerminalOnce(OPERATION_ID, TICKET_ITEM_ID, QUANTITY, FENCE_VERSION);

        service.recover(entry(OperationJournalRepository.JournalState.MIRROR_PENDING));

        verify(journal).scheduleRetry(
                eq(OPERATION_ID),
                eq(OperationJournalRepository.JournalState.MIRROR_PENDING),
                eq("MIRROR_RETRY_FAILED"),
                any());
    }

    @Test
    void fifthFailedAttemptMovesOperationToRepairRequired() {
        when(journal.attempts(OPERATION_ID)).thenReturn(5);
        doThrow(new IllegalStateException("redis unavailable"))
                .when(stock).mirrorTerminalOnce(OPERATION_ID, TICKET_ITEM_ID, QUANTITY, FENCE_VERSION);

        service.recover(entry(OperationJournalRepository.JournalState.MIRROR_PENDING));

        verify(journal).transition(
                OPERATION_ID,
                OperationJournalRepository.JournalState.MIRROR_PENDING,
                OperationJournalRepository.JournalState.REPAIR_REQUIRED,
                "MAX_ATTEMPTS_EXCEEDED",
                null);
        verify(telemetry).record(
                eq("recover"),
                eq("REPAIR_REQUIRED"),
                eq("MAX_ATTEMPTS_EXCEEDED"),
                any());
    }

    @Test
    void exhaustedRepairRequiredEntryIsNotRetriedAgain() {
        when(journal.attempts(OPERATION_ID)).thenReturn(6);

        service.recover(entry(OperationJournalRepository.JournalState.REPAIR_REQUIRED));

        verify(journal, never()).scheduleRetry(any(), any(), any(), any());
        verify(journal, never()).transition(any(), any(), any(), any(), any());
        verifyNoInteractions(repairs);
    }

    @Test
    void exhaustedRepairRequiredEntryWithAttachedContextIsNotRetriedAgain() {
        when(journal.attempts(OPERATION_ID)).thenReturn(6);
        when(journal.repairId(OPERATION_ID)).thenReturn(Optional.of(REPAIR_ID));

        service.recover(entry(OperationJournalRepository.JournalState.REPAIR_REQUIRED));

        verifyNoInteractions(repairs);
        verify(journal, never()).scheduleRetry(any(), any(), any(), any());
        verify(journal, never()).transition(any(), any(), any(), any(), any());
    }

    @Test
    void fencedRepairReconcilesRedisAndResolvesUnadmittedCreate() {
        when(journal.attempts(OPERATION_ID)).thenReturn(5);
        when(journal.repairId(OPERATION_ID)).thenReturn(Optional.of(REPAIR_ID));
        when(repairs.find(REPAIR_ID)).thenReturn(Optional.of(new ReservationRepairRepository.RepairContext(
                REPAIR_ID,
                TICKET_ITEM_ID,
                FENCE_VERSION,
                FENCE_VERSION + 1,
                new InventorySnapshot(TICKET_ITEM_ID, 10, 8, 2, 0),
                ReservationRepairRepository.RepairState.STARTED,
                "FENCE_STALE")));
        when(stock.publishFence(TICKET_ITEM_ID, FENCE_VERSION + 1, "DRAINING")).thenReturn("PUBLISHED");
        when(repairs.close(REPAIR_ID)).thenReturn(true);
        when(stock.publishFence(TICKET_ITEM_ID, FENCE_VERSION + 1, "CLOSED")).thenReturn("PUBLISHED");
        when(stock.repairMirror(REPAIR_ID, TICKET_ITEM_ID, FENCE_VERSION + 1, 10, 8, 2, 0, "REJECTED"))
                .thenReturn("REPAIRED");
        when(repairs.markVerified(REPAIR_ID, "REJECTED")).thenReturn(true);
        when(repairs.open(REPAIR_ID)).thenReturn(true);
        when(stock.publishFence(TICKET_ITEM_ID, FENCE_VERSION + 1, "OPEN")).thenReturn("PUBLISHED");
        when(repairs.complete(REPAIR_ID, "REJECTED")).thenReturn(true);

        service.recover(createEntry(OperationJournalRepository.JournalState.REPAIR_REQUIRED));

        verify(stock).repairMirror(REPAIR_ID, TICKET_ITEM_ID, FENCE_VERSION + 1, 10, 8, 2, 0, "REJECTED");
        verify(repairs).complete(REPAIR_ID, "REJECTED");
        verify(journal).transition(
                OPERATION_ID,
                OperationJournalRepository.JournalState.REPAIR_REQUIRED,
                OperationJournalRepository.JournalState.REJECTED,
                "FENCE_STALE",
                null);
    }

    @Test
    void fencedRepairPublishesDrainingClosesAndThenPublishesClosedBeforeMirror() {
        when(journal.attempts(OPERATION_ID)).thenReturn(1);
        when(journal.repairId(OPERATION_ID)).thenReturn(Optional.of(REPAIR_ID));
        when(repairs.find(REPAIR_ID)).thenReturn(Optional.of(new ReservationRepairRepository.RepairContext(
                REPAIR_ID,
                TICKET_ITEM_ID,
                FENCE_VERSION,
                FENCE_VERSION + 1,
                new InventorySnapshot(TICKET_ITEM_ID, 10, 8, 2, 0),
                ReservationRepairRepository.RepairState.STARTED,
                "FENCE_STALE")));
        when(stock.publishFence(TICKET_ITEM_ID, FENCE_VERSION + 1, "DRAINING")).thenReturn("PUBLISHED");
        when(repairs.close(REPAIR_ID)).thenReturn(true);
        when(stock.publishFence(TICKET_ITEM_ID, FENCE_VERSION + 1, "CLOSED")).thenReturn("PUBLISHED");
        when(stock.repairMirror(REPAIR_ID, TICKET_ITEM_ID, FENCE_VERSION + 1, 10, 8, 2, 0, "REJECTED"))
                .thenReturn("REPAIRED");
        when(repairs.markVerified(REPAIR_ID, "REJECTED")).thenReturn(true);
        when(repairs.open(REPAIR_ID)).thenReturn(true);
        when(stock.publishFence(TICKET_ITEM_ID, FENCE_VERSION + 1, "OPEN")).thenReturn("PUBLISHED");
        when(repairs.complete(REPAIR_ID, "REJECTED")).thenReturn(true);

        service.recover(createEntry(OperationJournalRepository.JournalState.REPAIR_REQUIRED));

        InOrder order = inOrder(stock, repairs);
        order.verify(stock).publishFence(TICKET_ITEM_ID, FENCE_VERSION + 1, "DRAINING");
        order.verify(repairs).close(REPAIR_ID);
        order.verify(stock).publishFence(TICKET_ITEM_ID, FENCE_VERSION + 1, "CLOSED");
        order.verify(stock).repairMirror(REPAIR_ID, TICKET_ITEM_ID, FENCE_VERSION + 1, 10, 8, 2, 0, "REJECTED");
    }

    @Test
    void repairedTerminalPreservesItsOriginalResultCode() {
        OperationJournalRepository.JournalEntry pending = OperationJournalRepository.JournalEntry.terminal(
                OPERATION_ID,
                RESERVATION_ID,
                OperationJournalRepository.OperationType.RELEASE,
                "a".repeat(64),
                TICKET_ITEM_ID,
                QUANTITY,
                FENCE_VERSION,
                OperationJournalRepository.JournalState.REPAIR_REQUIRED);
        pending = new OperationJournalRepository.JournalEntry(
                pending.operationId(),
                pending.reservationId(),
                pending.operationType(),
                pending.demoActorId(),
                pending.idempotencyKeyHash(),
                pending.requestFingerprint(),
                pending.ticketItemId(),
                pending.quantity(),
                pending.fenceVersion(),
                pending.state(),
                "RELEASED",
                pending.resultStockAfter());
        when(journal.repairId(OPERATION_ID)).thenReturn(Optional.of(REPAIR_ID));
        when(repairs.find(REPAIR_ID)).thenReturn(Optional.of(new ReservationRepairRepository.RepairContext(
                REPAIR_ID,
                TICKET_ITEM_ID,
                FENCE_VERSION,
                FENCE_VERSION + 1,
                new InventorySnapshot(TICKET_ITEM_ID, 10, 8, 2, 0),
                ReservationRepairRepository.RepairState.STARTED,
                "FENCE_STALE")));
        when(stock.publishFence(TICKET_ITEM_ID, FENCE_VERSION + 1, "DRAINING")).thenReturn("PUBLISHED");
        when(repairs.close(REPAIR_ID)).thenReturn(true);
        when(stock.publishFence(TICKET_ITEM_ID, FENCE_VERSION + 1, "CLOSED")).thenReturn("PUBLISHED");
        when(stock.repairMirror(REPAIR_ID, TICKET_ITEM_ID, FENCE_VERSION + 1, 10, 8, 2, 0, "COMMITTED"))
                .thenReturn("REPAIRED");
        when(repairs.markVerified(REPAIR_ID, "COMMITTED")).thenReturn(true);
        when(repairs.open(REPAIR_ID)).thenReturn(true);
        when(stock.publishFence(TICKET_ITEM_ID, FENCE_VERSION + 1, "OPEN")).thenReturn("PUBLISHED");
        when(repairs.complete(REPAIR_ID, "COMMITTED")).thenReturn(true);

        service.recover(pending);

        verify(journal).transition(
                OPERATION_ID,
                OperationJournalRepository.JournalState.REPAIR_REQUIRED,
                OperationJournalRepository.JournalState.COMMITTED,
                "RELEASED",
                null);
    }

    @Test
    void repairFailureLeavesTheRepairContextRetryable() {
        when(journal.repairId(OPERATION_ID)).thenReturn(Optional.of(REPAIR_ID));
        when(repairs.find(REPAIR_ID)).thenReturn(Optional.of(new ReservationRepairRepository.RepairContext(
                REPAIR_ID,
                TICKET_ITEM_ID,
                FENCE_VERSION,
                FENCE_VERSION + 1,
                new InventorySnapshot(TICKET_ITEM_ID, 10, 8, 2, 0),
                ReservationRepairRepository.RepairState.STARTED,
                "FENCE_STALE")));
        when(stock.publishFence(TICKET_ITEM_ID, FENCE_VERSION + 1, "DRAINING")).thenReturn("PUBLISHED");
        when(repairs.close(REPAIR_ID)).thenReturn(true);
        when(stock.publishFence(TICKET_ITEM_ID, FENCE_VERSION + 1, "CLOSED")).thenReturn("PUBLISHED");
        doThrow(new IllegalStateException("repair mirror timeout"))
                .when(stock).repairMirror(REPAIR_ID, TICKET_ITEM_ID, FENCE_VERSION + 1, 10, 8, 2, 0, "REJECTED");

        service.recover(createEntry(OperationJournalRepository.JournalState.REPAIR_REQUIRED));

        verify(repairs, never()).fail(any(), any());
        verify(journal).scheduleRetry(
                eq(OPERATION_ID),
                eq(OperationJournalRepository.JournalState.REPAIR_REQUIRED),
                eq("REPAIR_RETRY_FAILED"),
                any());
    }

    @Test
    void repairStartRetryReusesTheSameRepairIdWhenJournalAttachmentWasLost() {
        OperationJournalRepository.JournalEntry repairRequired = new OperationJournalRepository.JournalEntry(
                OPERATION_ID,
                RESERVATION_ID,
                OperationJournalRepository.OperationType.RELEASE,
                null,
                null,
                "a".repeat(64),
                TICKET_ITEM_ID,
                QUANTITY,
                FENCE_VERSION,
                OperationJournalRepository.JournalState.REPAIR_REQUIRED,
                "FENCE_STALE",
                null);
        when(repairs.start(any(), eq(TICKET_ITEM_ID), eq("FENCE_STALE")))
                .thenReturn(Optional.of(new ReservationRepairRepository.RepairContext(
                        REPAIR_ID,
                        TICKET_ITEM_ID,
                        FENCE_VERSION,
                        FENCE_VERSION + 1,
                        new InventorySnapshot(TICKET_ITEM_ID, 10, 8, 2, 0),
                        ReservationRepairRepository.RepairState.STARTED,
                        "FENCE_STALE")));
        when(stock.publishFence(TICKET_ITEM_ID, FENCE_VERSION + 1, "DRAINING")).thenReturn("PUBLISHED");
        when(journal.attachRepairId(any(), eq(OperationJournalRepository.JournalState.REPAIR_REQUIRED),
                any(), eq("FENCE_STALE"))).thenReturn(false);

        service.recover(repairRequired);
        service.recover(repairRequired);

        ArgumentCaptor<UUID> repairIds = ArgumentCaptor.forClass(UUID.class);
        verify(repairs, org.mockito.Mockito.times(2))
                .start(repairIds.capture(), eq(TICKET_ITEM_ID), eq("FENCE_STALE"));
        assertThat(repairIds.getAllValues()).hasSize(2)
                .containsOnly(repairIds.getAllValues().get(0));
    }

    @Test
    void staleReceivedCreateWithDurableReservationEntersRepairInsteadOfBeingRejected() {
        when(inventory.findFenceVersion(TICKET_ITEM_ID)).thenReturn(OptionalLong.of(FENCE_VERSION + 1));
        when(reservations.findById(RESERVATION_ID)).thenReturn(Optional.of(new Reservation(
                RESERVATION_ID,
                TICKET_ITEM_ID,
                UUID.fromString("73e4b5c9-1d67-4d3a-8db8-2e3e21c27844"),
                QUANTITY,
                ReservationStatus.RESERVED,
                Instant.now().plusSeconds(60),
                null)));

        service.recover(createEntry(OperationJournalRepository.JournalState.RECEIVED));

        verify(stock, never()).applyOnce(any(), anyLong(), anyInt(), anyLong());
        verify(journal).transition(
                OPERATION_ID,
                OperationJournalRepository.JournalState.RECEIVED,
                OperationJournalRepository.JournalState.REPAIR_REQUIRED,
                "FENCE_STALE",
                null);
    }

    @Test
    void databaseFailureAfterRecoveryApplyCompensatesFromRedisAppliedState() {
        when(stock.applyOnce(OPERATION_ID, TICKET_ITEM_ID, QUANTITY, FENCE_VERSION))
                .thenReturn(ReservationStockPort.RedisApplyResult.applied(8));
        when(inventory.decrementIfAvailable(TICKET_ITEM_ID, QUANTITY, FENCE_VERSION)).thenReturn(false);
        when(stock.compensateOnce(OPERATION_ID, TICKET_ITEM_ID, QUANTITY, FENCE_VERSION))
                .thenReturn(ReservationStockPort.RedisCompensationResult.compensated(10));
        when(journal.findByOperationId(OPERATION_ID)).thenReturn(Optional.of(
                createEntry(OperationJournalRepository.JournalState.REDIS_APPLIED)));

        service.recover(createEntry(OperationJournalRepository.JournalState.RECEIVED));

        verify(stock).compensateOnce(OPERATION_ID, TICKET_ITEM_ID, QUANTITY, FENCE_VERSION);
        verify(journal).transition(
                OPERATION_ID,
                OperationJournalRepository.JournalState.REDIS_APPLIED,
                OperationJournalRepository.JournalState.COMPENSATED,
                "DATABASE_FAILURE",
                10);
    }

    @Test
    void databaseFailureWithMissingRedisEvidenceEntersRepairBeforeCompensation() {
        when(stock.operationState(OPERATION_ID)).thenReturn(
                Optional.of(ReservationStockPort.RedisOperationState.applied(
                        TICKET_ITEM_ID, QUANTITY, FENCE_VERSION, 8)),
                Optional.empty());
        when(inventory.decrementIfAvailable(TICKET_ITEM_ID, QUANTITY, FENCE_VERSION)).thenReturn(false);
        when(journal.findByOperationId(OPERATION_ID)).thenReturn(Optional.of(
                createEntry(OperationJournalRepository.JournalState.REDIS_APPLIED)));

        service.recover(createEntry(OperationJournalRepository.JournalState.RECEIVED));

        verify(stock, never()).compensateOnce(any(), anyLong(), anyInt(), anyLong());
        verify(journal).transition(
                OPERATION_ID,
                OperationJournalRepository.JournalState.REDIS_APPLIED,
                OperationJournalRepository.JournalState.REPAIR_REQUIRED,
                "REDIS_OPERATION_STATE_MISSING",
                null);
    }

    @Test
    void recoveryCommitRaceDoesNotCompensateAfterAnotherWorkerCommitted() {
        when(stock.applyOnce(OPERATION_ID, TICKET_ITEM_ID, QUANTITY, FENCE_VERSION))
                .thenReturn(ReservationStockPort.RedisApplyResult.applied(8));
        when(inventory.decrementIfAvailable(TICKET_ITEM_ID, QUANTITY, FENCE_VERSION)).thenReturn(true);
        when(reservations.insertReserved(any(), anyLong(), any(), any())).thenReturn(true);
        when(journal.transition(
                OPERATION_ID,
                OperationJournalRepository.JournalState.REDIS_APPLIED,
                OperationJournalRepository.JournalState.COMMITTED,
                "RECOVERED",
                8)).thenReturn(false);
        when(journal.findByOperationId(OPERATION_ID)).thenReturn(Optional.of(
                createEntry(OperationJournalRepository.JournalState.COMMITTED)));

        service.recover(createEntry(OperationJournalRepository.JournalState.RECEIVED));

        verify(stock, never()).compensateOnce(any(), anyLong(), anyInt(), anyLong());
        verify(journal, never()).transition(
                eq(OPERATION_ID),
                eq(OperationJournalRepository.JournalState.REDIS_APPLIED),
                eq(OperationJournalRepository.JournalState.COMPENSATED),
                eq("DATABASE_FAILURE"),
                eq(10));
    }

    @Test
    void receivedCreateWithAppliedRedisTokenEntersRepairAfterFenceChange() {
        when(inventory.findFenceVersion(TICKET_ITEM_ID)).thenReturn(OptionalLong.of(FENCE_VERSION + 1));
        when(stock.operationState(OPERATION_ID)).thenReturn(Optional.of(
                ReservationStockPort.RedisOperationState.applied(
                        TICKET_ITEM_ID, QUANTITY, FENCE_VERSION, 8)));

        service.recover(createEntry(OperationJournalRepository.JournalState.RECEIVED));

        verify(stock, never()).applyOnce(any(), anyLong(), anyInt(), anyLong());
        verify(journal).transition(
                OPERATION_ID,
                OperationJournalRepository.JournalState.RECEIVED,
                OperationJournalRepository.JournalState.REPAIR_REQUIRED,
                "FENCE_STALE",
                null);
    }

    @Test
    void receivedCreateWithMissingTicketAndAppliedRedisTokenEntersRepair() {
        when(inventory.findFenceVersion(TICKET_ITEM_ID)).thenReturn(OptionalLong.empty());
        when(stock.operationState(OPERATION_ID)).thenReturn(Optional.of(
                ReservationStockPort.RedisOperationState.applied(
                        TICKET_ITEM_ID, QUANTITY, FENCE_VERSION, 8)));

        service.recover(createEntry(OperationJournalRepository.JournalState.RECEIVED));

        verify(stock, never()).applyOnce(any(), anyLong(), anyInt(), anyLong());
        verify(journal).transition(
                OPERATION_ID,
                OperationJournalRepository.JournalState.RECEIVED,
                OperationJournalRepository.JournalState.REPAIR_REQUIRED,
                "TICKET_ITEM_NOT_FOUND",
                null);
    }

    @Test
    void receivedCreateWithMissingRedisOperationStateRetriesApplyAtCurrentFence() {
        when(stock.operationState(OPERATION_ID)).thenReturn(Optional.empty());
        when(stock.applyOnce(OPERATION_ID, TICKET_ITEM_ID, QUANTITY, FENCE_VERSION))
                .thenReturn(ReservationStockPort.RedisApplyResult.applied(8));
        when(inventory.decrementIfAvailable(TICKET_ITEM_ID, QUANTITY, FENCE_VERSION)).thenReturn(true);
        when(reservations.insertReserved(any(), anyLong(), any(), any())).thenReturn(true);

        service.recover(createEntry(OperationJournalRepository.JournalState.RECEIVED));

        verify(stock).applyOnce(OPERATION_ID, TICKET_ITEM_ID, QUANTITY, FENCE_VERSION);
        verify(journal).transition(
                OPERATION_ID,
                OperationJournalRepository.JournalState.RECEIVED,
                OperationJournalRepository.JournalState.REDIS_APPLYING,
                "REDIS_APPLYING",
                null);
        verify(journal).transition(
                OPERATION_ID,
                OperationJournalRepository.JournalState.REDIS_APPLYING,
                OperationJournalRepository.JournalState.REDIS_APPLIED,
                "REDIS_APPLIED",
                8);
        verify(journal).transition(
                OPERATION_ID,
                OperationJournalRepository.JournalState.REDIS_APPLIED,
                OperationJournalRepository.JournalState.COMMITTED,
                "RECOVERED",
                8);
    }

    @Test
    void redisApplyingCreateWithMissingRedisOperationStateEntersRepairWithoutRetryingApply() {
        when(stock.operationState(OPERATION_ID)).thenReturn(Optional.empty());

        service.recover(createEntry(OperationJournalRepository.JournalState.REDIS_APPLYING));

        verify(stock, never()).applyOnce(any(), anyLong(), anyInt(), anyLong());
        verify(journal).transition(
                OPERATION_ID,
                OperationJournalRepository.JournalState.REDIS_APPLYING,
                OperationJournalRepository.JournalState.REPAIR_REQUIRED,
                "REDIS_OPERATION_STATE_MISSING",
                null);
    }

    @Test
    void receivedCreateWithMissingRedisOperationIdentityEntersRepairBeforeFinalizing() {
        when(stock.operationState(OPERATION_ID)).thenReturn(Optional.of(
                ReservationStockPort.RedisOperationState.applied(8)));

        service.recover(createEntry(OperationJournalRepository.JournalState.RECEIVED));

        verify(stock, never()).applyOnce(any(), anyLong(), anyInt(), anyLong());
        verify(journal).transition(
                OPERATION_ID,
                OperationJournalRepository.JournalState.RECEIVED,
                OperationJournalRepository.JournalState.REPAIR_REQUIRED,
                "REDIS_OPERATION_STATE_INVALID",
                null);
    }

    @Test
    void receivedCreateWithMismatchedRedisOperationIdentityEntersRepairBeforeFinalizing() {
        when(stock.operationState(OPERATION_ID)).thenReturn(Optional.of(
                new ReservationStockPort.RedisOperationState(
                        ReservationStockPort.RedisOperationState.Status.APPLIED,
                        8,
                        TICKET_ITEM_ID + 1,
                        QUANTITY,
                        FENCE_VERSION)));

        service.recover(createEntry(OperationJournalRepository.JournalState.RECEIVED));

        verify(stock, never()).applyOnce(any(), anyLong(), anyInt(), anyLong());
        verify(journal).transition(
                OPERATION_ID,
                OperationJournalRepository.JournalState.RECEIVED,
                OperationJournalRepository.JournalState.REPAIR_REQUIRED,
                "REDIS_OPERATION_STATE_INVALID",
                null);
    }

    @Test
    void receivedCreateWithRecordedSoldOutRedisStateRemainsRejectedOnRecovery() {
        when(stock.operationState(OPERATION_ID)).thenReturn(Optional.of(
                new ReservationStockPort.RedisOperationState(
                        ReservationStockPort.RedisOperationState.Status.SOLD_OUT,
                        8,
                        TICKET_ITEM_ID,
                        QUANTITY,
                        FENCE_VERSION)));

        service.recover(createEntry(OperationJournalRepository.JournalState.RECEIVED));

        verify(stock, never()).applyOnce(any(), anyLong(), anyInt(), anyLong());
        verify(journal).transition(
                OPERATION_ID,
                OperationJournalRepository.JournalState.RECEIVED,
                OperationJournalRepository.JournalState.REJECTED,
                "SOLD_OUT",
                8);
    }

    @Test
    void receivedCreateWithRecordedSoldOutRedisStateRemainsRejectedAfterFenceRotation() {
        when(inventory.findFenceVersion(TICKET_ITEM_ID)).thenReturn(OptionalLong.of(FENCE_VERSION + 1));
        when(stock.operationState(OPERATION_ID)).thenReturn(Optional.of(
                new ReservationStockPort.RedisOperationState(
                        ReservationStockPort.RedisOperationState.Status.SOLD_OUT,
                        8,
                        TICKET_ITEM_ID,
                        QUANTITY,
                        FENCE_VERSION)));

        service.recover(createEntry(OperationJournalRepository.JournalState.RECEIVED));

        verify(stock, never()).applyOnce(any(), anyLong(), anyInt(), anyLong());
        verify(journal).transition(
                OPERATION_ID,
                OperationJournalRepository.JournalState.RECEIVED,
                OperationJournalRepository.JournalState.REJECTED,
                "SOLD_OUT",
                8);
    }

    @Test
    void receivedCreateWithRecordedSoldOutRedisStateRemainsRejectedAfterTicketRemoval() {
        when(inventory.findFenceVersion(TICKET_ITEM_ID)).thenReturn(OptionalLong.empty());
        when(stock.operationState(OPERATION_ID)).thenReturn(Optional.of(
                new ReservationStockPort.RedisOperationState(
                        ReservationStockPort.RedisOperationState.Status.SOLD_OUT,
                        8,
                        TICKET_ITEM_ID,
                        QUANTITY,
                        FENCE_VERSION)));

        service.recover(createEntry(OperationJournalRepository.JournalState.RECEIVED));

        verify(stock, never()).applyOnce(any(), anyLong(), anyInt(), anyLong());
        verify(journal).transition(
                OPERATION_ID,
                OperationJournalRepository.JournalState.RECEIVED,
                OperationJournalRepository.JournalState.REJECTED,
                "SOLD_OUT",
                8);
    }

    @Test
    void redisAppliedCreateUsesRecordedRedisStateWithoutApplyingAgain() {
        when(stock.operationState(OPERATION_ID)).thenReturn(Optional.of(
                ReservationStockPort.RedisOperationState.applied(
                        TICKET_ITEM_ID, QUANTITY, FENCE_VERSION, 8)));
        when(inventory.decrementIfAvailable(TICKET_ITEM_ID, QUANTITY, FENCE_VERSION)).thenReturn(true);
        when(reservations.insertReserved(any(), anyLong(), any(), any())).thenReturn(true);

        service.recover(createEntry(OperationJournalRepository.JournalState.RECEIVED));

        verify(stock, never()).applyOnce(any(), anyLong(), anyInt(), anyLong());
        verify(journal).transition(
                OPERATION_ID,
                OperationJournalRepository.JournalState.RECEIVED,
                OperationJournalRepository.JournalState.REDIS_APPLIED,
                "REDIS_APPLIED",
                8);
        verify(journal).transition(
                OPERATION_ID,
                OperationJournalRepository.JournalState.REDIS_APPLIED,
                OperationJournalRepository.JournalState.COMMITTED,
                "RECOVERED",
                8);
    }

    @Test
    void repairRequiredWithoutDurableRepairContextSchedulesABoundedRetry() {
        service.recover(createEntry(OperationJournalRepository.JournalState.REPAIR_REQUIRED));

        verify(journal).scheduleRetry(
                eq(OPERATION_ID),
                eq(OperationJournalRepository.JournalState.REPAIR_REQUIRED),
                eq("REPAIR_CONTEXT_NOT_FOUND"),
                any());
    }

    @Test
    void staleCompensationAfterRecoveryApplyEntersRepairFromRedisAppliedState() {
        when(stock.applyOnce(OPERATION_ID, TICKET_ITEM_ID, QUANTITY, FENCE_VERSION))
                .thenReturn(ReservationStockPort.RedisApplyResult.applied(8));
        when(inventory.decrementIfAvailable(TICKET_ITEM_ID, QUANTITY, FENCE_VERSION)).thenReturn(false);
        when(journal.findByOperationId(OPERATION_ID)).thenReturn(Optional.of(
                createEntry(OperationJournalRepository.JournalState.REDIS_APPLIED)));
        when(stock.compensateOnce(OPERATION_ID, TICKET_ITEM_ID, QUANTITY, FENCE_VERSION))
                .thenReturn(ReservationStockPort.RedisCompensationResult.staleFence());

        service.recover(createEntry(OperationJournalRepository.JournalState.RECEIVED));

        verify(journal).transition(
                OPERATION_ID,
                OperationJournalRepository.JournalState.REDIS_APPLIED,
                OperationJournalRepository.JournalState.REPAIR_REQUIRED,
                "COMPENSATION_STALE_FENCE",
                null);
    }

    @Test
    void admittedCreateWithStaleCompensationResolvesToCompensatedAfterRepair() {
        ReservationRepairRepository.RepairContext context = new ReservationRepairRepository.RepairContext(
                REPAIR_ID,
                TICKET_ITEM_ID,
                FENCE_VERSION,
                FENCE_VERSION + 1,
                new InventorySnapshot(TICKET_ITEM_ID, 10, 8, 2, 0),
                ReservationRepairRepository.RepairState.STARTED,
                "COMPENSATION_STALE_FENCE");
        when(stock.applyOnce(OPERATION_ID, TICKET_ITEM_ID, QUANTITY, FENCE_VERSION))
                .thenReturn(ReservationStockPort.RedisApplyResult.applied(8));
        when(inventory.decrementIfAvailable(TICKET_ITEM_ID, QUANTITY, FENCE_VERSION)).thenReturn(false);
        when(journal.findByOperationId(OPERATION_ID)).thenReturn(Optional.of(
                createEntry(OperationJournalRepository.JournalState.REDIS_APPLIED)));
        when(stock.compensateOnce(OPERATION_ID, TICKET_ITEM_ID, QUANTITY, FENCE_VERSION))
                .thenReturn(ReservationStockPort.RedisCompensationResult.staleFence());
        when(journal.repairId(OPERATION_ID)).thenReturn(Optional.empty(), Optional.of(REPAIR_ID));
        when(repairs.start(any(), eq(TICKET_ITEM_ID), eq("COMPENSATION_STALE_FENCE")))
                .thenReturn(Optional.of(context));
        when(journal.attachRepairId(
                eq(OPERATION_ID),
                eq(OperationJournalRepository.JournalState.REPAIR_REQUIRED),
                eq(REPAIR_ID),
                eq("COMPENSATION_STALE_FENCE"))).thenReturn(true);
        when(repairs.find(REPAIR_ID)).thenReturn(Optional.of(context));
        when(stock.publishFence(TICKET_ITEM_ID, FENCE_VERSION + 1, "DRAINING")).thenReturn("PUBLISHED");
        when(repairs.close(REPAIR_ID)).thenReturn(true);
        when(stock.publishFence(TICKET_ITEM_ID, FENCE_VERSION + 1, "CLOSED")).thenReturn("PUBLISHED");
        when(stock.repairMirror(REPAIR_ID, TICKET_ITEM_ID, FENCE_VERSION + 1, 10, 8, 2, 0, "COMPENSATED"))
                .thenReturn("REPAIRED");
        when(repairs.markVerified(REPAIR_ID, "COMPENSATED")).thenReturn(true);
        when(repairs.open(REPAIR_ID)).thenReturn(true);
        when(stock.publishFence(TICKET_ITEM_ID, FENCE_VERSION + 1, "OPEN")).thenReturn("PUBLISHED");
        when(repairs.complete(REPAIR_ID, "COMPENSATED")).thenReturn(true);

        service.recover(createEntry(OperationJournalRepository.JournalState.RECEIVED));
        service.recover(createEntry(OperationJournalRepository.JournalState.REPAIR_REQUIRED));

        verify(journal).transition(
                OPERATION_ID,
                OperationJournalRepository.JournalState.REPAIR_REQUIRED,
                OperationJournalRepository.JournalState.COMPENSATED,
                "COMPENSATED",
                null);
    }

    private static OperationJournalRepository.JournalEntry entry(
            OperationJournalRepository.JournalState state
    ) {
        return OperationJournalRepository.JournalEntry.terminal(
                OPERATION_ID,
                RESERVATION_ID,
                OperationJournalRepository.OperationType.RELEASE,
                "a".repeat(64),
                TICKET_ITEM_ID,
                QUANTITY,
                FENCE_VERSION,
                state);
    }

    private static OperationJournalRepository.JournalEntry createEntry(
            OperationJournalRepository.JournalState state
    ) {
        return new OperationJournalRepository.JournalEntry(
                OPERATION_ID,
                RESERVATION_ID,
                UUID.fromString("73e4b5c9-1d67-4d3a-8db8-2e3e21c27844"),
                "b".repeat(64),
                "a".repeat(64),
                TICKET_ITEM_ID,
                QUANTITY,
                FENCE_VERSION,
                state,
                null,
                null);
    }
}
