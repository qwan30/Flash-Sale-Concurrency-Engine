package com.xxxx.ddd.domain.reservation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ReservationTest {

    @ParameterizedTest
    @CsvSource({
            "RESERVED,CONFIRMED,true",
            "RESERVED,RELEASED,true",
            "RESERVED,EXPIRED,true",
            "CONFIRMED,RELEASED,false",
            "RELEASED,CONFIRMED,false",
            "EXPIRED,CONFIRMED,false"
    })
    void enforcesTransitionMatrix(ReservationStatus from, ReservationStatus to, boolean allowed) {
        assertThat(ReservationTransition.canTransition(from, to)).isEqualTo(allowed);
    }

    @Test
    void inventorySnapshotReportsWhetherAccountingBalances() {
        InventorySnapshot balanced = new InventorySnapshot(42L, 10, 4, 3, 3);
        InventorySnapshot drifted = new InventorySnapshot(42L, 10, 5, 3, 3);

        assertThat(balanced.invariantHolds()).isTrue();
        assertThat(drifted.invariantHolds()).isFalse();
    }

    @Test
    void reservationIsAnImmutableLifecycleValue() {
        UUID reservationId = UUID.fromString("4dcf3d1d-b39c-4d5d-9ff9-12e19908a5fb");
        UUID actorId = UUID.fromString("b1d7f3d5-7d86-4a3f-8eb2-3a6a0b9f42b1");
        Instant expiresAt = Instant.parse("2026-08-09T05:00:00Z");

        Reservation reservation = new Reservation(
                reservationId,
                42L,
                actorId,
                2,
                ReservationStatus.RESERVED,
                expiresAt,
                null
        );

        assertThat(reservation.id()).isEqualTo(reservationId);
        assertThat(reservation.ticketItemId()).isEqualTo(42L);
        assertThat(reservation.demoActorId()).isEqualTo(actorId);
        assertThat(reservation.quantity()).isEqualTo(2);
        assertThat(reservation.status()).isEqualTo(ReservationStatus.RESERVED);
        assertThat(reservation.expiresAt()).isEqualTo(expiresAt);
        assertThat(reservation.terminalAt()).isNull();
    }
}
