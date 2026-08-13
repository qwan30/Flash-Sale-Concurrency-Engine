package com.xxxx.ddd.application.reservation.port;

import java.util.UUID;

public final class NoOpFaultInjection implements FaultInjectionPort {

    @Override
    public void hit(FaultPoint point, UUID operationId) {
        // Intentionally empty: fault injection is opt-in and profile-gated.
    }
}
