package com.xxxx.ddd.application.MQ;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Transactional Outbox service for reliable event publishing to Kafka.
 *
 * <p>Business code calls {@link #record} within its existing {@code @Transactional} scope
 * to atomically persist an event row alongside the domain change. A separate scheduler
 * then calls {@link #publishPendingEvents} to relay those events to Kafka.
 *
 * <p>This pattern guarantees at-least-once delivery: if Kafka is temporarily
 * unreachable, events stay in the database and are retried until successful.
 */
@Service
@Slf4j
public class OutboxService {

    private final OutboxRepository repository;
    private final ObjectMapper objectMapper;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final String topic;
    private final int publishBatchSize;
    private final Duration retryDelay;
    private final int maxAttempts;

    private Counter publishSuccessCounter;
    private Counter publishFailureCounter;
    private Counter retryScheduledCounter;
    private Timer publishLatency;

    public OutboxService(
            OutboxRepository repository,
            ObjectMapper objectMapper,
            KafkaTemplate<String, String> kafkaTemplate,
            @Value("${app.kafka.topic:flashsale.orders}") String topic,
            @Value("${app.outbox.publish-batch-size:50}") int publishBatchSize,
            @Value("${app.outbox.retry-delay:10s}") Duration retryDelay,
            @Value("${app.outbox.max-attempts:5}") int maxAttempts
    ) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
        this.publishBatchSize = publishBatchSize;
        this.retryDelay = retryDelay;
        this.maxAttempts = maxAttempts;
    }

    @Autowired(required = false)
    public void setMeterRegistry(MeterRegistry meterRegistry) {
        if (meterRegistry == null) {
            return;
        }
        this.publishSuccessCounter = meterRegistry.counter("outbox.publish.success");
        this.publishFailureCounter = meterRegistry.counter("outbox.publish.failure");
        this.retryScheduledCounter = meterRegistry.counter("outbox.retry.scheduled");
        this.publishLatency = meterRegistry.timer("outbox.publish.latency");
        Gauge.builder("outbox.backlog.pending", repository,
                        r -> r.countByStatus(OutboxStatus.PENDING))
                .register(meterRegistry);
        Gauge.builder("outbox.backlog.failed", repository,
                        r -> r.countByStatus(OutboxStatus.FAILED))
                .register(meterRegistry);
    }

    /**
     * Records a domain event in the outbox table.
     *
     * <p>This method MUST be called within a {@code @Transactional} scope so the
     * event row is committed atomically with the business data change.
     *
     * @param aggregateType the aggregate type (e.g., "Order", "Reconciliation")
     * @param aggregateId   the aggregate identifier (e.g., order number)
     * @param eventType     the event type (e.g., "ORDER_CREATED")
     * @param payload       the event payload object (will be serialized to JSON)
     * @return the persisted outbox event
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public OutboxEvent record(String aggregateType, String aggregateId, String eventType, Object payload) {
        try {
            String serializedPayload = objectMapper.writeValueAsString(payload);
            return repository.save(new OutboxEvent(
                    UUID.randomUUID().toString(),
                    aggregateType,
                    aggregateId,
                    eventType,
                    OutboxEvent.DEFAULT_EVENT_VERSION,
                    serializedPayload
            ));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize outbox payload", exception);
        }
    }

    /**
     * Publishes pending outbox events to Kafka in batches.
     *
     * @return the number of events processed in this batch
     */
    public int publishPendingEvents() {
        List<OutboxEvent> pendingEvents = repository.findByStatusOrderByCreatedAtAsc(
                OutboxStatus.PENDING,
                PageRequest.of(0, publishBatchSize)
        );
        pendingEvents.forEach(this::publishEvent);
        return pendingEvents.size();
    }

    /**
     * Re-queues failed events whose retry delay has elapsed.
     *
     * @return the number of events reset for retry
     */
    public int retryFailedEvents() {
        Instant now = Instant.now();
        List<OutboxEvent> retryableEvents =
                repository.findByStatusAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAscCreatedAtAsc(
                        OutboxStatus.FAILED, now, PageRequest.of(0, publishBatchSize));
        if (retryableEvents.isEmpty()) {
            return 0;
        }
        retryableEvents.forEach(OutboxEvent::resetForRetry);
        repository.saveAllAndFlush(retryableEvents);
        if (retryScheduledCounter != null) {
            retryScheduledCounter.increment(retryableEvents.size());
        }
        return retryableEvents.size();
    }

    /**
     * Returns the count of events still awaiting publication.
     */
    public long countPendingBacklog() {
        return repository.countByStatus(OutboxStatus.PENDING);
    }

    private void publishEvent(OutboxEvent event) {
        Timer.Sample sample = publishLatency != null ? Timer.start() : null;
        try {
            OutboxEnvelope envelope = new OutboxEnvelope(
                    event.getId(),
                    event.getAggregateType(),
                    event.getAggregateId(),
                    event.getEventType(),
                    event.getEventVersion(),
                    event.getCreatedAt(),
                    objectMapper.readTree(event.getPayload())
            );
            String message = objectMapper.writeValueAsString(envelope);
            kafkaTemplate.send(topic, event.getAggregateId(), message).get(5, TimeUnit.SECONDS);
            event.markPublished(Instant.now());
            if (publishSuccessCounter != null) {
                publishSuccessCounter.increment();
            }
        } catch (Exception exception) {
            event.markFailed(exception.getMessage(), Instant.now(), retryDelay, maxAttempts);
            if (publishFailureCounter != null) {
                publishFailureCounter.increment();
            }
            log.warn("OUTBOX: Failed to publish event id={} type={}: {}",
                    event.getId(), event.getEventType(), exception.getMessage());
        } finally {
            if (sample != null) {
                sample.stop(publishLatency);
            }
        }
        repository.saveAndFlush(event);
    }
}
