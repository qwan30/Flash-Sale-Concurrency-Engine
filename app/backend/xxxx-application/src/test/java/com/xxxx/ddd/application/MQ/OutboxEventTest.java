package com.xxxx.ddd.application.MQ;

import jakarta.persistence.Column;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class OutboxEventTest {

    @Test
    void constructorUsesOneStableIdentifierForJpaAndEventIdentity() {
        String id = "4dcf3d1d-b39c-4d5d-9ff9-12e19908a5fb";

        OutboxEvent event = new OutboxEvent(
                id,
                "Order",
                "order-1",
                "ORDER_CREATED",
                OutboxEvent.DEFAULT_EVENT_VERSION,
                "{}"
        );

        assertThat(event.getEventId()).isEqualTo(id);
    }

    @Test
    void eventIdentityIsReadOnlyThroughJpaMapping() throws NoSuchFieldException {
        Field field = OutboxEvent.class.getDeclaredField("eventId");
        Column mapping = field.getAnnotation(Column.class);

        assertThat(mapping).isNotNull();
        assertThat(mapping.insertable()).isFalse();
        assertThat(mapping.updatable()).isFalse();
    }

    @Test
    void stopsAutomaticRetryAfterTheConfiguredAttemptBudget() {
        OutboxEvent event = new OutboxEvent(
                "4dcf3d1d-b39c-4d5d-9ff9-12e19908a5fb",
                "Order",
                "order-1",
                "ORDER_CREATED",
                OutboxEvent.DEFAULT_EVENT_VERSION,
                "{}"
        );
        Instant now = Instant.parse("2026-08-11T08:00:00Z");

        event.markFailed("kafka unavailable", now, Duration.ofSeconds(10), 2);
        assertThat(event.getNextAttemptAt()).isEqualTo(now.plusSeconds(10));
        event.markFailed("kafka unavailable", now.plusSeconds(10), Duration.ofSeconds(10), 2);

        assertThat(event.getAttemptCount()).isEqualTo(2);
        assertThat(event.getNextAttemptAt()).isNull();
        assertThat(event.getStatus()).isEqualTo(OutboxStatus.FAILED);
    }
}
