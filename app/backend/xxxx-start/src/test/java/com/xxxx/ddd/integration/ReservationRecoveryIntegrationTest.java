package com.xxxx.ddd.integration;

import com.xxxx.StartApplication;
import com.xxxx.ddd.application.MQ.OutboxPublishScheduler;
import com.xxxx.ddd.application.reservation.ReservationExpiryScheduler;
import com.xxxx.ddd.application.reservation.ReservationRecoveryScheduler;
import com.xxxx.ddd.application.reservation.ReservationRecoveryService;
import com.xxxx.ddd.application.reservation.port.OperationJournalRepository;
import com.xxxx.ddd.application.reservation.port.ReservationStockPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@EnabledIfSystemProperty(named = "flashsale.integration", matches = "true")
@SpringBootTest(
        classes = StartApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "debug=false",
                "logging.level.root=WARN",
                "logging.level.com.xxxx=WARN",
                "logging.level.org.springframework=WARN",
                "spring.jpa.show-sql=false",
                "spring.task.scheduling.enabled=false"
        })
class ReservationRecoveryIntegrationTest {

    private static final long TICKET_ITEM_ID = 910_002L;
    private static final int INITIAL_STOCK = 10;
    private static final int HELD_QUANTITY = 3;
    private static final UUID RESERVATION_ID = UUID.fromString("2f03a82c-ab45-4f87-b8ea-3d407198f5c2");
    private static final UUID OPERATION_ID = UUID.fromString("f0f30dd9-2f8e-4d35-9c1f-5c6c3ee08d7c");
    private static final String STOCK_KEY = "flashsale:reservation:stock:" + TICKET_ITEM_ID;
    private static final long STALE_TICKET_ITEM_ID = 910_003L;
    private static final UUID STALE_RESERVATION_ID = UUID.fromString("0c3ab8a3-1110-4d8d-a7a2-92aaf1c66d11");
    private static final UUID STALE_OPERATION_ID = UUID.fromString("f8f9af2b-42d0-4aa1-b71b-dc5b2a8f0f50");
    private static final String STALE_STOCK_KEY = "flashsale:reservation:stock:" + STALE_TICKET_ITEM_ID;

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("vetautet");

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @MockBean
    private OutboxPublishScheduler outboxScheduler;

    @MockBean
    private ReservationRecoveryScheduler recoveryScheduler;

    @MockBean
    private ReservationExpiryScheduler expiryScheduler;

    @Autowired
    private ReservationRecoveryService recovery;

    @Autowired
    private ReservationStockPort stock;

