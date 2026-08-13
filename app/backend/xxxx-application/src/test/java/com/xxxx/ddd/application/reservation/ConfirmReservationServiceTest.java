package com.xxxx.ddd.application.reservation;

import com.xxxx.ddd.application.MQ.OutboxService;
import com.xxxx.ddd.application.reservation.port.InventoryRepository;
import com.xxxx.ddd.application.reservation.port.OperationJournalRepository;
import com.xxxx.ddd.application.reservation.port.ReservationOrderRepository;
import com.xxxx.ddd.application.reservation.port.ReservationRepository;
import com.xxxx.ddd.domain.reservation.Reservation;
import com.xxxx.ddd.domain.reservation.ReservationOrder;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ConfirmReservationServiceTest {

    private static final UUID RESERVATION_ID = UUID.fromString("27e72d8d-6ca4-4dda-81cb-7ab8f24d9bc1");
    private static final UUID CREATE_OPERATION_ID = UUID.fromString("a7b7e8c4-5bc1-4eb8-a8a2-88cc9e55f65c");
    private static final UUID ORDER_ID = UUID.fromString("8b932c7e-61c5-4e8f-a254-3d9f8d3166a2");
    private static final UUID ACTOR_ID = UUID.fromString("5e0c045b-1397-43a5-98e4-23e2cda702e3");
    private static final long TICKET_ITEM_ID = 42L;
    private static final int QUANTITY = 2;
    private static final long FENCE_VERSION = 7L;

    private final ReservationRepository reservations = mock(ReservationRepository.class);
    private final InventoryRepository inventory = mock(InventoryRepository.class);
    private final OperationJournalRepository journal = mock(OperationJournalRepository.class);
    private final ReservationOrderRepository orders = mock(ReservationOrderRepository.class);
    private final OutboxService outbox = mock(OutboxService.class);
    private final ExpireReservationService expiration = mock(ExpireReservationService.class);
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    private final TransactionStatus transactionStatus = mock(TransactionStatus.class);

    private ConfirmReservationService service;

    @BeforeEach
    void setUp() {
        when(transactionManager.getTransaction(any(TransactionDefinition.class))).thenReturn(transactionStatus);
        when(inventory.findFenceVersion(TICKET_ITEM_ID)).thenReturn(OptionalLong.of(FENCE_VERSION));
        service = new ConfirmReservationService(
                reservations,
                inventory,
                journal,
                orders,
                outbox,
                expiration,
                transactionManager);
    }

    @Test
    void repairRequiredCreateCannotBeConfirmedBeforeFencedRepairCompletes() {
        Reservation reserved = reservation(ReservationStatus.RESERVED);
        OperationJournalRepository.JournalEntry repairRequired = new OperationJournalRepository.JournalEntry(
                CREATE_OPERATION_ID,
                RESERVATION_ID,
                ACTOR_ID,
                "a".repeat(64),
                "b".repeat(64),
                TICKET_ITEM_ID,
                QUANTITY,
                FENCE_VERSION,
                OperationJournalRepository.JournalState.REPAIR_REQUIRED,
                "FENCE_STALE",
                null);
        when(reservations.findById(RESERVATION_ID)).thenReturn(Optional.of(reserved));
        when(journal.findByReservationId(RESERVATION_ID)).thenReturn(Optional.of(repairRequired));

        ReservationLifecycleResult result = service.confirm(RESERVATION_ID);

        assertThat(result.outcome()).isEqualTo(ReservationLifecycleResult.Outcome.REPAIR_REQUIRED);
        assertThat(result.operationId()).contains(CREATE_OPERATION_ID);
        verify(reservations, never()).transitionIfCurrent(any(), any(), any(), any(), anyLong());
        verifyNoInteractions(inventory, orders, outbox, expiration);
    }

    @Test
    void repairRequiredCreateWithoutDurableReservationCannotBeConfirmed() {
        OperationJournalRepository.JournalEntry repairRequired = new OperationJournalRepository.JournalEntry(
                CREATE_OPERATION_ID,
                RESERVATION_ID,
                ACTOR_ID,
                "a".repeat(64),
                "b".repeat(64),
                TICKET_ITEM_ID,
                QUANTITY,
                FENCE_VERSION,
                OperationJournalRepository.JournalState.REPAIR_REQUIRED,
                "FENCE_STALE",
                null);
        when(journal.findByReservationId(RESERVATION_ID)).thenReturn(Optional.of(repairRequired));

        ReservationLifecycleResult result = service.confirm(RESERVATION_ID);

        assertThat(result.outcome()).isEqualTo(ReservationLifecycleResult.Outcome.REPAIR_REQUIRED);
        assertThat(result.operationId()).contains(CREATE_OPERATION_ID);
        verifyNoInteractions(inventory, orders, outbox, expiration);
    }

    @Test
    void confirmsReservedReservationCreatesExactlyOneOrderWithoutAnotherStockDecrement() {
        Reservation reserved = reservation(ReservationStatus.RESERVED);
        Reservation confirmed = reservation(ReservationStatus.CONFIRMED);
        ReservationOrder order = order();
        when(reservations.findById(RESERVATION_ID)).thenReturn(Optional.of(reserved));
        when(reservations.transitionIfCurrent(
                eq(RESERVATION_ID),
                eq(ReservationStatus.RESERVED),
                eq(ReservationStatus.CONFIRMED),
                any(),
                eq(FENCE_VERSION))).thenReturn(Optional.of(confirmed));
        when(orders.create(any(), eq(confirmed))).thenReturn(order);

        ReservationLifecycleResult result = service.confirm(RESERVATION_ID);

        assertThat(result.outcome()).isEqualTo(ReservationLifecycleResult.Outcome.CONFIRMED);
        assertThat(result.reservation()).contains(confirmed);
        assertThat(result.order()).contains(order);
        verify(orders).create(any(), eq(confirmed));
        verify(outbox).record(eq("Reservation"), eq(RESERVATION_ID.toString()),
                eq("reservation.confirmed"), any());
        verify(inventory, never()).decrementIfAvailable(anyLong(), anyInt(), anyLong());
    }

    @Test
    void duplicateConfirmReturnsTheExistingOrderWithoutAnotherTransition() {
        Reservation confirmed = reservation(ReservationStatus.CONFIRMED);
        ReservationOrder order = order();
        when(reservations.findById(RESERVATION_ID)).thenReturn(Optional.of(confirmed));
        when(orders.findByReservationId(RESERVATION_ID)).thenReturn(Optional.of(order));

        ReservationLifecycleResult result = service.confirm(RESERVATION_ID);

        assertThat(result.outcome()).isEqualTo(ReservationLifecycleResult.Outcome.REPLAYED);
        assertThat(result.order()).contains(order);
        verify(reservations, never()).transitionIfCurrent(any(), any(), any(), any(), anyLong());
        verifyNoInteractions(inventory, outbox, expiration);
    }

    @Test
    void losingConcurrentConfirmReplaysTheOrderCreatedByTheWinner() {
        Reservation reserved = reservation(ReservationStatus.RESERVED);
        Reservation confirmed = reservation(ReservationStatus.CONFIRMED);
        ReservationOrder order = order();
        when(reservations.findById(RESERVATION_ID))
                .thenReturn(Optional.of(reserved))
                .thenReturn(Optional.of(confirmed));
        when(reservations.transitionIfCurrent(
                eq(RESERVATION_ID),
                eq(ReservationStatus.RESERVED),
                eq(ReservationStatus.CONFIRMED),
                any(),
                eq(FENCE_VERSION))).thenReturn(Optional.empty());
        when(orders.findByReservationId(RESERVATION_ID)).thenReturn(Optional.of(order));

        ReservationLifecycleResult result = service.confirm(RESERVATION_ID);

        assertThat(result.outcome()).isEqualTo(ReservationLifecycleResult.Outcome.REPLAYED);
        assertThat(result.reservation()).contains(confirmed);
        assertThat(result.order()).contains(order);
        verify(transactionManager, times(2)).getTransaction(any(TransactionDefinition.class));
        verifyNoInteractions(outbox, expiration);
    }

    @Test
    void confirmAtOrAfterExpiryReturnsLateConflictAfterDurablyExpiringTheReservation() {
        Reservation reserved = reservation(ReservationStatus.RESERVED);
        Reservation expired = reservation(ReservationStatus.EXPIRED);
        UUID mirrorOperationId = UUID.fromString("e4f4e2f6-f3b8-4ae9-9a3e-f564022b9ddd");
        when(reservations.findById(RESERVATION_ID)).thenReturn(Optional.of(reserved));
        when(reservations.transitionIfCurrent(
                eq(RESERVATION_ID),
                eq(ReservationStatus.RESERVED),
                eq(ReservationStatus.CONFIRMED),
                any(),
                eq(FENCE_VERSION))).thenReturn(Optional.empty());
        when(expiration.expire(RESERVATION_ID)).thenReturn(new ReservationLifecycleResult(
                ReservationLifecycleResult.Outcome.EXPIRED,
                Optional.of(expired),
                Optional.empty(),
                Optional.of(mirrorOperationId)));

        ReservationLifecycleResult result = service.confirm(RESERVATION_ID);

        assertThat(result.outcome()).isEqualTo(ReservationLifecycleResult.Outcome.LATE_CONFLICT);
        assertThat(result.reservation()).contains(expired);
        assertThat(result.operationId()).contains(mirrorOperationId);
        verify(expiration).expire(RESERVATION_ID);
        verifyNoInteractions(orders, outbox);
    }

    @Test
    void confirmAtOrAfterExpiryPropagatesPendingMirrorState() {
        Reservation reserved = reservation(ReservationStatus.RESERVED);
        Reservation expired = reservation(ReservationStatus.EXPIRED);
        UUID mirrorOperationId = UUID.randomUUID();
        when(reservations.findById(RESERVATION_ID)).thenReturn(Optional.of(reserved));
        when(reservations.transitionIfCurrent(
                eq(RESERVATION_ID),
                eq(ReservationStatus.RESERVED),
                eq(ReservationStatus.CONFIRMED),
                any(),
                eq(FENCE_VERSION))).thenReturn(Optional.empty());
        when(expiration.expire(RESERVATION_ID)).thenReturn(new ReservationLifecycleResult(
                ReservationLifecycleResult.Outcome.MIRROR_PENDING,
                Optional.of(expired),
                Optional.empty(),
                Optional.of(mirrorOperationId)));

        ReservationLifecycleResult result = service.confirm(RESERVATION_ID);

        assertThat(result.outcome()).isEqualTo(ReservationLifecycleResult.Outcome.MIRROR_PENDING);
        assertThat(result.operationId()).contains(mirrorOperationId);
        verify(expiration).expire(RESERVATION_ID);
        verifyNoInteractions(orders, outbox);
    }

    @Test
    void confirmOnExpiredReservationPropagatesAnExistingPendingExpiryMirror() {
        Reservation expired = reservation(ReservationStatus.EXPIRED);
        UUID mirrorOperationId = UUID.randomUUID();
        when(reservations.findById(RESERVATION_ID)).thenReturn(Optional.of(expired));
        when(expiration.expire(RESERVATION_ID)).thenReturn(new ReservationLifecycleResult(
                ReservationLifecycleResult.Outcome.MIRROR_PENDING,
                Optional.of(expired),
                Optional.empty(),
                Optional.of(mirrorOperationId)));

        ReservationLifecycleResult result = service.confirm(RESERVATION_ID);

        assertThat(result.outcome()).isEqualTo(ReservationLifecycleResult.Outcome.MIRROR_PENDING);
        assertThat(result.operationId()).contains(mirrorOperationId);
        verify(expiration).expire(RESERVATION_ID);
        verifyNoInteractions(orders, outbox);
    }

    @Test
    void confirmOnReleasedReservationSurfacesAPendingReleaseMirror() {
        Reservation released = reservation(ReservationStatus.RELEASED);
        UUID operationId = UUID.randomUUID();
        OperationJournalRepository.JournalEntry pending = OperationJournalRepository.JournalEntry.terminal(
                operationId,
                RESERVATION_ID,
                OperationJournalRepository.OperationType.RELEASE,
                "c".repeat(64),
                TICKET_ITEM_ID,
                QUANTITY,
                FENCE_VERSION,
                OperationJournalRepository.JournalState.MIRROR_PENDING);
        when(reservations.findById(RESERVATION_ID)).thenReturn(Optional.of(released));
        when(journal.findPendingTerminal(RESERVATION_ID, OperationJournalRepository.OperationType.RELEASE))
                .thenReturn(Optional.of(pending));

        ReservationLifecycleResult result = service.confirm(RESERVATION_ID);

        assertThat(result.outcome()).isEqualTo(ReservationLifecycleResult.Outcome.MIRROR_PENDING);
        assertThat(result.operationId()).contains(operationId);
        verifyNoInteractions(orders, outbox, expiration);
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

    private static ReservationOrder order() {
        return new ReservationOrder(
                ORDER_ID,
                RESERVATION_ID,
                TICKET_ITEM_ID,
                ACTOR_ID,
                QUANTITY,
                Instant.now());
    }
}
