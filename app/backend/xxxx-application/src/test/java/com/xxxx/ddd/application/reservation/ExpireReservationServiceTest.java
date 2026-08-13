package com.xxxx.ddd.application.reservation;

import com.xxxx.ddd.application.MQ.OutboxService;
import com.xxxx.ddd.application.reservation.port.InventoryRepository;
import com.xxxx.ddd.application.reservation.port.OperationJournalRepository;
import com.xxxx.ddd.application.reservation.port.ReservationRepository;
import com.xxxx.ddd.application.reservation.port.ReservationStockPort;
import com.xxxx.ddd.domain.reservation.Reservation;
import com.xxxx.ddd.domain.reservation.ReservationStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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

class ExpireReservationServiceTest {

    private static final UUID RESERVATION_ID = UUID.fromString("e78148e2-8c5e-452c-a779-8a8ccc430e04");
    private static final UUID ACTOR_ID = UUID.fromString("b51af637-5d1a-4d19-b919-f5934e82bf18");
    private static final UUID CREATE_OPERATION_ID = UUID.fromString("a7b7e8c4-5bc1-4eb8-a8a2-88cc9e55f65c");
    private static final long TICKET_ITEM_ID = 42L;
    private static final int QUANTITY = 2;
    private static final long FENCE_VERSION = 7L;

    private final ReservationRepository reservations = mock(ReservationRepository.class);
    private final InventoryRepository inventory = mock(InventoryRepository.class);
    private final OperationJournalRepository journal = mock(OperationJournalRepository.class);
    private final ReservationStockPort stock = mock(ReservationStockPort.class);
    private final OutboxService outbox = mock(OutboxService.class);
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    private final TransactionStatus transactionStatus = mock(TransactionStatus.class);

    private ExpireReservationService service;

    @BeforeEach
    void setUp() {
        when(transactionManager.getTransaction(any(TransactionDefinition.class))).thenReturn(transactionStatus);
        when(inventory.findFenceVersion(TICKET_ITEM_ID)).thenReturn(OptionalLong.of(FENCE_VERSION));
        when(journal.transition(
                any(),
                eq(OperationJournalRepository.JournalState.MIRROR_PENDING),
                eq(OperationJournalRepository.JournalState.COMMITTED),
                eq("EXPIRED"),
                isNull())).thenReturn(true);
        when(journal.transition(
                any(),
                eq(OperationJournalRepository.JournalState.COMMITTED),
                eq(OperationJournalRepository.JournalState.MIRROR_PENDING),
                eq("EXPIRED"),
                isNull())).thenReturn(true);
        service = new ExpireReservationService(
                reservations,
                inventory,
                journal,
                stock,
                outbox,
                transactionManager);
    }

    @Test
    void expiresReservedReservationReusesTheCreateJournalOperationForMirrorRecovery() {
        Reservation reserved = reservation(ReservationStatus.RESERVED);
        Reservation expired = reservation(ReservationStatus.EXPIRED);
        when(reservations.findById(RESERVATION_ID)).thenReturn(Optional.of(reserved));
        when(journal.findByReservationId(RESERVATION_ID)).thenReturn(Optional.of(
                createJournal(OperationJournalRepository.JournalState.COMMITTED, "NEW")));
        when(reservations.transitionIfCurrent(
                eq(RESERVATION_ID),
                eq(ReservationStatus.RESERVED),
                eq(ReservationStatus.EXPIRED),
                any(),
                eq(FENCE_VERSION))).thenReturn(Optional.of(expired));
        when(journal.transition(
                eq(CREATE_OPERATION_ID),
                eq(OperationJournalRepository.JournalState.COMMITTED),
                eq(OperationJournalRepository.JournalState.MIRROR_PENDING),
                eq("EXPIRED"),
                isNull())).thenReturn(true);

        ReservationLifecycleResult result = service.expire(RESERVATION_ID);

        assertThat(result.outcome()).isEqualTo(ReservationLifecycleResult.Outcome.EXPIRED);
        assertThat(result.reservation()).contains(expired);
        assertThat(result.operationId()).contains(CREATE_OPERATION_ID);
        verify(journal).transition(
                CREATE_OPERATION_ID,
                OperationJournalRepository.JournalState.COMMITTED,
                OperationJournalRepository.JournalState.MIRROR_PENDING,
                "EXPIRED",
                null);
        verify(journal, never()).recordTerminal(any());
        verify(stock).mirrorTerminalOnce(CREATE_OPERATION_ID, TICKET_ITEM_ID, QUANTITY, FENCE_VERSION);
        verify(outbox).record(eq("Reservation"), eq(RESERVATION_ID.toString()),
                eq("reservation.expired"), any());
    }

