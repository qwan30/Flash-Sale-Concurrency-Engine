package com.xxxx.ddd.infrastructure.reservation.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "inventory_stock_account")
public class InventoryStockAccountEntity {

    @Id
    @Column(name = "ticket_item_id")
    private long ticketItemId;

    @Column(name = "initial_quantity", nullable = false)
    private int initialQuantity;

    @Column(name = "available_quantity", nullable = false)
    private int availableQuantity;

    @Column(name = "admission_state", nullable = false, length = 16)
    private String admissionState;

    @Column(name = "fence_version", nullable = false)
    private long fenceVersion;

    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected InventoryStockAccountEntity() {
    }
}
