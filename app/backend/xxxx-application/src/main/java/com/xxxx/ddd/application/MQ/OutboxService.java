package com.xxxx.ddd.application.MQ;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xxxx.ddd.application.reservation.port.FaultInjectionPort;
import com.xxxx.ddd.application.reservation.port.NoOpFaultInjection;
import com.xxxx.ddd.application.reservation.port.NoOpReservationTelemetry;
import com.xxxx.ddd.application.reservation.port.ReservationTelemetryPort;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.observation.annotation.Observed;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Transactional Outbox service for reliable event publishing to Kafka.
 *
 * <p>Business code calls {@link #record} within its existing {@code @Transactional} scope
 * to atomically persist an event row alongside the domain change. A separate scheduler
 * then calls {@link #publishPendingEvents} to relay those events to Kafka.
 *
 * <p>This pattern provides at-least-once delivery while the configured retry budget remains:
 * if Kafka is temporarily unreachable, events stay in the database and are retried until they
 * publish or reach {@code app.outbox.max-attempts}. An exhausted event remains {@code FAILED}
 * without a next-attempt timestamp and requires an explicit operator retry or dead-letter action.
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
    private final Duration publishLease;
    private final String publisherId = "outbox-publisher-" + UUID.randomUUID();
    private FaultInjectionPort faults = new NoOpFaultInjection();
    private ReservationTelemetryPort telemetry = new NoOpReservationTelemetry();

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
            @Value("${app.outbox.max-attempts:5}") int maxAttempts,
            @Value("${app.outbox.publish-lease:30s}") Duration publishLease
    ) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
        this.publishBatchSize = publishBatchSize;
        this.retryDelay = retryDelay;
        this.maxAttempts = maxAttempts;
        this.publishLease = publishLease;
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

    @Autowired(required = false)
    public void setFaultInjectionPort(FaultInjectionPort faults) {
        this.faults = Objects.requireNonNull(faults, "faults must not be null");
    }

    @Autowired(required = false)
    public void setReservationTelemetryPort(ReservationTelemetryPort telemetry) {
        this.telemetry = Objects.requireNonNull(telemetry, "telemetry must not be null");
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
     * @return the number of events successfully finalized as published in this batch
     */
    @Observed(name = "flashsale.outbox.publish")
    public int publishPendingEvents() {
        Instant startedAt = Instant.now();
        Instant leaseUntil = Instant.now()
                .plus(publishLease)
                .truncatedTo(ChronoUnit.MILLIS);
        try {
            repository.claimPending(publisherId, leaseUntil, publishBatchSize);
            List<OutboxEvent> pendingEvents = repository.findByLeaseOwnerAndLeaseUntilOrderByCreatedAtAsc(
                    publisherId,
                    leaseUntil);
            int published = 0;
            for (OutboxEvent event : pendingEvents) {
                if (publishEvent(event, publisherId, leaseUntil)) {
                    published++;
                }
            }
            String outcome = pendingEvents.isEmpty()
                    ? "IDLE"
                    : published == 0
                    ? "FAILED"
                    : published == pendingEvents.size() ? "PUBLISHED" : "PARTIAL";
            telemetry.record(
                    "outbox.publish",
                    outcome,
                    outcome,
                    Duration.between(startedAt, Instant.now()));
            return published;
        } catch (RuntimeException exception) {
            telemetry.record(
                    "outbox.publish",
                    "EXCEPTION",
                    "UNHANDLED",
                    Duration.between(startedAt, Instant.now()));
            throw exception;
        }
    }

    /**
     * Re-queues failed events whose retry delay has elapsed.
     *
     * @return the number of events reset for retry
     */
    public int retryFailedEvents() {
        Instant now = Instant.now();
        int requeued = repository.requeueFailed(now, publishBatchSize);
        if (retryScheduledCounter != null) {
            retryScheduledCounter.increment(requeued);
        }
        return requeued;
    }

    /**
     * Returns the count of events still awaiting publication.
     */
    public long countPendingBacklog() {
        return repository.countByStatus(OutboxStatus.PENDING);
    }

    private boolean publishEvent(OutboxEvent event, String leaseOwner, Instant leaseUntil) {
        Timer.Sample sample = publishLatency != null ? Timer.start() : null;
        boolean published = false;
        Instant renewedAt = Instant.now();
        Instant activeLeaseUntil = renewedAt
                .plus(publishLease)
                .truncatedTo(ChronoUnit.MILLIS);
        try {
            if (repository.renewLeaseIfOwned(
                    event.getId(), leaseOwner, leaseUntil, activeLeaseUntil, renewedAt) == 0) {
                log.warn("OUTBOX: Lease expired or was reclaimed before Kafka send for event id={}",
                        event.getId());
                return false;
            }
        } catch (RuntimeException exception) {
            log.warn("OUTBOX: Could not renew lease before Kafka send for event id={}: {}",
                    event.getId(), exception.getMessage());
            return false;
        }
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
            faults.hit(
                    FaultInjectionPort.FaultPoint.KAFKA_UNAVAILABLE,
                    UUID.nameUUIDFromBytes(event.getEventId().getBytes(StandardCharsets.UTF_8)));
            kafkaTemplate.send(topic, event.getAggregateId(), message).get(5, TimeUnit.SECONDS);
            int finalized = repository.markPublishedIfOwned(
                    event.getId(), leaseOwner, activeLeaseUntil, Instant.now());
            if (finalized == 0) {
                log.warn("OUTBOX: Lease lost before finalizing successful publication for event id={}",
                        event.getId());
                return false;
            }
            if (publishSuccessCounter != null) {
                publishSuccessCounter.increment();
            }
            published = true;
        } catch (Exception exception) {
            Instant now = Instant.now();
            int finalized = repository.markFailedIfOwned(
                    event.getId(),
                    leaseOwner,
                    activeLeaseUntil,
                    exception.getMessage(),
                    now.plus(retryDelay),
                    now,
                    maxAttempts);
            if (publishFailureCounter != null) {
                publishFailureCounter.increment();
            }
            if (finalized == 0) {
                log.warn("OUTBOX: Lease lost before finalizing failed publication for event id={}",
                        event.getId());
            }
            log.warn("OUTBOX: Failed to publish event id={} type={}: {}",
                    event.getId(), event.getEventType(), exception.getMessage());
        } finally {
            if (sample != null) {
                sample.stop(publishLatency);
            }
        }
        return published;
    }
}
