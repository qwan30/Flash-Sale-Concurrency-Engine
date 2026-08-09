package com.xxxx.ddd.application.reservation.port;

import java.time.Duration;

public interface ReservationTelemetryPort {

    void record(String operation, String outcome, String reason, Duration duration);
}
