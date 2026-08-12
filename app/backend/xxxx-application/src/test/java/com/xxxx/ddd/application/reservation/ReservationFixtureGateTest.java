package com.xxxx.ddd.application.reservation;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class ReservationFixtureGateTest {

    @Test
    void fixtureResetWaitsForInFlightReservationOperation() throws Exception {
        ReservationFixtureGate gate = new ReservationFixtureGate();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch operationEntered = new CountDownLatch(1);
        CountDownLatch releaseOperation = new CountDownLatch(1);
        CountDownLatch resetEntered = new CountDownLatch(1);
        try {
            var operation = executor.submit(() -> gate.withReservationOperation(() -> {
                operationEntered.countDown();
                await(releaseOperation);
                return null;
            }));

            assertThat(operationEntered.await(1, TimeUnit.SECONDS)).isTrue();

            var reset = executor.submit(() -> gate.withFixtureReset(() -> {
                resetEntered.countDown();
                return null;
            }));

            assertThat(resetEntered.await(150, TimeUnit.MILLISECONDS)).isFalse();
            releaseOperation.countDown();
            operation.get(1, TimeUnit.SECONDS);
            reset.get(1, TimeUnit.SECONDS);
            assertThat(resetEntered.await(1, TimeUnit.SECONDS)).isTrue();
        } finally {
            releaseOperation.countDown();
            executor.shutdownNow();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(1, TimeUnit.SECONDS)) {
                throw new AssertionError("reservation operation was not released");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("reservation operation was interrupted", exception);
        }
    }
}
