package com.xxxx.ddd.application.reservation;

import com.xxxx.ddd.application.reservation.port.OperationJournalRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** Claims and processes journal rows that survived a dependency or worker crash. */
@Component
@Slf4j
@ConditionalOnProperty(
        name = "flashsale.reservation.recovery-enabled",
        havingValue = "true",
        matchIfMissing = true)
public class ReservationRecoveryScheduler {

    private final OperationJournalRepository journal;
    private final ReservationRecoveryService recovery;
    private final ReservationFixtureGate fixtureGate;
    private final String workerId = "reservation-recovery-" + UUID.randomUUID();

    public ReservationRecoveryScheduler(
            OperationJournalRepository journal,
            ReservationRecoveryService recovery,
            ReservationFixtureGate fixtureGate
    ) {
        this.journal = journal;
        this.recovery = recovery;
        this.fixtureGate = fixtureGate;
    }

    @Scheduled(fixedDelayString = "${flashsale.reservation.recovery-delay:1000}")
    public void recover() {
        try {
            fixtureGate.withReservationOperation(() -> {
                journal.claimRecoverable(
                                workerId,
                                ReservationRecoveryService.BATCH_SIZE,
                                ReservationRecoveryService.LEASE)
                        .forEach(recovery::recover);
                return null;
            });
        } catch (RuntimeException exception) {
            log.error("RESERVATION_RECOVERY: claim cycle failed", exception);
        }
    }
}
