package com.xxxx.ddd.application.reservation.port;

import java.util.UUID;

public interface FaultInjectionPort {

    void hit(FaultPoint point, UUID operationId);

    enum FaultPoint {
        AFTER_REDIS_BEFORE_DB,
        AFTER_DB_COMMIT_BEFORE_RESPONSE,
        REDIS_MIRROR_TIMEOUT,
        KAFKA_UNAVAILABLE,
        CONFIRM_EXPIRE_RACE
    }
}
