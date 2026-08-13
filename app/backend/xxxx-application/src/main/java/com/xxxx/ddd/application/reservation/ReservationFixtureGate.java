package com.xxxx.ddd.application.reservation;

import org.springframework.stereotype.Component;

import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;

/**
 * Coordinates local reservation traffic with the destructive benchmark-fixture reset.
 *
 * <p>The gate is intentionally process-local. It closes the reset window for the HTTP
 * reservation paths and the in-process recovery/expiry schedulers; it is not a distributed
 * lease and therefore cannot certify a multi-replica reset.
 */
@Component
public class ReservationFixtureGate {

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock(true);

    public <T> T withReservationOperation(Supplier<T> operation) {
        lock.readLock().lock();
        try {
            return operation.get();
        } finally {
            lock.readLock().unlock();
        }
    }

    public <T> T withFixtureReset(Supplier<T> operation) {
        lock.writeLock().lock();
        try {
            return operation.get();
        } finally {
            lock.writeLock().unlock();
        }
    }
}
