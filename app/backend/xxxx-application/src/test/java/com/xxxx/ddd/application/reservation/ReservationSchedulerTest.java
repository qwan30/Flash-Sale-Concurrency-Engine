package com.xxxx.ddd.application.reservation;

import com.xxxx.ddd.application.reservation.port.OperationJournalRepository;
import com.xxxx.ddd.application.reservation.port.ReservationRepository;
import com.xxxx.ddd.domain.reservation.Reservation;
import com.xxxx.ddd.domain.reservation.ReservationStatus;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ReservationSchedulerTest {

    @Test
    void recoveryClaimsTheBoundedLeaseBatchAndDelegatesEachEntry() {
        OperationJournalRepository journal = mock(OperationJournalRepository.class);
        ReservationRecoveryService recovery = mock(ReservationRecoveryService.class);
        OperationJournalRepository.JournalEntry entry = journalEntry();
        when(journal.claimRecoverable(
                anyString(),
                eq(ReservationRecoveryService.BATCH_SIZE),
                eq(ReservationRecoveryService.LEASE)))
                .thenReturn(List.of(entry));

        new ReservationRecoveryScheduler(journal, recovery, new ReservationFixtureGate()).recover();

        verify(journal).claimRecoverable(
                anyString(),
                eq(ReservationRecoveryService.BATCH_SIZE),
                eq(ReservationRecoveryService.LEASE));
        verify(recovery).recover(entry);
    }

    @Test
    void recoverySwallowsAClaimCycleFailureSoSchedulingCanContinue() {
        OperationJournalRepository journal = mock(OperationJournalRepository.class);
        ReservationRecoveryService recovery = mock(ReservationRecoveryService.class);
        when(journal.claimRecoverable(anyString(), eq(50), eq(Duration.ofSeconds(30))))
                .thenThrow(new IllegalStateException("database unavailable"));

        assertThatCode(() -> new ReservationRecoveryScheduler(
                journal, recovery, new ReservationFixtureGate()).recover())
                .doesNotThrowAnyException();

        verifyNoInteractions(recovery);
    }

    @Test
    void expiryScansTheBoundedBatchAndUsesTheApplicationExpiryService() {
        ReservationRepository reservations = mock(ReservationRepository.class);
        ExpireReservationService expiration = mock(ExpireReservationService.class);
        Reservation due = reservation();
        when(reservations.findDueReserved(50)).thenReturn(List.of(due));

        new ReservationExpiryScheduler(
                reservations, expiration, new ReservationFixtureGate()).expireDueReservations();

        verify(reservations).findDueReserved(50);
        verify(expiration).expire(due.id());
    }

    @Test
    void expiryContinuesWithLaterReservationsWhenOneTransitionFails() {
        ReservationRepository reservations = mock(ReservationRepository.class);
        ExpireReservationService expiration = mock(ExpireReservationService.class);
        Reservation failed = reservation();
        Reservation later = reservation();
        when(reservations.findDueReserved(50)).thenReturn(List.of(failed, later));
        doThrow(new IllegalStateException("transient expiry failure"))
                .when(expiration).expire(failed.id());

        assertThatCode(() -> new ReservationExpiryScheduler(
                reservations, expiration, new ReservationFixtureGate()).expireDueReservations())
                .doesNotThrowAnyException();

        verify(expiration).expire(failed.id());
        verify(expiration).expire(later.id());
    }

    @Test
    void expirySwallowsARepositoryScanFailure() {
        ReservationRepository reservations = mock(ReservationRepository.class);
        ExpireReservationService expiration = mock(ExpireReservationService.class);
        when(reservations.findDueReserved(50))
                .thenThrow(new IllegalStateException("database unavailable"));

        assertThatCode(() -> new ReservationExpiryScheduler(
                reservations, expiration, new ReservationFixtureGate()).expireDueReservations())
                .doesNotThrowAnyException();

        verifyNoInteractions(expiration);
    }

    private static OperationJournalRepository.JournalEntry journalEntry() {
        return OperationJournalRepository.JournalEntry.terminal(
                UUID.randomUUID(),
                UUID.randomUUID(),
                OperationJournalRepository.OperationType.REPAIR,
                "request-fingerprint",
                42L,
                1,
                3L,
                OperationJournalRepository.JournalState.REPAIR_REQUIRED);
    }

    private static Reservation reservation() {
        return new Reservation(
                UUID.randomUUID(),
                42L,
                UUID.randomUUID(),
                1,
                ReservationStatus.RESERVED,
                Instant.now().plusSeconds(30),
                null);
    }
}
