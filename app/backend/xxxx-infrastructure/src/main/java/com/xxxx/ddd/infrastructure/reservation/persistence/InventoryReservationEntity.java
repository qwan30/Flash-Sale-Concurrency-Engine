package com.xxxx.ddd.infrastructure.reservation.persistence;

import com.xxxx.ddd.domain.reservation.ReservationStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "inventory_reservation")
public class InventoryReservationEntity {

    @Id
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "id", columnDefinition = "BINARY(16)")
    private UUID id;

    @Column(name = "ticket_item_id", nullable = false)
    private long ticketItemId;

    @Column(name = "demo_actor_id", nullable = false, length = 36)
    private String demoActorId;

    @Column(nullable = false)
    private int quantity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ReservationStatus status;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "terminal_at")
    private Instant terminalAt;

    @Column(name = "idempotency_key_hash", nullable = false, columnDefinition = "BINARY(32)")
    private byte[] idempotencyKeyHash;

    @Column(name = "request_fingerprint", nullable = false, columnDefinition = "BINARY(32)")
    private byte[] requestFingerprint;

    @Column(nullable = false)
    private long version;

    protected InventoryReservationEntity() {
    }
}
