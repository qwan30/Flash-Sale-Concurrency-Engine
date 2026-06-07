package com.xxxx.ddd.application.MQ;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduler that drives the outbox publish and retry cycles.
 *
 * <p>Runs alongside the existing {@code @EnableScheduling} on StartApplication.
 * Publish runs every 5 seconds; retry runs every 30 seconds.
 */
@Component
@Slf4j
public class OutboxPublishScheduler {

    private final OutboxService outboxService;

    public OutboxPublishScheduler(OutboxService outboxService) {
        this.outboxService = outboxService;
    }

    /**
     * Publishes pending outbox events to Kafka every 5 seconds.
     */
    @Scheduled(fixedDelay = 5_000, initialDelay = 5_000)
    public void publishPending() {
        try {
            int published = outboxService.publishPendingEvents();
            if (published > 0) {
                log.info("OUTBOX_SCHEDULER: Published {} pending events to Kafka", published);
            }
        } catch (Exception e) {
            log.error("OUTBOX_SCHEDULER: Error publishing pending events", e);
        }
    }

    /**
     * Retries failed outbox events every 30 seconds.
     */
    @Scheduled(fixedDelay = 30_000, initialDelay = 15_000)
    public void retryFailed() {
        try {
            int retried = outboxService.retryFailedEvents();
            if (retried > 0) {
                log.info("OUTBOX_SCHEDULER: Re-queued {} failed events for retry", retried);
            }
        } catch (Exception e) {
            log.error("OUTBOX_SCHEDULER: Error retrying failed events", e);
        }
    }
}
