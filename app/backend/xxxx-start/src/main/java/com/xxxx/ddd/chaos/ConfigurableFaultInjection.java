package com.xxxx.ddd.chaos;

import com.xxxx.ddd.application.reservation.port.FaultInjectionPort;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

@Component
@Profile("chaos")
public final class ConfigurableFaultInjection implements FaultInjectionPort {

    private final AtomicReference<FaultPoint> active = new AtomicReference<>();

    @Override
    public void hit(FaultPoint point, UUID operationId) {
        if (point == active.get()) {
            throw new InjectedFaultException(point, operationId);
        }
    }

    public List<FaultPoint> catalog() {
        return List.of(FaultPoint.values());
    }

    public FaultPoint active() {
        return active.get();
    }

    public void activate(FaultPoint point) {
        active.set(point);
    }

    public void clear() {
        active.set(null);
    }
}
