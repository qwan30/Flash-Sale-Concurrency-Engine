package com.xxxx.ddd.application.MQ;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Duration;
import java.time.Instant;

/**
 * JPA entity representing a single outbox event row.
 *
 * <p>Written within the same database transaction as the business operation,
 * then asynchronously published to Kafka by {@link OutboxService}.
 */
@Entity
@Table(name = "outbox_event")
@Getter
@Setter
@NoArgsConstructor
public class OutboxEvent {

    public static final int DEFAULT_EVENT_VERSION = 1;

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "aggregate_type", length = 100, nullable = false)
    private String aggregateType;

    @Column(name = "aggregate_id", length = 100, nullable = false)
    private String aggregateId;

    @Column(name = "event_type", length = 100, nullable = false)
    private String eventType;

    @Column(name = "event_version", nullable = false)
    private int eventVersion = DEFAULT_EVENT_VERSION;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(length = 20, nullable = false)
    private OutboxStatus status = OutboxStatus.PENDING;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "failure_message", columnDefinition = "TEXT")
    private String failureMessage;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount = 0;

    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    public OutboxEvent(String id, String aggregateType, String aggregateId,
                       String eventType, int eventVersion, String payload) {
        this.id = id;
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.eventVersion = eventVersion;
        this.payload = payload;
        this.createdAt = Instant.now();
    }

    /**
     * Marks this event as successfully published.
     */
    public void markPublished(Instant now) {
        this.status = OutboxStatus.PUBLISHED;
        this.publishedAt = now;
        this.failureMessage = null;
    }

    /**
     * Marks this event as failed and schedules the next retry attempt.
     * If max attempts are exceeded, the event stays FAILED without a next attempt.
     */
    public void markFailed(String message, Instant now, Duration retryDelay, int maxAttempts) {
        this.status = OutboxStatus.FAILED;
        this.failureMessage = message;
        this.attemptCount++;
        if (this.attemptCount < maxAttempts) {
            this.nextAttemptAt = now.plus(retryDelay);
        } else {
            this.nextAttemptAt = null;
        }
    }

    /**
     * Resets this failed event back to PENDING for re-publication.
     */
    public void resetForRetry() {
        this.status = OutboxStatus.PENDING;
        this.failureMessage = null;
        this.nextAttemptAt = null;
    }
}
