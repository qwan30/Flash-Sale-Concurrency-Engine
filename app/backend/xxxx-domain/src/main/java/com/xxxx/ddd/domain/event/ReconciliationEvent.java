package com.xxxx.ddd.domain.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Domain event for reconciliation actions (drift detection and repair).
 *
 * <p>Published via the Transactional Outbox after the reconciliation service
 * detects and repairs Redis/MySQL stock drift.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReconciliationEvent {

    public static final String AGGREGATE_TYPE = "Reconciliation";

    private Long ticketItemId;
    private String eventType;
    private int redisStockBefore;
    private int dbStockBefore;
    private int driftAmount;
    private int redisStockAfter;
    private boolean repaired;

    /** Event type constants */
    public static final String DRIFT_DETECTED = "DRIFT_DETECTED";
    public static final String DRIFT_REPAIRED = "DRIFT_REPAIRED";
}
