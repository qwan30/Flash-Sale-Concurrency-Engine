CREATE DATABASE IF NOT EXISTS vetautet
    DEFAULT CHARSET = utf8mb4
    COLLATE = utf8mb4_unicode_ci;

-- ============================================================================
-- 1. ticket table — flash-sale events
-- ============================================================================
CREATE TABLE IF NOT EXISTS `vetautet`.`ticket` (
     `id` BIGINT(20)  NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
    `name` VARCHAR(120) NOT NULL COMMENT 'ticket name',
    `desc` TEXT COMMENT 'ticket description',
    `start_time` DATETIME NOT NULL COMMENT 'ticket sale start time',
    `end_time` DATETIME    NOT NULL COMMENT 'ticket sale end time',
    `status`   INT(11) NOT NULL DEFAULT 0 COMMENT 'ticket sale activity status', -- 0: deactive, 1: activity
    `updated_at` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Last update time',
    `created_at`   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Creation time',
    PRIMARY KEY (`id`),
    KEY `idx_end_time` (`end_time`),
    KEY `idx_start_time` (`start_time`),
    KEY `idx_status` (`status`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = 'ticket table';

-- ============================================================================
-- 2. ticket_item table — individual ticket types with stock
-- ============================================================================
CREATE TABLE IF NOT EXISTS `vetautet`.`ticket_item` (
    `id` BIGINT(20)  NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
    `name` VARCHAR(120) NOT NULL COMMENT 'Ticket title',
    `description` TEXT COMMENT 'Ticket description',
    `stock_initial` INT(11) NOT NULL DEFAULT 0 COMMENT 'Initial stock quantity (e.g., 1000 tickets)',
    `stock_available` INT(11) NOT NULL DEFAULT 0 COMMENT 'Current available stock (e.g., 900 tickets)',
    `is_stock_prepared` BOOLEAN NOT NULL DEFAULT 0 COMMENT 'Indicates if stock is pre-warmed (0/1)',
    `price_original` BIGINT(20) NOT NULL COMMENT 'Original ticket price',
    `price_flash` BIGINT(20) NOT NULL COMMENT 'Discounted price during flash sale',
    `sale_start_time` DATETIME NOT NULL COMMENT 'Flash sale start time',
    `sale_end_time` DATETIME NOT NULL COMMENT 'Flash sale end time',
    `status` INT(11) NOT NULL DEFAULT 0 COMMENT 'Ticket status (e.g., active/inactive)',
    `activity_id` BIGINT(20) NOT NULL COMMENT 'ID of associated activity',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Timestamp of the last update',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Creation timestamp',
    PRIMARY KEY (`id`),
    KEY `idx_end_time` (`sale_end_time`),
    KEY `idx_start_time` (`sale_start_time`),
    KEY `idx_status` (`status`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = 'Table for ticket details';

-- ============================================================================
-- SEED DATA — Events (ticket)
-- ============================================================================
-- Events span major Vietnamese holiday periods so the dashboard shows
-- realistic sales windows. Status=1 = active, 0 = inactive.
-- ============================================================================

INSERT INTO `vetautet`.`ticket` (`name`, `desc`, `start_time`, `end_time`, `status`, `updated_at`, `created_at`)
VALUES
    -- Event 1 (original — kept for backward compatibility)
    ('Đợt Mở Bán Vé Ngày 12/12', 'Sự kiện mở bán vé đặc biệt cho ngày 12/12', '2024-12-12 00:00:00', '2024-12-12 23:59:59', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    -- Event 2 (original — kept for backward compatibility)
    ('Đợt Mở Bán Vé Ngày 01/01', 'Sự kiện mở bán vé cho ngày đầu năm mới 01/01', '2025-01-01 00:00:00', '2025-01-01 23:59:59', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),

    -- NEW EVENTS — realistic Vietnamese holiday calendar
    ('Tết Nguyên Đán Ất Tỵ 2025', 'Mở bán vé tàu Tết Nguyên Đán — tuyến Bắc-Nam phục vụ người dân về quê đón Tết cổ truyền', '2025-01-15 08:00:00', '2025-02-10 23:59:59', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('Lễ 30/4 - 1/5 Đợt 1', 'Mở bán vé tàu dịp nghỉ lễ Giải Phóng Miền Nam và Quốc tế Lao Động', '2025-04-20 08:00:00', '2025-05-02 23:59:59', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('Hè 2025 — Cao Điểm Du Lịch', 'Mở bán vé tàu mùa hè 2025 — ưu tiên tuyến du lịch biển Đà Nẵng, Nha Trang, Phan Thiết', '2025-06-01 08:00:00', '2025-08-31 23:59:59', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('Quốc Khánh 2/9/2025', 'Mở bán vé tàu dịp nghỉ lễ Quốc Khánh — tuyến du lịch ngắn ngày', '2025-08-20 08:00:00', '2025-09-03 23:59:59', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('Tết Dương Lịch 2026', 'Mở bán vé tàu dịp Tết Dương Lịch — chào đón năm mới 2026', '2025-12-20 08:00:00', '2026-01-02 23:59:59', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('Tết Nguyên Đán Bính Ngọ 2026', 'Mở bán vé tàu Tết Nguyên Đán 2026 — đợt về quê lớn nhất trong năm', '2026-01-10 08:00:00', '2026-02-15 23:59:59', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

-- ============================================================================
-- SEED DATA — Ticket Items
-- ============================================================================
-- Covers real Vietnam Railways North-South line stations:
--   HAN = Hà Nội         DNG = Đà Nẵng        NTR = Nha Trang
--   SGN = Sài Gòn         HUE = Huế            PYC = Phan Thiết
--   VII = Vinh            DTH = Đồng Hới
--
-- Seat classes: Ngồi Cứng (hard seat), Ngồi Mềm (soft seat),
--   Nằm Khoang 6 (6-berth sleeper), Nằm Khoang 4 (4-berth sleeper),
--   VIP (luxury sleeper)
--
-- Stock levels are chosen to exercise different concurrency stress profiles:
--   Tiny  (10–50)   -> extreme contention, near-instant sell-out
--   Small (100–300) -> high contention, realistic flash-sale
--   Medium (500–1000) -> typical benchmark (IDs 1-4 kept unchanged)
--   Large (2000–10000) -> throughput stress testing
-- ============================================================================

INSERT INTO `vetautet`.`ticket_item` (`name`, `description`, `stock_initial`, `stock_available`, `is_stock_prepared`, `price_original`, `price_flash`, `sale_start_time`, `sale_end_time`, `status`, `activity_id`, `updated_at`, `created_at`)
VALUES
    -- ======== IDs 1-4: ORIGINAL (kept for backward compat with WarmupDataBeforeEvent) ========
    -- Event 1 items (activity_id=1)
    ('Vé Sự Kiện 12/12 - Hạng Phổ Thông', 'Vé phổ thông cho sự kiện ngày 12/12', 1000, 1000, 0, 100000, 10000, '2024-12-12 00:00:00', '2024-12-12 23:59:59', 1, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('Vé Sự Kiện 12/12 - Hạng VIP', 'Vé VIP cho sự kiện ngày 12/12', 500, 500, 0, 200000, 15000, '2024-12-12 00:00:00', '2024-12-12 23:59:59', 1, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    -- Event 2 items (activity_id=2)
    ('Vé Sự Kiện 01/01 - Hạng Phổ Thông', 'Vé phổ thông cho sự kiện ngày 01/01', 2000, 2000, 0, 100000, 10000, '2025-01-01 00:00:00', '2025-01-01 23:59:59', 1, 2, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('Vé Sự Kiện 01/01 - Hạng VIP', 'Vé VIP cho sự kiện ngày 01/01', 1000, 1000, 0, 200000, 15000, '2025-01-01 00:00:00', '2025-01-01 23:59:59', 1, 2, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),

    -- ======== IDs 5-36: NEW — realistic Vietnam Railways flash-sale catalog ========

    -- --- Event 3: Tết Nguyên Đán 2025 (activity_id=3) ---
    -- Tiny stock (10-50): extreme contention stress tests
    ('Tàu Tết SE1 — Hà Nội → Sài Gòn · Ngồi Cứng · Flash 90%', 'Tuyến Bắc-Nam huyết mạch. Vé ngồi cứng giá rẻ cho sinh viên về quê. Flash sale giảm sâu 90%.', 30, 30, 0, 380000, 38000, '2025-01-20 12:00:00', '2025-01-20 12:05:00', 1, 3, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('Tàu Tết SE1 — Hà Nội → Sài Gòn · Ngồi Mềm · Flash 85%', 'Ngồi mềm điều hòa — lựa chọn phổ biến nhất. Giảm 85% giờ vàng.', 50, 50, 0, 650000, 97500, '2025-01-20 12:00:00', '2025-01-20 12:05:00', 1, 3, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('Tàu Tết SE1 — Hà Nội → Sài Gòn · Nằm Khoang 4 · Flash 80%', 'Khoang 4 giường — tiện nghi cho hành trình 30 tiếng. Giảm 80%.', 20, 20, 0, 1400000, 280000, '2025-01-20 12:00:00', '2025-01-20 12:05:00', 1, 3, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('Tàu Tết SE3 — Hà Nội → Huế · Ngồi Mềm · Flash 85%', 'Tuyến ngắn HN-Huế. Phù hợp người dân miền Trung về quê.', 100, 100, 0, 450000, 67500, '2025-01-20 12:00:00', '2025-01-20 12:05:00', 1, 3, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('Tàu Tết SE3 — Hà Nội → Huế · Nằm Khoang 6 · Flash 80%', 'Khoang 6 giường tiết kiệm. Tuyến HN-Huế.', 80, 80, 0, 750000, 150000, '2025-01-20 12:00:00', '2025-01-20 12:05:00', 1, 3, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),

    -- --- Event 4: Lễ 30/4-1/5 (activity_id=4) ---
    -- Small stock (100-300): high-contention realistic flash-sale
    ('Tàu Lễ SE2 — Sài Gòn → Nha Trang · Ngồi Mềm · Flash 80%', 'Tuyến du lịch biển hot nhất dịp lễ. Nha Trang — thiên đường nghỉ dưỡng.', 200, 200, 0, 550000, 110000, '2025-04-20 12:00:00', '2025-04-20 12:10:00', 1, 4, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('Tàu Lễ SE2 — Sài Gòn → Nha Trang · Nằm Khoang 4 · Flash 75%', 'Khoang 4 giường — thoải mái cho chuyến đi 8 tiếng.', 150, 150, 0, 950000, 237500, '2025-04-20 12:00:00', '2025-04-20 12:10:00', 1, 4, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('Tàu Lễ SE4 — Sài Gòn → Phan Thiết · Ngồi Mềm · Flash 80%', 'Tuyến ngắn 4 tiếng đến Mũi Né. Biển xanh cát trắng.', 300, 300, 0, 350000, 70000, '2025-04-20 12:00:00', '2025-04-20 12:10:00', 1, 4, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('Tàu Lễ SE4 — Sài Gòn → Phan Thiết · VIP Sleeper · Flash 70%', 'Khoang VIP 2 giường — trải nghiệm hạng sang.', 100, 100, 0, 1600000, 480000, '2025-04-20 12:00:00', '2025-04-20 12:10:00', 1, 4, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('Tàu Lễ SE6 — Hà Nội → Đà Nẵng · Ngồi Mềm · Flash 80%', 'Tuyến HN-Đà Nẵng. Thành phố đáng sống nhất Việt Nam.', 250, 250, 0, 700000, 140000, '2025-04-20 12:00:00', '2025-04-20 12:10:00', 1, 4, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),

    -- --- Event 5: Hè 2025 (activity_id=5) ---
    -- Medium-large stock: throughput benchmarks
    ('Tàu Hè SE8 — Hà Nội → Đồng Hới · Ngồi Mềm · Flash 75%', 'Đồng Hới — cửa ngõ vào Phong Nha Kẻ Bàng. Di sản thiên nhiên thế giới.', 500, 500, 0, 500000, 125000, '2025-06-10 10:00:00', '2025-06-10 10:15:00', 1, 5, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('Tàu Hè SE8 — Hà Nội → Đồng Hới · Nằm Khoang 4 · Flash 70%', 'Khoang 4 giường. Tiện nghi cho gia đình đi Phong Nha.', 300, 300, 0, 850000, 255000, '2025-06-10 10:00:00', '2025-06-10 10:15:00', 1, 5, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('Tàu Hè SE10 — Sài Gòn → Nha Trang · Ngồi Mềm · Flash 75%', 'Đường bờ biển đẹp nhất Việt Nam. Hè này đi Nha Trang.', 800, 800, 0, 550000, 137500, '2025-06-10 10:00:00', '2025-06-10 10:15:00', 1, 5, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('Tàu Hè SE10 — Sài Gòn → Nha Trang · VIP Sleeper · Flash 65%', 'Trải nghiệm VIP cho kỳ nghỉ hè đáng nhớ.', 200, 200, 0, 1800000, 630000, '2025-06-10 10:00:00', '2025-06-10 10:15:00', 1, 5, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('Tàu Hè SE12 — Đà Nẵng → Nha Trang · Ngồi Mềm · Flash 75%', 'Tuyến ven biển miền Trung. Hai thiên đường du lịch.', 400, 400, 0, 400000, 100000, '2025-06-10 10:00:00', '2025-06-10 10:15:00', 1, 5, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('Tàu Hè SE14 — Hà Nội → Sài Gòn · Nằm Khoang 6 · Flash 70%', 'Hành trình Bắc-Nam trọn vẹn. Khám phá Việt Nam từ Hà Nội đến Sài Gòn.', 600, 600, 0, 1100000, 330000, '2025-06-10 10:00:00', '2025-06-10 10:15:00', 1, 5, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),

    -- --- Event 6: Quốc Khánh 2/9 (activity_id=6) ---
    -- Mixed stock: short-holiday flash-sale simulation
    ('Tàu Lễ 2/9 SE16 — Hà Nội → Vinh · Ngồi Mềm · Flash 80%', 'Vinh — thành phố quê Bác. Tuyến ngắn 5 tiếng dịp Quốc Khánh.', 150, 150, 0, 300000, 60000, '2025-08-20 12:00:00', '2025-08-20 12:10:00', 1, 6, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('Tàu Lễ 2/9 SE16 — Hà Nội → Vinh · Nằm Khoang 4 · Flash 75%', 'Tiện nghi cho gia đình về thăm quê Bác dịp 2/9.', 100, 100, 0, 600000, 150000, '2025-08-20 12:00:00', '2025-08-20 12:10:00', 1, 6, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('Tàu Lễ 2/9 SE18 — Sài Gòn → Phan Thiết · Ngồi Mềm · Flash 80%', 'Kỳ nghỉ ngắn lý tưởng. Biển Mũi Né chỉ cách 4 tiếng.', 200, 200, 0, 350000, 70000, '2025-08-20 12:00:00', '2025-08-20 12:10:00', 1, 6, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('Tàu Lễ 2/9 SE20 — Đà Nẵng → Huế · Ngồi Mềm · Flash 85%', 'Hành trình di sản. Huế — cố đô mộng mơ.', 120, 120, 0, 150000, 22500, '2025-08-20 12:00:00', '2025-08-20 12:10:00', 1, 6, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),

    -- --- Event 7: Tết Dương Lịch 2026 (activity_id=7) ---
    ('Tàu Tết Tây SE22 — Hà Nội → Sài Gòn · Nằm Khoang 4 · Flash 70%', 'Chào năm mới 2026 trên tàu Bắc-Nam. Trải nghiệm độc đáo.', 200, 200, 0, 1400000, 420000, '2025-12-20 12:00:00', '2025-12-20 12:10:00', 1, 7, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('Tàu Tết Tây SE22 — Hà Nội → Sài Gòn · VIP Sleeper · Flash 60%', 'Khoang VIP đón năm mới. Champagne và tiệc nhẹ trên tàu.', 50, 50, 0, 2500000, 1000000, '2025-12-20 12:00:00', '2025-12-20 12:10:00', 1, 7, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('Tàu Tết Tây SE24 — Sài Gòn → Nha Trang · Ngồi Mềm · Flash 75%', 'Đón bình minh năm mới trên biển Nha Trang.', 300, 300, 0, 550000, 137500, '2025-12-20 12:00:00', '2025-12-20 12:10:00', 1, 7, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),

    -- --- Event 8: Tết Nguyên Đán Bính Ngọ 2026 (activity_id=8) ---
    -- Large stock: throughput capacity testing at scale
    ('Tàu Tết 2026 SE1 — Hà Nội → Sài Gòn · Ngồi Cứng · Flash 90%', 'Đợt về quê lớn nhất năm. Siêu giảm giá 90% vé ngồi cứng.', 100, 100, 0, 400000, 40000, '2026-01-15 12:00:00', '2026-01-15 12:15:00', 1, 8, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('Tàu Tết 2026 SE1 — Hà Nội → Sài Gòn · Ngồi Mềm · Flash 85%', 'Vé ngồi mềm Tết — lựa chọn của đa số.', 500, 500, 0, 700000, 105000, '2026-01-15 12:00:00', '2026-01-15 12:15:00', 1, 8, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('Tàu Tết 2026 SE1 — Hà Nội → Sài Gòn · Nằm Khoang 6 · Flash 80%', 'Tiết kiệm với khoang 6 giường. Hành trình 30 tiếng.', 300, 300, 0, 1200000, 240000, '2026-01-15 12:00:00', '2026-01-15 12:15:00', 1, 8, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('Tàu Tết 2026 SE1 — Hà Nội → Sài Gòn · Nằm Khoang 4 · Flash 75%', 'Khoang 4 giường riêng tư. Lựa chọn gia đình.', 200, 200, 0, 1500000, 375000, '2026-01-15 12:00:00', '2026-01-15 12:15:00', 1, 8, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('Tàu Tết 2026 SE1 — Hà Nội → Sài Gòn · VIP Sleeper · Flash 65%', 'Hạng sang VIP — trải nghiệm đỉnh cao ngày Tết.', 50, 50, 0, 2800000, 980000, '2026-01-15 12:00:00', '2026-01-15 12:15:00', 1, 8, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    -- Large stock items for throughput benchmarks
    ('Tàu Tết 2026 SE3 — Hà Nội → Đà Nẵng · Ngồi Mềm · Flash 85%', 'Tuyến HN-ĐN dịp Tết. Sức chứa lớn.', 5000, 5000, 0, 750000, 112500, '2026-01-15 12:00:00', '2026-01-15 12:15:00', 1, 8, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('Tàu Tết 2026 SE5 — Sài Gòn → Hà Nội · Nằm Khoang 6 · Flash 80%', 'Chiều ngược lại. Người miền Nam ra Bắc ăn Tết.', 3000, 3000, 0, 1200000, 240000, '2026-01-15 12:00:00', '2026-01-15 12:15:00', 1, 8, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('Tàu Tết 2026 SE7 — Sài Gòn → Huế · Ngồi Mềm · Flash 85%', 'Tuyến SG-Huế. Cố đô dịp Tết cổ truyền.', 10000, 10000, 0, 650000, 97500, '2026-01-15 12:00:00', '2026-01-15 12:15:00', 1, 8, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

-- ============================================================================
-- 3. ticket_order table — monthly partitioned order storage
-- ============================================================================
CREATE TABLE IF NOT EXISTS `vetautet`.`ticket_order_202502` (
    id INT(8) NOT NULL AUTO_INCREMENT COMMENT 'Unique ticket sales ID',
    user_id INT(8) NOT NULL  COMMENT 'userId',
    order_number VARCHAR(50) NOT NULL COMMENT 'Unique order number',
    total_amount DECIMAL(10,3) NOT NULL COMMENT 'Total order amount',
    terminal_id VARCHAR(20) NOT NULL COMMENT 'ID of the sales terminal',
    order_date TIMESTAMP NOT NULL COMMENT 'Date and time of the ticket order request',
    order_notes VARCHAR(100) NULL DEFAULT 'None' COMMENT 'Additional notes for the order',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Timestamp of the last update',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Creation timestamp',
    PRIMARY KEY (id) USING BTREE,
    UNIQUE KEY order_number (order_number),
    KEY order_date (order_date),
    KEY index_usr_id (user_id)
    ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = 'order table';

-- ============================================================================
-- 4. ticket_order_details table — order line items
-- ============================================================================
CREATE TABLE IF NOT EXISTS `vetautet`.`ticket_order_details_202502` (
 id INT(8) NOT NULL AUTO_INCREMENT COMMENT 'Unique ticket sales ID',
 ticket_item_id BIGINT(20) NOT NULL COMMENT 'ticket detail ID',
 order_number VARCHAR(50) NOT NULL COMMENT 'Reference to the order number',
 passenger_name VARCHAR(100) NOT NULL COMMENT 'Passenger full name',
 passenger_id VARCHAR(20) NOT NULL COMMENT 'National ID or passport number',
 departure_station VARCHAR(10) NOT NULL COMMENT 'Departure station code (e.g., SGN)',
 arrival_station VARCHAR(10) NOT NULL COMMENT 'Arrival station code (e.g., HAN)',
 departure_time DATETIME NOT NULL COMMENT 'Train departure time',
 seat_class ENUM('Economy', 'Business', 'First') NOT NULL COMMENT 'Seat class type',
 seat_number VARCHAR(10) NOT NULL COMMENT 'Seat number',
 ticket_price DECIMAL(10,3) NOT NULL COMMENT 'Price of the individual ticket',
 updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Timestamp of the last update',
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Creation timestamp',
 PRIMARY KEY (id) USING BTREE,
--  FOREIGN KEY (order_number) REFERENCES ticket_order_202502(order_number) ON DELETE CASCADE,
 KEY order_number (order_number),
 KEY ticket_item_id (ticket_item_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = 'order table';

-- ============================================================================
-- SEED DATA — Sample Orders
-- ============================================================================
-- Realistic Vietnamese passenger names and train journeys.
-- Stations: HAN, SGN, DNG, HUE, NTR, PYC, VII, DTH
-- Seat classes mapped: Economy -> Ngồi Cứng/Mềm, Business -> Nằm Khoang 4/6, First -> VIP
-- ============================================================================

-- --- Order 1: Family trip (original — kept for backward compat) ---
INSERT INTO `vetautet`.`ticket_order_202502` (order_number, user_id, total_amount, terminal_id, order_date, order_notes)
VALUES ('ORD2025020001', 1001, 5.600, 'POS001', '2025-02-28 10:00:00', 'Family trip');

INSERT INTO `vetautet`.`ticket_order_details_202502` (ticket_item_id, order_number, passenger_name, passenger_id, departure_station, arrival_station, departure_time, seat_class, seat_number, ticket_price)
VALUES
    (4,'ORD2025020001', 'Nguyen Van A', 'ID12345678', 'SGN', 'HAN', '2025-03-01 08:00:00', 'Economy', 'A1', 1.400),
    (4,'ORD2025020001', 'Nguyen Van B', 'ID12345679', 'SGN', 'HAN', '2025-03-01 08:00:00', 'Economy', 'A2', 1.400),
    (4,'ORD2025020001', 'Nguyen Van C', 'ID12345680', 'SGN', 'HAN', '2025-03-01 08:00:00', 'Economy', 'A3', 1.400),
    (4,'ORD2025020001', 'Nguyen Van D', 'ID12345681', 'SGN', 'HAN', '2025-03-01 08:00:00', 'Economy', 'A4', 1.400);

-- --- Order 2: Couple — SGN -> Nha Trang beach getaway ---
INSERT INTO `vetautet`.`ticket_order_202502` (order_number, user_id, total_amount, terminal_id, order_date, order_notes)
VALUES ('ORD2025020002', 1002, 0.475, 'WEB001', '2025-02-28 14:30:00', 'Kỳ nghỉ cuối tuần Nha Trang');

INSERT INTO `vetautet`.`ticket_order_details_202502` (ticket_item_id, order_number, passenger_name, passenger_id, departure_station, arrival_station, departure_time, seat_class, seat_number, ticket_price)
VALUES
    (10,'ORD2025020002', 'Trần Thị Mai', 'ID23456789', 'SGN', 'NTR', '2025-04-25 06:00:00', 'Business', 'B1', 0.237500),
    (10,'ORD2025020002', 'Lê Văn Hùng', 'ID23456790', 'SGN', 'NTR', '2025-04-25 06:00:00', 'Business', 'B2', 0.237500);

-- --- Order 3: Solo traveler — Hà Nội -> Đà Nẵng ---
INSERT INTO `vetautet`.`ticket_order_202502` (order_number, user_id, total_amount, terminal_id, order_date, order_notes)
VALUES ('ORD2025020003', 1003, 0.140, 'APP_MOBILE', '2025-03-01 08:15:00', 'Du lịch một mình Đà Nẵng');

INSERT INTO `vetautet`.`ticket_order_details_202502` (ticket_item_id, order_number, passenger_name, passenger_id, departure_station, arrival_station, departure_time, seat_class, seat_number, ticket_price)
VALUES
    (14,'ORD2025020003', 'Phạm Thanh Tùng', 'ID34567890', 'HAN', 'DNG', '2025-04-26 20:00:00', 'Economy', 'C5', 0.140);

-- --- Order 4: Group of friends — Hà Nội -> Huế (Tết) ---
INSERT INTO `vetautet`.`ticket_order_202502` (order_number, user_id, total_amount, terminal_id, order_date, order_notes)
VALUES ('ORD2025020004', 1004, 0.435, 'WEB001', '2025-03-01 09:00:00', 'Hội bạn thân về Huế ăn Tết');

INSERT INTO `vetautet`.`ticket_order_details_202502` (ticket_item_id, order_number, passenger_name, passenger_id, departure_station, arrival_station, departure_time, seat_class, seat_number, ticket_price)
VALUES
    (8,'ORD2025020004', 'Hoàng Minh Đức', 'ID45678901', 'HAN', 'HUE', '2025-01-20 18:00:00', 'Economy', 'D1', 0.067500),
    (8,'ORD2025020004', 'Ngô Thị Lan', 'ID45678902', 'HAN', 'HUE', '2025-01-20 18:00:00', 'Economy', 'D2', 0.067500),
    (9,'ORD2025020004', 'Vũ Đình Nam', 'ID45678903', 'HAN', 'HUE', '2025-01-20 18:00:00', 'Business', 'D3', 0.150000),
    (9,'ORD2025020004', 'Đặng Thị Hoa', 'ID45678904', 'HAN', 'HUE', '2025-01-20 18:00:00', 'Business', 'D4', 0.150000);

-- --- Order 5: VIP experience — SGN -> Phan Thiết ---
INSERT INTO `vetautet`.`ticket_order_202502` (order_number, user_id, total_amount, terminal_id, order_date, order_notes)
VALUES ('ORD2025020005', 1005, 0.960, 'APP_MOBILE', '2025-03-02 11:00:00', 'Trải nghiệm VIP Mũi Né — quà kỷ niệm cưới');

INSERT INTO `vetautet`.`ticket_order_details_202502` (ticket_item_id, order_number, passenger_name, passenger_id, departure_station, arrival_station, departure_time, seat_class, seat_number, ticket_price)
VALUES
    (13,'ORD2025020005', 'Bùi Quang Hải', 'ID56789012', 'SGN', 'PYC', '2025-04-25 08:00:00', 'First', 'E1', 0.480000),
    (13,'ORD2025020005', 'Nguyễn Thị Thảo', 'ID56789013', 'SGN', 'PYC', '2025-04-25 08:00:00', 'First', 'E2', 0.480000);

-- --- Order 6: Sinh viên về quê — Hà Nội -> Vinh ---
INSERT INTO `vetautet`.`ticket_order_202502` (order_number, user_id, total_amount, terminal_id, order_date, order_notes)
VALUES ('ORD2025020006', 1006, 0.120, 'WEB001', '2025-03-02 15:00:00', 'Sinh viên về thăm nhà dịp Quốc Khánh');

INSERT INTO `vetautet`.`ticket_order_details_202502` (ticket_item_id, order_number, passenger_name, passenger_id, departure_station, arrival_station, departure_time, seat_class, seat_number, ticket_price)
VALUES
    (20,'ORD2025020006', 'Đỗ Văn Toàn', 'ID67890123', 'HAN', 'VII', '2025-08-28 22:00:00', 'Economy', 'F1', 0.060000),
    (20,'ORD2025020006', 'Trịnh Thị Ngọc', 'ID67890124', 'HAN', 'VII', '2025-08-28 22:00:00', 'Economy', 'F2', 0.060000);

-- --- Order 7: Cả nhà đi Phong Nha ---
INSERT INTO `vetautet`.`ticket_order_202502` (order_number, user_id, total_amount, terminal_id, order_date, order_notes)
VALUES ('ORD2025020007', 1007, 1.020, 'POS002', '2025-03-03 08:00:00', 'Gia đình 4 người khám phá Phong Nha Kẻ Bàng');

INSERT INTO `vetautet`.`ticket_order_details_202502` (ticket_item_id, order_number, passenger_name, passenger_id, departure_station, arrival_station, departure_time, seat_class, seat_number, ticket_price)
VALUES
    (15,'ORD2025020007', 'Lý Văn Phúc', 'ID78901234', 'HAN', 'DTH', '2025-06-15 07:00:00', 'Business', 'G1', 0.255000),
    (15,'ORD2025020007', 'Trương Thị Hạnh', 'ID78901235', 'HAN', 'DTH', '2025-06-15 07:00:00', 'Business', 'G2', 0.255000),
    (15,'ORD2025020007', 'Lý Minh Anh', 'ID78901236', 'HAN', 'DTH', '2025-06-15 07:00:00', 'Business', 'G3', 0.255000),
    (15,'ORD2025020007', 'Lý Tuấn Kiệt', 'ID78901237', 'HAN', 'DTH', '2025-06-15 07:00:00', 'Business', 'G4', 0.255000);

-- --- Order 8: Đà Nẵng -> Huế day trip ---
INSERT INTO `vetautet`.`ticket_order_202502` (order_number, user_id, total_amount, terminal_id, order_date, order_notes)
VALUES ('ORD2025020008', 1008, 0.045, 'APP_MOBILE', '2025-03-03 16:00:00', 'Du lịch di sản Huế trong ngày');

INSERT INTO `vetautet`.`ticket_order_details_202502` (ticket_item_id, order_number, passenger_name, passenger_id, departure_station, arrival_station, departure_time, seat_class, seat_number, ticket_price)
VALUES
    (23,'ORD2025020008', 'Mai Thị Hồng', 'ID89012345', 'DNG', 'HUE', '2025-08-28 06:00:00', 'Economy', 'H1', 0.022500),
    (23,'ORD2025020008', 'Mai Văn Dũng', 'ID89012346', 'DNG', 'HUE', '2025-08-28 06:00:00', 'Economy', 'H2', 0.022500);

-- --- Order 9: Tết Tây VIP experience ---
INSERT INTO `vetautet`.`ticket_order_202502` (order_number, user_id, total_amount, terminal_id, order_date, order_notes)
VALUES ('ORD2025020009', 1009, 2.000, 'WEB001', '2025-03-04 10:00:00', 'Đón năm mới 2026 trên tàu — trải nghiệm có 1-0-2');

INSERT INTO `vetautet`.`ticket_order_details_202502` (ticket_item_id, order_number, passenger_name, passenger_id, departure_station, arrival_station, departure_time, seat_class, seat_number, ticket_price)
VALUES
    (25,'ORD2025020009', 'Đinh Cao Sơn', 'ID90123456', 'HAN', 'SGN', '2025-12-28 19:00:00', 'First', 'I1', 1.000000),
    (25,'ORD2025020009', 'Phan Thị Yến', 'ID90123457', 'HAN', 'SGN', '2025-12-28 19:00:00', 'First', 'I2', 1.000000);

-- --- Order 10: Nhóm bạn Nha Trang ---
INSERT INTO `vetautet`.`ticket_order_202502` (order_number, user_id, total_amount, terminal_id, order_date, order_notes)
VALUES ('ORD2025020010', 1010, 0.550, 'APP_MOBILE', '2025-03-04 13:00:00', 'Hội bạn thân 4 người — Nha Trang hè 2025');

INSERT INTO `vetautet`.`ticket_order_details_202502` (ticket_item_id, order_number, passenger_name, passenger_id, departure_station, arrival_station, departure_time, seat_class, seat_number, ticket_price)
VALUES
    (17,'ORD2025020010', 'Hồ Văn Lâm', 'ID01234567', 'SGN', 'NTR', '2025-06-20 06:00:00', 'Economy', 'J1', 0.137500),
    (17,'ORD2025020010', 'Tô Thị Thắm', 'ID01234568', 'SGN', 'NTR', '2025-06-20 06:00:00', 'Economy', 'J2', 0.137500),
    (17,'ORD2025020010', 'Hồ Thanh Sơn', 'ID01234569', 'SGN', 'NTR', '2025-06-20 06:00:00', 'Economy', 'J3', 0.137500),
    (17,'ORD2025020010', 'Lâm Thị Nguyệt', 'ID01234570', 'SGN', 'NTR', '2025-06-20 06:00:00', 'Economy', 'J4', 0.137500);
