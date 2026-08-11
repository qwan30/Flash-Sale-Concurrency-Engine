package com.xxxx.ddd.application.reservation;

import com.xxxx.ddd.application.MQ.OutboxService;
import com.xxxx.ddd.application.reservation.port.FaultInjectionPort;
import com.xxxx.ddd.application.reservation.port.InventoryRepository;
import com.xxxx.ddd.application.reservation.port.OperationJournalRepository;
import com.xxxx.ddd.application.reservation.port.ReservationRepository;
import com.xxxx.ddd.application.reservation.port.ReservationStockPort;
import com.xxxx.ddd.domain.reservation.Reservation;
import com.xxxx.ddd.domain.reservation.ReservationStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

import java.time.Instant;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ReleaseReservationServiceTest {

    private static final UUID RESERVATION_ID = UUID.fromString("2f03a82c-ab45-4f87-b8ea-3d407198f5c2");
    private static final UUID ACTOR_ID = UUID.fromString("bf3ac19e-3142-4d8a-b1a7-937742d86995");
    private static final UUID CREATE_OPERATION_ID = UUID.fromString("d7d5d5b4-2e3c-4cc3-bbcb-01b4e1f01f42");
    private static final long TICKET_ITEM_ID = 42L;
    private static final int QUANTITY = 2;
    private static final long FENCE_VERSION = 7L;

    private final ReservationRepository reservations = mock(ReservationRepository.class);
    private final InventoryRepository inventory = mock(InventoryRepository.class);
    private final OperationJournalRepository journal = mock(OperationJournalRepository.class);
    private final ReservationStockPort stock = mock(ReservationStockPort.class);
    private final OutboxService outbox = mock(OutboxService.class);
    private final ExpireReservationService expiration = mock(ExpireReservationService.class);
    private final FaultInjectionPort faults = mock(FaultInjectionPort.class);
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    private final TransactionStatus transactionStatus = mock(TransactionStatus.class);

    private ReleaseReservationService service;

    @BeforeEach
    void setUp() {
        when(transactionManager.getTransaction(any(TransactionDefinition.class))).thenReturn(transactionStatus);
        when(inventory.findFenceVersion(TICKET_ITEM_ID)).thenReturn(OptionalLong.of(FENCE_VERSION));
        when(journal.transition(
                any(),
                eq(OperationJournalRepository.JournalState.MIRROR_PENDING),
                eq(OperationJournalRepository.JournalState.COMMITTED),
                eq("RELEASED"),
                isNull())).thenReturn(true);
        when(journal.transition(
                any(),
                eq(OperationJournalRepository.JournalState.COMMITTED),
                eq(OperationJournalRepository.JournalState.MIRROR_PENDING),
                eq("RELEASED"),
                isNull())).thenReturn(true);
        service = new ReleaseReservationService(
                reservations,
                inventory,
                journal,
                stock,
                outbox,
                expiration,
                transactionManager);
        service.setFaultInjectionPort(faults);
    }

    @Test
    void releasesReservedReservationReusesTheCreateJournalOperationForMirrorRecovery() {
        Reservation reserved = reservation(ReservationStatus.RESERVED);
        Reservation released = reservation(ReservationStatus.RELEASED);
        when(reservations.findById(RESERVATION_ID)).thenReturn(Optional.of(reserved));
        when(journal.findByReservationId(RESERVATION_ID)).thenReturn(Optional.of(
                createJournal(OperationJournalRepository.JournalState.COMMITTED, "NEW")));
        when(reservations.transitionIfCurrent(
                eq(RESERVATION_ID),
                eq(ReservationStatus.RESERVED),
                eq(ReservationStatus.RELEASED),
                any(),
                eq(FENCE_VERSION))).thenReturn(Optional.of(released));
        when(journal.transition(
                eq(CREATE_OPERATION_ID),
                eq(OperationJournalRepository.JournalState.COMMITTED),
                eq(OperationJournalRepository.JournalState.MIRROR_PENDING),
                eq("RELEASED"),
                isNull())).thenReturn(true);

        ReservationLifecycleResult result = service.release(RESERVATION_ID);

        assertThat(result.outcome()).isEqualTo(ReservationLifecycleResult.Outcome.RELEASED);
        assertThat(result.reservation()).contains(released);
        assertThat(result.operationId()).contains(CREATE_OPERATION_ID);
        verify(journal).transition(
                CREATE_OPERATION_ID,
                OperationJournalRepository.JournalState.COMMITTED,
                OperationJournalRepository.JournalState.MIRROR_PENDING,
                "RELEASED",
                null);
        verify(journal, never()).recordTerminal(any());
        verify(stock).mirrorTerminalOnce(CREATE_OPERATION_ID, TICKET_ITEM_ID, QUANTITY, FENCE_VERSION);
        verify(outbox).record(eq("Reservation"), eq(RESERVATION_ID.toString()),
                eq("reservation.released"), any());
    }

    @Test
    void duplicateReleaseReturnsCurrentStateWithoutAnotherStockRestoreOrMirror() {
        Reservation released = reservation(ReservationStatus.RELEASED);
        when(reservations.findById(RESERVATION_ID)).thenReturn(Optional.of(released));

        ReservationLifecycleResult result = service.release(RESERVATION_ID);

        assertThat(result.outcome()).isEqualTo(ReservationLifecycleResult.Outcome.REPLAYED);
        assertThat(result.reservation()).contains(released);
        verify(journal).findPendingTerminal(RESERVATION_ID, OperationJournalRepository.OperationType.RELEASE);
        verifyNoInteractions(inventory, stock, outbox, expiration);
    }

    @Test
    void repairRequiredCreateCannotBeReleasedBeforeFencedRepairCompletes() {
        Reservation reserved = reservation(ReservationStatus.RESERVED);
        OperationJournalRepository.JournalEntry repairRequired = createJournal(
                OperationJournalRepository.JournalState.REPAIR_REQUIRED,
                "FENCE_STALE");
        when(reservations.findById(RESERVATION_ID)).thenReturn(Optional.of(reserved));
        when(journal.findByReservationId(RESERVATION_ID)).thenReturn(Optional.of(repairRequired));

        ReservationLifecycleResult result = service.release(RESERVATION_ID);

        assertThat(result.outcome()).isEqualTo(ReservationLifecycleResult.Outcome.REPAIR_REQUIRED);
        assertThat(result.operationId()).contains(CREATE_OPERATION_ID);
        verify(reservations, never()).transitionIfCurrent(any(), any(), any(), any(), anyLong());
        verifyNoInteractions(inventory, stock, outbox, expiration);
    }

    @Test
    void losingConcurrentReleaseReadsTheWinnerStateFromANewTransaction() {
        Reservation reserved = reservation(ReservationStatus.RESERVED);
        Reservation released = reservation(ReservationStatus.RELEASED);
        when(reservations.findById(RESERVATION_ID))
                .thenReturn(Optional.of(reserved))
                .thenReturn(Optional.of(released));
        when(reservations.transitionIfCurrent(
                eq(RESERVATION_ID),
                eq(ReservationStatus.RESERVED),
                eq(ReservationStatus.RELEASED),
                any(),
                eq(FENCE_VERSION))).thenReturn(Optional.empty());

        ReservationLifecycleResult result = service.release(RESERVATION_ID);

        assertThat(result.outcome()).isEqualTo(ReservationLifecycleResult.Outcome.REPLAYED);
        assertThat(result.reservation()).contains(released);
        verify(transactionManager, times(2)).getTransaction(any(TransactionDefinition.class));
        verify(journal).findPendingTerminal(RESERVATION_ID, OperationJournalRepository.OperationType.RELEASE);
        verifyNoInteractions(stock, outbox, expiration);
    }

    @Test
    void duplicateReleaseRetriesTheExistingPendingMirrorOperation() {
        Reservation released = reservation(ReservationStatus.RELEASED);
        UUID operationId = UUID.randomUUID();
        OperationJournalRepository.JournalEntry pending = OperationJournalRepository.JournalEntry.terminal(
                operationId,
                RESERVATION_ID,
                OperationJournalRepository.OperationType.RELEASE,
                "a".repeat(64),
                TICKET_ITEM_ID,
                QUANTITY,
                FENCE_VERSION,
                OperationJournalRepository.JournalState.MIRROR_PENDING);
        when(reservations.findById(RESERVATION_ID)).thenReturn(Optional.of(released));
        when(journal.findPendingTerminal(RESERVATION_ID, OperationJournalRepository.OperationType.RELEASE))
                .thenReturn(Optional.of(pending));

        ReservationLifecycleResult result = service.release(RESERVATION_ID);

        assertThat(result.outcome()).isEqualTo(ReservationLifecycleResult.Outcome.RELEASED);
        assertThat(result.operationId()).contains(operationId);
        verify(stock).mirrorTerminalOnce(operationId, TICKET_ITEM_ID, QUANTITY, FENCE_VERSION);
        verify(journal, never()).recordTerminal(any());
        verifyNoInteractions(inventory, outbox, expiration);
    }

    @Test
    void repairRequiredTerminalIsNotReportedAsReplaySuccess() {
        Reservation released = reservation(ReservationStatus.RELEASED);
        OperationJournalRepository.JournalEntry repairRequired = createJournal(
                OperationJournalRepository.JournalState.REPAIR_REQUIRED,
                "FENCE_STALE");
        when(reservations.findById(RESERVATION_ID)).thenReturn(Optional.of(released));
        when(journal.findPendingTerminal(RESERVATION_ID, OperationJournalRepository.OperationType.RELEASE))
                .thenReturn(Optional.of(repairRequired));

        ReservationLifecycleResult result = service.release(RESERVATION_ID);

        assertThat(result.outcome()).isEqualTo(ReservationLifecycleResult.Outcome.REPAIR_REQUIRED);
        assertThat(result.operationId()).contains(CREATE_OPERATION_ID);
        verifyNoInteractions(inventory, stock, outbox, expiration);
    }

    @Test
    void releaseOnExpiredReservationPropagatesAnExistingPendingExpiryMirror() {
        Reservation expired = reservation(ReservationStatus.EXPIRED);
        UUID operationId = UUID.randomUUID();
        when(reservations.findById(RESERVATION_ID)).thenReturn(Optional.of(expired));
        when(expiration.expire(RESERVATION_ID)).thenReturn(new ReservationLifecycleResult(
                ReservationLifecycleResult.Outcome.MIRROR_PENDING,
                Optional.of(expired),
                Optional.empty(),
                Optional.of(operationId)));

        ReservationLifecycleResult result = service.release(RESERVATION_ID);

        assertThat(result.outcome()).isEqualTo(ReservationLifecycleResult.Outcome.MIRROR_PENDING);
        assertThat(result.operationId()).contains(operationId);
        verify(expiration).expire(RESERVATION_ID);
        verifyNoInteractions(inventory, stock, outbox);
    }

    @Test
    void mirrorFailureLeavesTheDurableReleaseAndJournalPendingForRecovery() {
        Reservation reserved = reservation(ReservationStatus.RESERVED);
        Reservation released = reservation(ReservationStatus.RELEASED);
        when(reservations.findById(RESERVATION_ID)).thenReturn(Optional.of(reserved));
        when(journal.findByReservationId(RESERVATION_ID)).thenReturn(Optional.of(
                createJournal(OperationJournalRepository.JournalState.COMMITTED, "NEW")));
        when(reservations.transitionIfCurrent(
                eq(RESERVATION_ID),
                eq(ReservationStatus.RESERVED),
                eq(ReservationStatus.RELEASED),
                any(),
                eq(FENCE_VERSION))).thenReturn(Optional.of(released));
        doThrow(new IllegalStateException("redis unavailable"))
                .when(stock).mirrorTerminalOnce(any(), anyLong(), anyInt(), anyLong());

        ReservationLifecycleResult result = service.release(RESERVATION_ID);

        assertThat(result.outcome()).isEqualTo(ReservationLifecycleResult.Outcome.MIRROR_PENDING);
        assertThat(result.reservation()).contains(released);
        verify(journal).transition(
                CREATE_OPERATION_ID,
                OperationJournalRepository.JournalState.COMMITTED,
                OperationJournalRepository.JournalState.MIRROR_PENDING,
                "RELEASED",
                null);
        verify(journal, never()).recordTerminal(any());
        verify(journal, never()).transition(
                any(),
                eq(OperationJournalRepository.JournalState.MIRROR_PENDING),
                eq(OperationJournalRepository.JournalState.COMMITTED),
                any(),
                any());
    }

    @Test
    void repairRequiredCreateWithoutDurableReservationCannotBeReleased() {
        OperationJournalRepository.JournalEntry repairRequired = createJournal(
                OperationJournalRepository.JournalState.REPAIR_REQUIRED,
                "FENCE_STALE");
        when(journal.findByReservationId(RESERVATION_ID)).thenReturn(Optional.of(repairRequired));

        ReservationLifecycleResult result = service.release(RESERVATION_ID);

        assertThat(result.outcome()).isEqualTo(ReservationLifecycleResult.Outcome.REPAIR_REQUIRED);
        assertThat(result.operationId()).contains(CREATE_OPERATION_ID);
        verifyNoInteractions(inventory, stock, outbox, expiration);
    }

    @Test
    void configuredMirrorTimeoutUsesTheSamePendingRecoveryBoundary() {
        Reservation reserved = reservation(ReservationStatus.RESERVED);
        Reservation released = reservation(ReservationStatus.RELEASED);
        when(reservations.findById(RESERVATION_ID)).thenReturn(Optional.of(reserved));
        when(journal.findByReservationId(RESERVATION_ID)).thenReturn(Optional.of(
                createJournal(OperationJournalRepository.JournalState.COMMITTED, "NEW")));
        when(reservations.transitionIfCurrent(
                eq(RESERVATION_ID),
                eq(ReservationStatus.RESERVED),
                eq(ReservationStatus.RELEASED),
                any(),
                eq(FENCE_VERSION))).thenReturn(Optional.of(released));
        doThrow(new IllegalStateException("injected mirror timeout"))
                .when(faults).hit(eq(FaultInjectionPort.FaultPoint.REDIS_MIRROR_TIMEOUT), any());

        ReservationLifecycleResult result = service.release(RESERVATION_ID);

        assertThat(result.outcome()).isEqualTo(ReservationLifecycleResult.Outcome.MIRROR_PENDING);
        verify(faults).hit(
                eq(FaultInjectionPort.FaultPoint.REDIS_MIRROR_TIMEOUT),
                eq(result.operationId().orElseThrow()));
        verify(stock, never()).mirrorTerminalOnce(any(), anyLong(), anyInt(), anyLong());
    }

    private static OperationJournalRepository.JournalEntry createJournal(
            OperationJournalRepository.JournalState state,
            String resultCode
    ) {
        return new OperationJournalRepository.JournalEntry(
                CREATE_OPERATION_ID,
                RESERVATION_ID,
                ACTOR_ID,
                "a".repeat(64),
                "b".repeat(64),
                TICKET_ITEM_ID,
                QUANTITY,
                FENCE_VERSION,
                state,
                resultCode,
                null);
    }

    private static Reservation reservation(ReservationStatus status) {
        return new Reservation(
                RESERVATION_ID,
                TICKET_ITEM_ID,
                ACTOR_ID,
                QUANTITY,
                status,
                Instant.now().plusSeconds(120),
                status == ReservationStatus.RESERVED ? null : Instant.now());
    }
}