    @Test
    void repairRequiredCreateCannotExpireBeforeFencedRepairCompletes() {
        Reservation reserved = reservation(ReservationStatus.RESERVED);
        OperationJournalRepository.JournalEntry repairRequired = createJournal(
                OperationJournalRepository.JournalState.REPAIR_REQUIRED,
                "FENCE_STALE");
        when(reservations.findById(RESERVATION_ID)).thenReturn(Optional.of(reserved));
        when(journal.findByReservationId(RESERVATION_ID)).thenReturn(Optional.of(repairRequired));

        ReservationLifecycleResult result = service.expire(RESERVATION_ID);

        assertThat(result.outcome()).isEqualTo(ReservationLifecycleResult.Outcome.REPAIR_REQUIRED);
        assertThat(result.operationId()).contains(CREATE_OPERATION_ID);
        verify(reservations, never()).transitionIfCurrent(any(), any(), any(), any(), anyLong());
        verifyNoInteractions(inventory, stock, outbox);
    }

    @Test
    void repairRequiredCreateWithoutDurableReservationCannotExpire() {
        OperationJournalRepository.JournalEntry repairRequired = createJournal(
                OperationJournalRepository.JournalState.REPAIR_REQUIRED,
                "FENCE_STALE");
        when(journal.findByReservationId(RESERVATION_ID)).thenReturn(Optional.of(repairRequired));

        ReservationLifecycleResult result = service.expire(RESERVATION_ID);

        assertThat(result.outcome()).isEqualTo(ReservationLifecycleResult.Outcome.REPAIR_REQUIRED);
        assertThat(result.operationId()).contains(CREATE_OPERATION_ID);
        verifyNoInteractions(inventory, stock, outbox);
    }

    @Test
    void duplicateExpireReturnsTheCurrentTerminalStateWithoutAnotherIncrement() {
        Reservation expired = reservation(ReservationStatus.EXPIRED);
        when(reservations.findById(RESERVATION_ID)).thenReturn(Optional.of(expired));

        ReservationLifecycleResult result = service.expire(RESERVATION_ID);

        assertThat(result.outcome()).isEqualTo(ReservationLifecycleResult.Outcome.REPLAYED);
        assertThat(result.reservation()).contains(expired);
        verify(journal).findPendingTerminal(RESERVATION_ID, OperationJournalRepository.OperationType.EXPIRE);
        verifyNoInteractions(inventory, stock, outbox);
    }

    @Test
    void duplicateExpiryRetriesTheExistingPendingMirrorOperation() {
        Reservation expired = reservation(ReservationStatus.EXPIRED);
        UUID operationId = UUID.randomUUID();
        OperationJournalRepository.JournalEntry pending = OperationJournalRepository.JournalEntry.terminal(
                operationId,
                RESERVATION_ID,
                OperationJournalRepository.OperationType.EXPIRE,
                "b".repeat(64),
                TICKET_ITEM_ID,
                QUANTITY,
                FENCE_VERSION,
                OperationJournalRepository.JournalState.MIRROR_PENDING);
        when(reservations.findById(RESERVATION_ID)).thenReturn(Optional.of(expired));
        when(journal.findPendingTerminal(RESERVATION_ID, OperationJournalRepository.OperationType.EXPIRE))
                .thenReturn(Optional.of(pending));

        ReservationLifecycleResult result = service.expire(RESERVATION_ID);

        assertThat(result.outcome()).isEqualTo(ReservationLifecycleResult.Outcome.EXPIRED);
        assertThat(result.operationId()).contains(operationId);
        verify(stock).mirrorTerminalOnce(operationId, TICKET_ITEM_ID, QUANTITY, FENCE_VERSION);
        verify(journal, never()).recordTerminal(any());
        verifyNoInteractions(inventory, outbox);
    }

    @Test
    void expiryOnReleasedReservationSurfacesAPendingReleaseMirror() {
        Reservation released = reservation(ReservationStatus.RELEASED);
        UUID operationId = UUID.randomUUID();
        OperationJournalRepository.JournalEntry pending = OperationJournalRepository.JournalEntry.terminal(
                operationId,
                RESERVATION_ID,
                OperationJournalRepository.OperationType.RELEASE,
                "d".repeat(64),
                TICKET_ITEM_ID,
                QUANTITY,
                FENCE_VERSION,
                OperationJournalRepository.JournalState.MIRROR_PENDING);
        when(reservations.findById(RESERVATION_ID)).thenReturn(Optional.of(released));
        when(journal.findPendingTerminal(RESERVATION_ID, OperationJournalRepository.OperationType.RELEASE))
                .thenReturn(Optional.of(pending));

        ReservationLifecycleResult result = service.expire(RESERVATION_ID);

        assertThat(result.outcome()).isEqualTo(ReservationLifecycleResult.Outcome.MIRROR_PENDING);
        assertThat(result.operationId()).contains(operationId);
        verifyNoInteractions(inventory, stock, outbox);
    }

