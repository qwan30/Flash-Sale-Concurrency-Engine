-- Fail closed on legacy oversold or malformed stock. An operator must repair
-- and record the disposition before retrying this migration; the reservation
-- account must never be initialized with an invalid quantity invariant.
CREATE TEMPORARY TABLE reservation_legacy_stock_validation (
    ticket_item_id BIGINT PRIMARY KEY,
    initial_quantity INT NOT NULL,
    available_quantity INT NOT NULL,
    CONSTRAINT chk_legacy_stock_values CHECK (
        initial_quantity >= 0
        AND available_quantity >= 0
        AND available_quantity <= initial_quantity
    )
) ENGINE=InnoDB;

INSERT INTO reservation_legacy_stock_validation (
    ticket_item_id,
    initial_quantity,
    available_quantity
)
SELECT id, stock_initial, stock_available
FROM ticket_item;

DROP TEMPORARY TABLE reservation_legacy_stock_validation;

CREATE TABLE inventory_stock_account (
    ticket_item_id BIGINT PRIMARY KEY,
    initial_quantity INT NOT NULL,
    available_quantity INT NOT NULL,
    admission_state VARCHAR(16) NOT NULL DEFAULT 'OPEN',
    fence_version BIGINT NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT chk_inventory_admission_state CHECK (admission_state IN ('OPEN', 'DRAINING', 'CLOSED')),
    CONSTRAINT chk_inventory_versions CHECK (fence_version >= 0 AND version >= 0),
    CONSTRAINT chk_inventory_quantities CHECK (
        initial_quantity >= 0 AND available_quantity >= 0 AND available_quantity <= initial_quantity
    ),
    CONSTRAINT fk_stock_ticket_item FOREIGN KEY (ticket_item_id)
        REFERENCES ticket_item(id)
) ENGINE=InnoDB;

CREATE TABLE inventory_reservation (
    id BINARY(16) PRIMARY KEY,
    ticket_item_id BIGINT NOT NULL,
    demo_actor_id CHAR(36) NOT NULL,
    quantity INT NOT NULL,
    status VARCHAR(16) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    terminal_at DATETIME(6) NULL,
    idempotency_key_hash BINARY(32) NOT NULL,
    request_fingerprint BINARY(32) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_reservation_actor_key (demo_actor_id, idempotency_key_hash),
    KEY idx_reservation_expiry (ticket_item_id, status, expires_at),
    CONSTRAINT chk_reservation_quantity CHECK (quantity BETWEEN 1 AND 4),
    CONSTRAINT chk_reservation_status CHECK (status IN ('RESERVED', 'CONFIRMED', 'RELEASED', 'EXPIRED')),
    CONSTRAINT fk_reservation_stock FOREIGN KEY (ticket_item_id)
        REFERENCES inventory_stock_account(ticket_item_id)
) ENGINE=InnoDB;

CREATE TABLE inventory_operation_journal (
    operation_id BINARY(16) PRIMARY KEY,
    reservation_id BINARY(16) NOT NULL,
    operation_type VARCHAR(24) NOT NULL,
    state VARCHAR(32) NOT NULL,
    ticket_item_id BIGINT NOT NULL,
    quantity INT NOT NULL,
    demo_actor_id CHAR(36) NULL,
    idempotency_key_hash BINARY(32) NULL,
    request_fingerprint BINARY(32) NOT NULL,
    fence_version BIGINT NOT NULL,
    lease_owner VARCHAR(64) NULL,
    lease_until DATETIME(6) NULL,
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(6) NULL,
    last_error_code VARCHAR(64) NULL,
    result_code VARCHAR(64) NULL,
    result_stock_after INT NULL,
    repair_id BINARY(16) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_journal_create_claim (demo_actor_id, idempotency_key_hash),
    KEY idx_journal_recovery (state, next_attempt_at, lease_until),
    CONSTRAINT chk_journal_operation_type CHECK (
        operation_type IN ('CREATE', 'CONFIRM', 'RELEASE', 'EXPIRE', 'COMPENSATE', 'MIRROR', 'REPAIR')
    ),
    CONSTRAINT chk_journal_state CHECK (
        state IN (
            'RECEIVED', 'REJECTED', 'REDIS_APPLIED', 'COMMITTED', 'COMPENSATED',
            'COMPENSATION_PENDING', 'MIRROR_PENDING', 'REPAIR_REQUIRED'
        )
    ),
    CONSTRAINT chk_journal_numbers CHECK (
        quantity BETWEEN 1 AND 4 AND fence_version >= 0 AND attempts >= 0
    ),
    CONSTRAINT chk_journal_create_claim CHECK (
        (operation_type = 'CREATE' AND demo_actor_id IS NOT NULL AND idempotency_key_hash IS NOT NULL)
        OR (operation_type <> 'CREATE' AND demo_actor_id IS NULL AND idempotency_key_hash IS NULL)
    )
) ENGINE=InnoDB;

CREATE TABLE inventory_repair_journal (
    repair_id BINARY(16) PRIMARY KEY,
    ticket_item_id BIGINT NOT NULL,
    previous_fence_version BIGINT NOT NULL,
    new_fence_version BIGINT NOT NULL,
    state VARCHAR(16) NOT NULL,
    disposition VARCHAR(64) NULL,
    mysql_available_snapshot INT NULL,
    started_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    completed_at DATETIME(6) NULL,
    UNIQUE KEY uk_repair_ticket_fence (ticket_item_id, new_fence_version),
    CONSTRAINT fk_repair_stock FOREIGN KEY (ticket_item_id)
        REFERENCES inventory_stock_account(ticket_item_id),
    CONSTRAINT chk_repair_state CHECK (state IN ('STARTED', 'VERIFIED', 'COMPLETED', 'FAILED')),
    CONSTRAINT chk_repair_fence CHECK (previous_fence_version >= 0 AND new_fence_version > previous_fence_version)
) ENGINE=InnoDB;

CREATE TABLE reservation_order (
    id BINARY(16) PRIMARY KEY,
    reservation_id BINARY(16) NOT NULL,
    ticket_item_id BIGINT NOT NULL,
    demo_actor_id CHAR(36) NOT NULL,
    quantity INT NOT NULL,
    confirmed_at DATETIME(6) NOT NULL,
    UNIQUE KEY uk_order_reservation (reservation_id),
    CONSTRAINT chk_order_quantity CHECK (quantity BETWEEN 1 AND 4),
    CONSTRAINT fk_order_stock FOREIGN KEY (ticket_item_id)
        REFERENCES inventory_stock_account(ticket_item_id),
    CONSTRAINT fk_order_reservation FOREIGN KEY (reservation_id)
        REFERENCES inventory_reservation(id)
) ENGINE=InnoDB;

-- event_id is a generated read alias of the existing primary key. The primary
-- key remains the only identity/uniqueness boundary for outbox rows, so legacy
-- and direct SQL writers cannot create a null or divergent event identity.
ALTER TABLE outbox_event
    ADD COLUMN event_id VARCHAR(36) GENERATED ALWAYS AS (id) STORED,
    ADD COLUMN lease_owner VARCHAR(64) NULL,
    ADD COLUMN lease_until TIMESTAMP(3) NULL;

INSERT INTO inventory_stock_account (
    ticket_item_id,
    initial_quantity,
    available_quantity,
    admission_state,
    fence_version,
    version
)
SELECT
    id,
    stock_initial,
    stock_available,
    'OPEN',
    0,
    0
FROM ticket_item
;
