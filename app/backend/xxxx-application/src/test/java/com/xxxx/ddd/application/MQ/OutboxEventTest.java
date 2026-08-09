package com.xxxx.ddd.application.MQ;

import org.junit.jupiter.api.Test;

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
}