    @Test
    void repairRequiredTerminalIsNotReportedAsReplaySuccess() {
        Reservation expired = reservation(ReservationStatus.EXPIRED);
        OperationJournalRepository.JournalEntry repairRequired = createJournal(
                OperationJournalRepository.JournalState.REPAIR_REQUIRED,
                "FENCE_STALE");
        when(reservations.findById(RESERVATION_ID)).thenReturn(Optional.of(expired));
        when(journal.findPendingTerminal(RESERVATION_ID, OperationJournalRepository.OperationType.EXPIRE))
                .thenReturn(Optional.of(repairRequired));

        ReservationLifecycleResult result = service.expire(RESERVATION_ID);

        assertThat(result.outcome()).isEqualTo(ReservationLifecycleResult.Outcome.REPAIR_REQUIRED);
        assertThat(result.operationId()).contains(CREATE_OPERATION_ID);
        verifyNoInteractions(inventory, stock, outbox);
    }

    @Test
    void mirrorFailureLeavesTheDurableExpiryAndJournalPendingForRecovery() {
        Reservation reserved = reservation(ReservationStatus.RESERVED);
        Reservation expired = reservation(ReservationStatus.EXPIRED);
        when(reservations.findById(RESERVATION_ID)).thenReturn(Optional.of(reserved));
        when(journal.findByReservationId(RESERVATION_ID)).thenReturn(Optional.of(
                createJournal(OperationJournalRepository.JournalState.COMMITTED, "NEW")));
        when(reservations.transitionIfCurrent(
                eq(RESERVATION_ID),
                eq(ReservationStatus.RESERVED),
                eq(ReservationStatus.EXPIRED),
                any(),
                eq(FENCE_VERSION))).thenReturn(Optional.of(expired));
        doThrow(new IllegalStateException("redis unavailable"))
                .when(stock).mirrorTerminalOnce(any(), anyLong(), anyInt(), anyLong());

        ReservationLifecycleResult result = service.expire(RESERVATION_ID);

        assertThat(result.outcome()).isEqualTo(ReservationLifecycleResult.Outcome.MIRROR_PENDING);
        assertThat(result.reservation()).contains(expired);
        verify(journal).transition(
                CREATE_OPERATION_ID,
                OperationJournalRepository.JournalState.COMMITTED,
                OperationJournalRepository.JournalState.MIRROR_PENDING,
                "EXPIRED",
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
    void losingConcurrentExpiryReadsTheConfirmedWinnerFromANewTransaction() {
        Reservation reserved = reservation(ReservationStatus.RESERVED);
        Reservation confirmed = reservation(ReservationStatus.CONFIRMED);
        when(reservations.findById(RESERVATION_ID))
                .thenReturn(Optional.of(reserved))
                .thenReturn(Optional.of(confirmed));
        when(reservations.transitionIfCurrent(
                eq(RESERVATION_ID),
                eq(ReservationStatus.RESERVED),
                eq(ReservationStatus.EXPIRED),
                any(),
                eq(FENCE_VERSION))).thenReturn(Optional.empty());

        ReservationLifecycleResult result = service.expire(RESERVATION_ID);

        assertThat(result.outcome()).isEqualTo(ReservationLifecycleResult.Outcome.CONFLICT);
        assertThat(result.reservation()).contains(confirmed);
        verify(transactionManager, times(2)).getTransaction(any(TransactionDefinition.class));
        verify(journal).findByReservationId(RESERVATION_ID);
        verifyNoInteractions(stock, outbox);
    }

    @Test
    void unresolvedReservedTransitionStaysProcessingWithoutPublishingAnEarlyExpiry() {
        Reservation reserved = reservation(ReservationStatus.RESERVED);
        when(reservations.findById(RESERVATION_ID))
                .thenReturn(Optional.of(reserved))
                .thenReturn(Optional.of(reserved));
        when(reservations.transitionIfCurrent(
                eq(RESERVATION_ID),
                eq(ReservationStatus.RESERVED),
                eq(ReservationStatus.EXPIRED),
                any(),
                eq(FENCE_VERSION))).thenReturn(Optional.empty());

        ReservationLifecycleResult result = service.expire(RESERVATION_ID);

        assertThat(result.outcome()).isEqualTo(ReservationLifecycleResult.Outcome.PROCESSING);
        verify(journal).findByReservationId(RESERVATION_ID);
        verifyNoInteractions(stock, outbox);
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
}
