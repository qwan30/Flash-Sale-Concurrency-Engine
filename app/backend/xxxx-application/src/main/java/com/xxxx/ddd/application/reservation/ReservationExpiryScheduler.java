package com.xxxx.ddd.application.reservation;

import com.xxxx.ddd.application.reservation.port.ReservationRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Converts due RESERVED rows through the same expiry service used by the HTTP path. */
@Component
@Slf4j
@ConditionalOnProperty(
        name = "flashsale.reservation.expiry-enabled",
        havingValue = "true",
        matchIfMissing = true)
public class ReservationExpiryScheduler {

    private static final int BATCH_SIZE = 50;

    private final ReservationRepository reservations;
    private final ExpireReservationService expiration;
    private final ReservationFixtureGate fixtureGate;

    public ReservationExpiryScheduler(
            ReservationRepository reservations,
            ExpireReservationService expiration,
            ReservationFixtureGate fixtureGate
    ) {
        this.reservations = reservations;
        this.expiration = expiration;
        this.fixtureGate = fixtureGate;
    }

    @Scheduled(fixedDelayString = "${flashsale.reservation.expiry-delay:1000}")
    public void expireDueReservations() {
        try {
            fixtureGate.withReservationOperation(() -> {
                reservations.findDueReserved(BATCH_SIZE).forEach(reservation -> {
                    try {
                        expiration.expire(reservation.id());
                    } catch (RuntimeException exception) {
                        log.warn("RESERVATION_EXPIRY: reservation={} failed", reservation.id(), exception);
                    }
                });
                return null;
            });
        } catch (RuntimeException exception) {
            log.error("RESERVATION_EXPIRY: due reservation scan failed", exception);
        }
    }
}