    @Autowired
    private OperationJournalRepository journal;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private StringRedisTemplate redis;

    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> MYSQL.getJdbcUrl() + "?serverTimezone=UTC");
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("app.redisson.single-address",
                () -> "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:9094");
    }

    @BeforeEach
    void seedPendingMirror() {
        jdbc.update("DELETE FROM inventory_operation_journal WHERE ticket_item_id = ?", TICKET_ITEM_ID);
        jdbc.update("DELETE FROM outbox_event WHERE aggregate_id = ?", RESERVATION_ID.toString());
        jdbc.update("DELETE FROM inventory_reservation WHERE ticket_item_id = ?", TICKET_ITEM_ID);
        jdbc.update("DELETE FROM inventory_stock_account WHERE ticket_item_id = ?", TICKET_ITEM_ID);
        jdbc.update("DELETE FROM ticket_item WHERE id = ?", TICKET_ITEM_ID);
        redis.delete(STOCK_KEY);
        redis.delete(operationKey(OPERATION_ID));

        jdbc.update("INSERT INTO ticket_item "
                        + "(id, name, description, stock_initial, stock_available, is_stock_prepared, "
                        + "price_original, price_flash, sale_start_time, sale_end_time, status, activity_id) "
                        + "VALUES (?, 'recovery-integration', 'recovery-integration', ?, ?, FALSE, 100, 50, "
                        + "'2025-01-01 00:00:00', '2030-01-01 00:00:00', 1, 1)",
                TICKET_ITEM_ID, INITIAL_STOCK, INITIAL_STOCK);
        jdbc.update("INSERT INTO inventory_stock_account "
                        + "(ticket_item_id, initial_quantity, available_quantity, admission_state, fence_version, version) "
                        + "VALUES (?, ?, ?, 'OPEN', 0, 1)",
                TICKET_ITEM_ID, INITIAL_STOCK, INITIAL_STOCK - HELD_QUANTITY);
        jdbc.update("INSERT INTO inventory_operation_journal "
                        + "(operation_id, reservation_id, operation_type, state, ticket_item_id, quantity, "
                        + "request_fingerprint, fence_version, attempts) "
                        + "VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), 'RELEASE', 'MIRROR_PENDING', ?, ?, UNHEX(?), 0, 1)",
                OPERATION_ID.toString(), RESERVATION_ID.toString(), TICKET_ITEM_ID, HELD_QUANTITY, "a".repeat(64));

        redis.opsForHash().putAll(STOCK_KEY, Map.of(
                "initial", Integer.toString(INITIAL_STOCK),
                "available", Integer.toString(INITIAL_STOCK - HELD_QUANTITY),
                "reserved", Integer.toString(HELD_QUANTITY),
                "confirmed", "0",
                "fence", "0",
                "admission_state", "OPEN"));
    }

    @Test
    void recoversPendingTerminalMirrorIntoCommittedJournalAndRedisState() {
        seedAppliedCreateOperation(OPERATION_ID);
        OperationJournalRepository.JournalEntry entry = OperationJournalRepository.JournalEntry.terminal(
                OPERATION_ID,
                RESERVATION_ID,
                OperationJournalRepository.OperationType.RELEASE,
                "a".repeat(64),
                TICKET_ITEM_ID,
                HELD_QUANTITY,
                0,
                OperationJournalRepository.JournalState.MIRROR_PENDING);

        recovery.recover(entry);

        assertThat(jdbc.queryForObject(
                "SELECT state FROM inventory_operation_journal WHERE operation_id = UUID_TO_BIN(?)",
                String.class,
                OPERATION_ID.toString())).isEqualTo("COMMITTED");
        assertThat(redis.opsForHash().get(STOCK_KEY, "available"))
                .isEqualTo(Integer.toString(INITIAL_STOCK));
        assertThat(redis.opsForHash().get(operationKey(OPERATION_ID), "state"))
                .isEqualTo("MIRRORED");
    }

    @Test
    void recoversCreateAfterCrashBeforeRedisAndCommitsDurableReservation() {
        seedCreateRecoveryState("RECEIVED");

        recovery.recover(journal.findByOperationId(OPERATION_ID).orElseThrow());

        assertThat(journal.findByOperationId(OPERATION_ID).orElseThrow().state())
                .isEqualTo(OperationJournalRepository.JournalState.COMMITTED);
        assertThat(jdbc.queryForObject(
                "SELECT status FROM inventory_reservation WHERE id = UUID_TO_BIN(?)",
                String.class,
                RESERVATION_ID.toString())).isEqualTo("RESERVED");
        assertThat(jdbc.queryForObject(
                "SELECT available_quantity FROM inventory_stock_account WHERE ticket_item_id = ?",
                Integer.class,
                TICKET_ITEM_ID)).isEqualTo(INITIAL_STOCK - HELD_QUANTITY);
        assertThat(redis.opsForHash().get(STOCK_KEY, "available"))
                .isEqualTo(Integer.toString(INITIAL_STOCK - HELD_QUANTITY));
        assertThat(redis.opsForHash().get(operationKey(OPERATION_ID), "state"))
                .isEqualTo("APPLIED");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_event WHERE aggregate_id = ? AND event_type = ?",
                Integer.class,
                RESERVATION_ID.toString(),
                "reservation.created")).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT payload FROM outbox_event WHERE aggregate_id = ? AND event_type = ?",
                String.class,
                RESERVATION_ID.toString(),
                "reservation.created")).contains("\"expiresAt\"");
    }

    @Test
    void recoversCreateAfterRedisBeforeDatabaseAndFinalizesTheDurableReservation() {
        seedCreateRecoveryState("REDIS_APPLIED");
        seedAppliedCreateOperation(OPERATION_ID);

        recovery.recover(journal.findByOperationId(OPERATION_ID).orElseThrow());

        assertThat(journal.findByOperationId(OPERATION_ID).orElseThrow().state())
                .isEqualTo(OperationJournalRepository.JournalState.COMMITTED);
        assertThat(jdbc.queryForObject(
                "SELECT status FROM inventory_reservation WHERE id = UUID_TO_BIN(?)",
                String.class,
                RESERVATION_ID.toString())).isEqualTo("RESERVED");
        assertThat(jdbc.queryForObject(
                "SELECT available_quantity FROM inventory_stock_account WHERE ticket_item_id = ?",
                Integer.class,
                TICKET_ITEM_ID)).isEqualTo(INITIAL_STOCK - HELD_QUANTITY);
        assertThat(redis.opsForHash().get(operationKey(OPERATION_ID), "state"))
                .isEqualTo("APPLIED");
    }

    @Test
    void staleFenceRepairUsesNewFenceAndConvergesDurableSnapshot() {
        seedStaleMirror();

        OperationJournalRepository.JournalEntry pending = journal
                .findByOperationId(STALE_OPERATION_ID)
                .orElseThrow();
        recovery.recover(pending);

        OperationJournalRepository.JournalEntry repairRequired = journal
                .findByOperationId(STALE_OPERATION_ID)
                .orElseThrow();
        assertThat(repairRequired.state())
                .isEqualTo(OperationJournalRepository.JournalState.REPAIR_REQUIRED);
        assertThat(journal.repairId(STALE_OPERATION_ID)).isPresent();

        recovery.recover(repairRequired);

        assertThat(jdbc.queryForObject(
                "SELECT state FROM inventory_operation_journal WHERE operation_id = UUID_TO_BIN(?)",
                String.class,
                STALE_OPERATION_ID.toString())).isEqualTo("COMMITTED");
        assertThat(jdbc.queryForObject(
                "SELECT repair_id IS NOT NULL FROM inventory_operation_journal "
                        + "WHERE operation_id = UUID_TO_BIN(?)",
                Boolean.class,
                STALE_OPERATION_ID.toString())).isTrue();
        assertThat(jdbc.queryForObject(
                "SELECT state FROM inventory_repair_journal WHERE ticket_item_id = ?",
                String.class,
                STALE_TICKET_ITEM_ID)).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject(
                "SELECT admission_state FROM inventory_stock_account WHERE ticket_item_id = ?",
                String.class,
                STALE_TICKET_ITEM_ID)).isEqualTo("OPEN");
        assertThat(jdbc.queryForObject(
                "SELECT fence_version FROM inventory_stock_account WHERE ticket_item_id = ?",
                Long.class,
                STALE_TICKET_ITEM_ID)).isEqualTo(2L);
        assertThat(redis.opsForHash().get(STALE_STOCK_KEY, "available")).isEqualTo("10");
        assertThat(redis.opsForHash().get(STALE_STOCK_KEY, "reserved")).isEqualTo("0");
        assertThat(redis.opsForHash().get(STALE_STOCK_KEY, "fence")).isEqualTo("2");
        assertThat(redis.opsForHash().get(STALE_STOCK_KEY, "admission_state")).isEqualTo("OPEN");
        assertThat(redis.opsForHash().get("flashsale:reservation:op:" + journal.repairId(STALE_OPERATION_ID).orElseThrow(), "state"))
                .isEqualTo("REPAIRED");
    }

    private void seedStaleMirror() {
        jdbc.update("DELETE FROM inventory_operation_journal WHERE ticket_item_id = ?", STALE_TICKET_ITEM_ID);
        jdbc.update("DELETE FROM inventory_repair_journal WHERE ticket_item_id = ?", STALE_TICKET_ITEM_ID);
        jdbc.update("DELETE FROM inventory_reservation WHERE ticket_item_id = ?", STALE_TICKET_ITEM_ID);
        jdbc.update("DELETE FROM inventory_stock_account WHERE ticket_item_id = ?", STALE_TICKET_ITEM_ID);
        jdbc.update("DELETE FROM ticket_item WHERE id = ?", STALE_TICKET_ITEM_ID);
        redis.delete(STALE_STOCK_KEY);
        redis.delete("flashsale:reservation:op:" + STALE_OPERATION_ID);

        jdbc.update("INSERT INTO ticket_item "
                        + "(id, name, description, stock_initial, stock_available, is_stock_prepared, "
                        + "price_original, price_flash, sale_start_time, sale_end_time, status, activity_id) "
                        + "VALUES (?, 'stale-repair', 'stale-repair', 10, 10, FALSE, 100, 50, "
                        + "'2025-01-01 00:00:00', '2030-01-01 00:00:00', 1, 1)",
                STALE_TICKET_ITEM_ID);
        jdbc.update("INSERT INTO inventory_stock_account "
                        + "(ticket_item_id, initial_quantity, available_quantity, admission_state, fence_version, version) "
                        + "VALUES (?, 10, 10, 'OPEN', 1, 1)", STALE_TICKET_ITEM_ID);
        jdbc.update("INSERT INTO inventory_reservation "
                        + "(id, ticket_item_id, demo_actor_id, quantity, status, expires_at, "
                        + "idempotency_key_hash, request_fingerprint) "
                        + "VALUES (UUID_TO_BIN(?), ?, ?, 3, 'RELEASED', '2030-01-01 00:00:00', UNHEX(?), UNHEX(?))",
                STALE_RESERVATION_ID.toString(), STALE_TICKET_ITEM_ID,
                "73e4b5c9-1d67-4d3a-8db8-2e3e21c27844", "c".repeat(64), "d".repeat(64));
        jdbc.update("INSERT INTO inventory_operation_journal "
                        + "(operation_id, reservation_id, operation_type, state, ticket_item_id, quantity, "
                        + "request_fingerprint, fence_version, attempts) "
                        + "VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), 'RELEASE', 'MIRROR_PENDING', ?, 3, UNHEX(?), 0, 1)",
                STALE_OPERATION_ID.toString(), STALE_RESERVATION_ID.toString(), STALE_TICKET_ITEM_ID, "e".repeat(64));
        redis.opsForHash().putAll(STALE_STOCK_KEY, Map.of(
                "initial", "10",
                "available", "7",
                "reserved", "3",
                "confirmed", "0",
                "fence", "0",
                "admission_state", "OPEN"));
    }

    private void seedCreateRecoveryState(String state) {
        jdbc.update("DELETE FROM inventory_operation_journal WHERE ticket_item_id = ?", TICKET_ITEM_ID);
        jdbc.update("DELETE FROM inventory_reservation WHERE ticket_item_id = ?", TICKET_ITEM_ID);
        jdbc.update("DELETE FROM outbox_event WHERE aggregate_id = ?", RESERVATION_ID.toString());
        jdbc.update("UPDATE inventory_stock_account SET available_quantity = ?, version = version + 1 "
                        + "WHERE ticket_item_id = ?",
                INITIAL_STOCK, TICKET_ITEM_ID);
        redis.delete(operationKey(OPERATION_ID));
        redis.opsForHash().put(STOCK_KEY, "available", Integer.toString(INITIAL_STOCK));
        redis.opsForHash().put(STOCK_KEY, "reserved", "0");
        jdbc.update("INSERT INTO inventory_operation_journal "
                        + "(operation_id, reservation_id, operation_type, state, ticket_item_id, quantity, "
                        + "demo_actor_id, idempotency_key_hash, request_fingerprint, fence_version, attempts) "
                        + "VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), 'CREATE', ?, ?, ?, ?, UNHEX(?), UNHEX(?), 0, 1)",
                OPERATION_ID.toString(),
                RESERVATION_ID.toString(),
                state,
                TICKET_ITEM_ID,
                HELD_QUANTITY,
                "73e4b5c9-1d67-4d3a-8db8-2e3e21c27844",
                "a".repeat(64),
                "b".repeat(64));
    }

    private void seedAppliedCreateOperation(UUID operationId) {
        redis.opsForHash().put(STOCK_KEY, "available", Integer.toString(INITIAL_STOCK));
        redis.opsForHash().put(STOCK_KEY, "reserved", "0");
        assertThat(stock.applyOnce(operationId, TICKET_ITEM_ID, HELD_QUANTITY, 0L))
                .isEqualTo(ReservationStockPort.RedisApplyResult.applied(INITIAL_STOCK - HELD_QUANTITY));
    }

    private static String operationKey(UUID operationId) {
        return "flashsale:reservation:op:" + operationId;
    }
}
