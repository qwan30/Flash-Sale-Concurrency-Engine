package com.xxxx.ddd.chaos;

import com.xxxx.ddd.application.reservation.port.FaultInjectionPort;

import java.util.UUID;

public final class InjectedFaultException extends RuntimeException {

    private final FaultInjectionPort.FaultPoint point;
    private final UUID operationId;

    public InjectedFaultException(FaultInjectionPort.FaultPoint point, UUID operationId) {
        super("Injected reservation fault: " + point.name());
        this.point = point;
        this.operationId = operationId;
    }

    public FaultInjectionPort.FaultPoint point() {
        return point;
    }

    public UUID operationId() {
        return operationId;
    }
}
