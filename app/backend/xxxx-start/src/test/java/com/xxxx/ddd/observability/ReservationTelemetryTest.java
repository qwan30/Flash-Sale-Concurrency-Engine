package com.xxxx.ddd.observability;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

class ReservationTelemetryTest {

    private static final Set<String> ALLOWED_TAG_KEYS = Set.of("operation", "outcome", "reason");

    @Test
    void emitsFixedReservationMetersWithBoundedTagValues() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ReservationTelemetryAdapter telemetry = new ReservationTelemetryAdapter(registry);

        telemetry.record("create", "NEW", "NEW", Duration.ofMillis(12));
        telemetry.record("ticket-42", "operation-123", "idempotency-key-123", Duration.ofMillis(2));

        assertThat(registry.find("flashsale.reservation.operation").timers()).hasSize(2);
        assertThat(registry.find("flashsale.reservation.operation").timers().stream()
                .mapToLong(timer -> timer.count())
                .sum()).isEqualTo(2);
        assertThat(registry.find("flashsale.reservation.transitions").counter()).isNotNull();
        assertThat(registry.getMeters())
                .allSatisfy(meter -> assertThat(meter.getId().getTags())
                        .extracting(Tag::getKey)
                        .allMatch(ALLOWED_TAG_KEYS::contains));
        assertThat(registry.getMeters())
                .flatExtracting(meter -> meter.getId().getTags())
                .extracting(Tag::getValue)
                .contains("OTHER");
    }

    @Test
    void mapsEveryUnknownLabelToOneBoundedOtherValue() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new ReservationTelemetryAdapter(registry)
                .record("operation-raw", "outcome-raw", "reason-raw", Duration.ofMillis(1));

        assertThat(registry.getMeters())
                .flatExtracting(meter -> meter.getId().getTags())
                .extracting(Tag::getValue)
                .containsOnly("OTHER");
    }

    @Test
    void registersOnlyBoundedOperationalGaugeLabels() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new ReservationTelemetryAdapter(registry, mock(JdbcTemplate.class), null);

        assertThat(registry.find("flashsale.reservation.units").gauges()).hasSize(3);
        assertThat(registry.find("flashsale.recovery.operations").gauges()).hasSize(9);
        assertThat(registry.find("flashsale.inventory.drift.units").gauge()).isNotNull();
        assertThat(registry.getMeters())
                .flatExtracting(meter -> meter.getId().getTags())
                .extracting(Tag::getKey)
                .containsAnyOf("status", "state");
    }

    @Test
    void exposesOutboxAgeAsSecondsForPrometheusDashboardQueries() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new ReservationTelemetryAdapter(registry, mock(JdbcTemplate.class), null);

        assertThat(registry.get("flashsale.outbox.oldest.age").meter().getId().getBaseUnit())
                .isEqualTo("seconds");
    }

    @Test
    void exposesNaNAndFailureCounterWhenDatabaseGaugeReadFails() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        doThrow(new IllegalStateException("database unavailable"))
                .when(jdbc).queryForObject(anyString(), eq(Number.class), any(Object[].class));
        ReservationTelemetryAdapter telemetry = new ReservationTelemetryAdapter(registry, jdbc, null);

        double value = registry.get("flashsale.reservation.units")
                .tag("status", "available")
                .gauge()
                .value();

        assertThat(value).isNaN();
        assertThat(registry.get("flashsale.telemetry.read.failure").counter().count()).isEqualTo(1.0);
    }

    @Test
    void exposesNaNAndFailureCounterWhenRedisDriftEvidenceIsUnavailable() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ReservationTelemetryAdapter telemetry = new ReservationTelemetryAdapter(
                registry, mock(JdbcTemplate.class), null);

        double value = registry.get("flashsale.inventory.drift.units").gauge().value();

        assertThat(value).isNaN();
        assertThat(registry.get("flashsale.telemetry.read.failure").counter().count()).isEqualTo(1.0);
    }
}
