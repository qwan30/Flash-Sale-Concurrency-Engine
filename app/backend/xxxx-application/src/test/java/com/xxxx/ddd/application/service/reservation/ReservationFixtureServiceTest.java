package com.xxxx.ddd.application.service.reservation;

import com.xxxx.ddd.application.reservation.ReservationFixtureResetRequest;
import com.xxxx.ddd.application.reservation.ReservationFixtureResult;
import com.xxxx.ddd.application.reservation.ReservationFixtureGate;
import com.xxxx.ddd.application.reservation.ReservationFixtureEvidence;
import com.xxxx.ddd.application.reservation.port.ReservationFixtureCache;
import com.xxxx.ddd.application.reservation.port.ReservationFixtureRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReservationFixtureServiceTest {

    @Mock
    private ReservationFixtureRepository repository;

    @Mock
    private ReservationFixtureCache cache;

    @Test
    void resetSeedsRedisFromDurableStateAndReturnsParityProof() {
        ReservationFixtureRepository.DurableState durable =
                new ReservationFixtureRepository.DurableState(4L, 1000, 1000, 0, 0, 0, "OPEN");
        when(repository.reset(4L, 1000)).thenReturn(durable);
        when(cache.read(4L)).thenReturn(new ReservationFixtureCache.CacheState(
                4L, 1000, 1000, 0, 0, 0, "OPEN"));

        ReservationFixtureResult result = new ReservationFixtureService(repository, cache, new ReservationFixtureGate()).reset(
                new ReservationFixtureResetRequest(4L, 1000, "REDIS_FIRST", true));

        assertThat(result.success()).isTrue();
        assertThat(result.reservationFixtureReset()).isTrue();
        assertThat(result.reservationStockAfter()).isEqualTo(1000);
        assertThat(result.reservationRedisStockAfter()).isEqualTo(1000);
        verify(cache).reset(eq(4L), eq(1000), eq(1000), eq(0), eq(0), eq(0L), eq("OPEN"));
    }

    @Test
    void resetRejectsRequestsThatDoNotDeclareAReservationFixture() {
        ReservationFixtureService service = new ReservationFixtureService(repository, cache, new ReservationFixtureGate());

        assertThatThrownBy(() -> service.reset(
                new ReservationFixtureResetRequest(4L, 1000, "REDIS_FIRST", false)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("reservationFixture must be true");
    }

    @Test
    void resetFailsClosedWhenRedisDoesNotMatchDurableState() {
        when(repository.reset(4L, 1000)).thenReturn(
                new ReservationFixtureRepository.DurableState(4L, 1000, 1000, 0, 0, 0, "OPEN"));
        when(cache.read(4L)).thenReturn(
                new ReservationFixtureCache.CacheState(4L, 1000, 999, 1, 0, 0, "OPEN"));

        ReservationFixtureResult result = new ReservationFixtureService(repository, cache, new ReservationFixtureGate()).reset(
                new ReservationFixtureResetRequest(4L, 1000, "MYSQL_CONDITIONAL", true));

        assertThat(result.success()).isFalse();
        assertThat(result.reservationFixtureReset()).isFalse();
        assertThat(result.message()).contains("diverged");
    }

    @Test
    void evidenceCombinesDurableJournalOutboxAndRedisState() {
        when(repository.evidence(4L)).thenReturn(new ReservationFixtureRepository.EvidenceState(
                4L, 1000, 975, 25, 0, 0, "OPEN", 0, 0, 0.0, 0, 0));
        when(cache.readEvidence(4L)).thenReturn(new ReservationFixtureCache.EvidenceState(
                4L, 1000, 975, 25, 0, 0, "OPEN"));

        ReservationFixtureEvidence evidence = new ReservationFixtureService(
                repository, cache, new ReservationFixtureGate()).evidence(4L);

        assertThat(evidence.ticketItemId()).isEqualTo(4L);
        assertThat(evidence.acceptedUnits()).isEqualTo(25L);
        assertThat(evidence.pendingJournal()).isZero();
        assertThat(evidence.pendingOutbox()).isZero();
        assertThat(evidence.invariantPass()).isTrue();
        assertThat(evidence.parityPass()).isTrue();
        assertThat(evidence.finalDriftUnits()).isZero();
    }
}
