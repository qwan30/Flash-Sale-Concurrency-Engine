package com.xxxx.ddd.application.MQ;

/**
 * Lifecycle status of an outbox event.
 */
public enum OutboxStatus {
    /** Event recorded in DB, awaiting Kafka publication. */
    PENDING,
    /** Event successfully published to Kafka. */
    PUBLISHED,
    /** Publication failed; eligible for retry until max attempts exceeded. */
    FAILED
}
