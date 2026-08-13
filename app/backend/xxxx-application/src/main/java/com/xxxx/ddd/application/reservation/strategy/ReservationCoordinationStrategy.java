package com.xxxx.ddd.application.reservation.strategy;

import com.xxxx.ddd.application.reservation.CreateReservationCommand;
import com.xxxx.ddd.application.reservation.CreateReservationResult;

import java.util.Objects;

public interface ReservationCoordinationStrategy {

    ReservationStrategy strategy();

    CreateReservationResult create(CreateReservationCommand command);

    default void requireSupported(ReservationStrategy expected) {
        if (strategy() != Objects.requireNonNull(expected, "expected strategy must not be null")) {
            throw new IllegalArgumentException("strategy does not match expected comparison lane");
        }
    }
}
