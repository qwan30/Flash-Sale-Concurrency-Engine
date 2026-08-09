package com.xxxx.ddd.application.MQ;

import jakarta.persistence.Column;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

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
}
