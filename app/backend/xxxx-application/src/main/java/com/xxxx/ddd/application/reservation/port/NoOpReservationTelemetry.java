package com.xxxx.ddd.application.reservation.port;

import java.time.Duration;

public final class NoOpReservationTelemetry implements ReservationTelemetryPort {

    @Override
    public void record(String operation, String outcome, String reason, Duration duration) {
        // Intentionally empty: the default adapter must not create observable side effects.
    }
}
