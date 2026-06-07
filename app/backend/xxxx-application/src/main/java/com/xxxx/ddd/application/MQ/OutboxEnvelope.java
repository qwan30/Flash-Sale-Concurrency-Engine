package com.xxxx.ddd.application.MQ;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.Instant;

/**
 * Versioned JSON envelope wrapping outbox events for the Kafka wire format.
 *
 * <p>This envelope provides a consistent contract for Kafka consumers,
 * including event metadata (aggregate type, event version, timestamps)
 * alongside the business payload.
 */
@Data
@AllArgsConstructor
public class OutboxEnvelope {
    private String eventId;
    private String aggregateType;
    private String aggregateId;
    private String eventType;
    private int eventVersion;
    private Instant createdAt;
    private JsonNode payload;
}
