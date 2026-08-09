package com.xxxx.ddd.infrastructure.reservation.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "inventory_operation_journal")
public class OperationJournalEntity {

    @Id
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "operation_id", columnDefinition = "BINARY(16)")
    private UUID operationId;

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "reservation_id", nullable = false, columnDefinition = "BINARY(16)")
    private UUID reservationId;

    @Column(name = "operation_type", nullable = false, length = 24)
    private String operationType;

    @Column(nullable = false, length = 32)
    private String state;

    @Column(name = "ticket_item_id", nullable = false)
    private long ticketItemId;

    @Column(nullable = false)
    private int quantity;

    @Column(name = "demo_actor_id", length = 36)
    private String demoActorId;

    @Column(name = "idempotency_key_hash", columnDefinition = "BINARY(32)")
    private byte[] idempotencyKeyHash;

    @Column(name = "request_fingerprint", nullable = false, columnDefinition = "BINARY(32)")
    private byte[] requestFingerprint;

    @Column(name = "fence_version", nullable = false)
    private long fenceVersion;

    @Column(name = "lease_owner", length = 64)
    private String leaseOwner;

    @Column(name = "lease_until")
    private Instant leaseUntil;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @Column(name = "result_code", length = 64)
    private String resultCode;

    @Column(name = "result_stock_after")
    private Integer resultStockAfter;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected OperationJournalEntity() {
    }
}
