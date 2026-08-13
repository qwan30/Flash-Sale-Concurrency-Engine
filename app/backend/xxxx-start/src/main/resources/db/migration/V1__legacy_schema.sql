-- Flyway baseline for the schema that predates reservation reliability.
-- Existing non-empty installations are baselined explicitly; fresh installs
-- receive the legacy tables before V2 adds the reservation model.

CREATE TABLE IF NOT EXISTS ticket (
    id BIGINT NOT NULL AUTO_INCREMENT,
    name VARCHAR(120) NOT NULL,
    `desc` TEXT NULL,
    start_time DATETIME NOT NULL,
    end_time DATETIME NOT NULL,
    status INT NOT NULL DEFAULT 0,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_ticket_end_time (end_time),
    KEY idx_ticket_start_time (start_time),
    KEY idx_ticket_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS ticket_item (
    id BIGINT NOT NULL AUTO_INCREMENT,
    name VARCHAR(120) NOT NULL,
    description TEXT NULL,
    stock_initial INT NOT NULL DEFAULT 0,
    stock_available INT NOT NULL DEFAULT 0,
    is_stock_prepared BOOLEAN NOT NULL DEFAULT FALSE,
    price_original BIGINT NOT NULL,
    price_flash BIGINT NOT NULL,
    sale_start_time DATETIME NOT NULL,
    sale_end_time DATETIME NOT NULL,
    status INT NOT NULL DEFAULT 0,
    activity_id BIGINT NOT NULL,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_ticket_item_end_time (sale_end_time),
    KEY idx_ticket_item_start_time (sale_start_time),
    KEY idx_ticket_item_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS ticket_order_202502 (
    id INT NOT NULL AUTO_INCREMENT,
    user_id INT NOT NULL,
    order_number VARCHAR(50) NOT NULL,
    total_amount DECIMAL(10,3) NOT NULL,
    terminal_id VARCHAR(20) NOT NULL,
    order_date TIMESTAMP NOT NULL,
    order_notes VARCHAR(100) NULL DEFAULT 'None',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_ticket_order_number (order_number),
    KEY idx_ticket_order_date (order_date),
    KEY idx_ticket_order_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS ticket_order_details_202502 (
    id INT NOT NULL AUTO_INCREMENT,
    ticket_item_id BIGINT NOT NULL,
    order_number VARCHAR(50) NOT NULL,
    passenger_name VARCHAR(100) NOT NULL,
    passenger_id VARCHAR(20) NOT NULL,
    departure_station VARCHAR(10) NOT NULL,
    arrival_station VARCHAR(10) NOT NULL,
    departure_time DATETIME NOT NULL,
    seat_class ENUM('Economy', 'Business', 'First') NOT NULL,
    seat_number VARCHAR(10) NOT NULL,
    ticket_price DECIMAL(10,3) NOT NULL,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_ticket_order_details_order (order_number),
    KEY idx_ticket_order_details_item (ticket_item_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS outbox_event (
    id VARCHAR(36) PRIMARY KEY,
    aggregate_type VARCHAR(100) NOT NULL,
    aggregate_id VARCHAR(100) NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    event_version INT NOT NULL DEFAULT 1,
    payload TEXT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    published_at TIMESTAMP(3) NULL,
    failure_message TEXT NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP(3) NULL,
    INDEX idx_outbox_status_created (status, created_at),
    INDEX idx_outbox_status_next_attempt (status, next_attempt_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
