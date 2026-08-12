package com.xxxx.ddd.integration;

import com.xxxx.StartApplication;
import com.xxxx.ddd.application.reservation.ConfirmReservationService;
import com.xxxx.ddd.application.reservation.ExpireReservationService;
import com.xxxx.ddd.application.reservation.ReleaseReservationService;
import com.xxxx.ddd.application.reservation.ReservationLifecycleResult;
import com.xxxx.ddd.application.reservation.port.FaultInjectionPort;
import com.xxxx.ddd.application.reservation.port.ReservationStockPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;

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
class ReservationLifecycleServiceIntegrationTest {

    private static final long TICKET_ITEM_ID = 910_001L;
    private static final int INITIAL_STOCK = 10;
    private static final int HELD_QUANTITY = 3;
    private static final UUID ACTOR_ID = UUID.fromString("73e4b5c9-1d67-4d3a-8db8-2e3e21c27844");
    private static final UUID CREATE_OPERATION_ID = UUID.fromString("a6b3a6ce-7efc-426e-9858-89a1ef69ca9f");
    private static final UUID CONFIRM_RESERVATION_ID = UUID.fromString("8f6d72e1-7de1-4ab8-94cb-4eac78b3a10e");
    private static final UUID EXPIRE_RESERVATION_ID = UUID.fromString("d8c66b77-85b1-46e8-a439-29b2d1515b9d");
    private static final UUID RACE_RESERVATION_ID = UUID.fromString("7c1d6c5a-2bca-43b7-9f72-e03bb6f1e9ea");
    private static final UUID RACE_OPERATION_ID = UUID.fromString("347891d3-50b1-4f9d-9b2a-e682f13756c3");
    private static final UUID EXPIRE_OPERATION_ID = UUID.fromString("f9b4a8e6-c75f-4fd8-8c74-9dcae4a1b0a0");
    private static final String STOCK_KEY = "flashsale:reservation:stock:" + TICKET_ITEM_ID;

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
            .withDatabaseName("vetautet");

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @Autowired
    private ReleaseReservationService release;

    @Autowired
    private ConfirmReservationService confirmation;

    @Autowired
    private ExpireReservationService expiration;

    @MockBean
    private FaultInjectionPort faults;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private ReservationStockPort stock;

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
    void seedDurableAndRedisState() {
        jdbc.update("DELETE FROM outbox_event WHERE aggregate_id IN (?, ?, ?, ?)",
                "reservation-service-integration",
                CONFIRM_RESERVATION_ID.toString(),
                EXPIRE_RESERVATION_ID.toString(),
                RACE_RESERVATION_ID.toString());
        jdbc.update("DELETE FROM reservation_order WHERE ticket_item_id = ?", TICKET_ITEM_ID);
        jdbc.update("DELETE FROM inventory_operation_journal WHERE ticket_item_id = ?", TICKET_ITEM_ID);
        jdbc.update("DELETE FROM inventory_reservation WHERE ticket_item_id = ?", TICKET_ITEM_ID);
        jdbc.update("DELETE FROM inventory_stock_account WHERE ticket_item_id = ?", TICKET_ITEM_ID);
        jdbc.update("DELETE FROM ticket_item WHERE id = ?", TICKET_ITEM_ID);
        redis.delete(STOCK_KEY);
        redis.delete(operationKey(CREATE_OPERATION_ID));
        redis.delete(operationKey(EXPIRE_OPERATION_ID));
        redis.delete(operationKey(RACE_OPERATION_ID));

        jdbc.update("INSERT INTO ticket_item "
                        + "(id, name, description, stock_initial, stock_available, is_stock_prepared, "
                        + "price_original, price_flash, sale_start_time, sale_end_time, status, activity_id) "
                        + "VALUES (?, 'service-integration', 'service-integration', ?, ?, FALSE, 100, 50, "
                        + "'2025-01-01 00:00:00', '2030-01-01 00:00:00', 1, 1)",
                TICKET_ITEM_ID, INITIAL_STOCK, INITIAL_STOCK);
        jdbc.update("INSERT INTO inventory_stock_account "
                        + "(ticket_item_id, initial_quantity, available_quantity, admission_state, fence_version, version) "
                        + "VALUES (?, ?, ?, 'OPEN', 0, 1)",
                TICKET_ITEM_ID, INITIAL_STOCK, INITIAL_STOCK - HELD_QUANTITY);

        redis.opsForHash().putAll(STOCK_KEY, Map.of(
                "initial", Integer.toString(INITIAL_STOCK),
                "available", Integer.toString(INITIAL_STOCK - HELD_QUANTITY),
                "reserved", Integer.toString(HELD_QUANTITY),
                "confirmed", "0",
                "fence", "0",
                "admission_state", "OPEN"));
    }

    @Test
    void releaseServicePersistsStockOutboxJournalAndRedisMirrorTogether() {
        UUID reservationId = UUID.randomUUID();
        jdbc.update("INSERT INTO inventory_reservation "
                        + "(id, ticket_item_id, demo_actor_id, quantity, status, expires_at, terminal_at, "
                        + "idempotency_key_hash, request_fingerprint, version) "
                        + "VALUES (UUID_TO_BIN(?), ?, ?, ?, 'RESERVED', ?, NULL, UNHEX(?), UNHEX(?), 0)",
                reservationId.toString(),
                TICKET_ITEM_ID,
                ACTOR_ID.toString(),
                HELD_QUANTITY,
                Instant.now().plusSeconds(120),
                "1".repeat(64),
                "2".repeat(64));
        jdbc.update("INSERT INTO inventory_operation_journal "
                        + "(operation_id, reservation_id, operation_type, state, ticket_item_id, quantity, "
                        + "demo_actor_id, idempotency_key_hash, request_fingerprint, fence_version, attempts) "
                        + "VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), 'CREATE', 'COMMITTED', ?, ?, ?, UNHEX(?), UNHEX(?), 0, 0)",
                CREATE_OPERATION_ID.toString(),
                reservationId.toString(),
                TICKET_ITEM_ID,
                HELD_QUANTITY,
                ACTOR_ID.toString(),
                "1".repeat(64),
                "2".repeat(64));
        seedAppliedCreateOperation(CREATE_OPERATION_ID);

        ReservationLifecycleResult result = release.release(reservationId);

        assertThat(result.outcome()).isEqualTo(ReservationLifecycleResult.Outcome.RELEASED);
        UUID operationId = result.operationId().orElseThrow();
        assertThat(jdbc.queryForObject(
                "SELECT status FROM inventory_reservation WHERE id = UUID_TO_BIN(?)",
                String.class,
                reservationId.toString())).isEqualTo("RELEASED");
        assertThat(jdbc.queryForObject(
                "SELECT available_quantity FROM inventory_stock_account WHERE ticket_item_id = ?",
                Integer.class,
                TICKET_ITEM_ID)).isEqualTo(INITIAL_STOCK);
        assertThat(jdbc.queryForObject(
                "SELECT state FROM inventory_operation_journal WHERE operation_id = UUID_TO_BIN(?)",
                String.class,
                operationId.toString())).isEqualTo("COMMITTED");
        assertThat(jdbc.queryForObject(
                "SELECT operation_type FROM inventory_operation_journal WHERE operation_id = UUID_TO_BIN(?)",
                String.class,
                operationId.toString())).isEqualTo("CREATE");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_event WHERE aggregate_id = ? AND event_type = ?",
                Integer.class,
                reservationId.toString(),
                "reservation.released")).isEqualTo(1);
        assertThat(redis.opsForHash().get(STOCK_KEY, "available")).isEqualTo(Integer.toString(INITIAL_STOCK));
        assertThat(redis.opsForHash().get("flashsale:reservation:op:" + operationId, "state"))
                .isEqualTo("MIRRORED");
    }

    @Test
    void confirmServiceCreatesOneOrderAndDoesNotDecrementStockAgain() {
        seedReservationWithCreateJournal(
                CONFIRM_RESERVATION_ID,
                CREATE_OPERATION_ID,
                Instant.now().plusSeconds(120));

        ReservationLifecycleResult result = confirmation.confirm(CONFIRM_RESERVATION_ID);

        assertThat(result.outcome()).isEqualTo(ReservationLifecycleResult.Outcome.CONFIRMED);
        assertThat(result.order()).isPresent();
        assertThat(jdbc.queryForObject(
                "SELECT status FROM inventory_reservation WHERE id = UUID_TO_BIN(?)",
                String.class,
                CONFIRM_RESERVATION_ID.toString())).isEqualTo("CONFIRMED");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM reservation_order WHERE reservation_id = UUID_TO_BIN(?)",
                Integer.class,
                CONFIRM_RESERVATION_ID.toString())).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT available_quantity FROM inventory_stock_account WHERE ticket_item_id = ?",
                Integer.class,
                TICKET_ITEM_ID)).isEqualTo(INITIAL_STOCK - HELD_QUANTITY);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_event WHERE aggregate_id = ? AND event_type = ?",
                Integer.class,
                CONFIRM_RESERVATION_ID.toString(),
                "reservation.confirmed")).isEqualTo(1);
    }

    @Test
    void expireServiceKeepsDurableTerminalStateWhenRedisMirrorFails() {
        seedReservationWithCreateJournal(EXPIRE_RESERVATION_ID, EXPIRE_OPERATION_ID, Instant.now().minusSeconds(1));
        seedAppliedCreateOperation(EXPIRE_OPERATION_ID);
        doThrow(new IllegalStateException("injected mirror timeout"))
                .when(faults).hit(eq(FaultInjectionPort.FaultPoint.REDIS_MIRROR_TIMEOUT), any());

        ReservationLifecycleResult result = expiration.expire(EXPIRE_RESERVATION_ID);

        assertThat(result.outcome()).isEqualTo(ReservationLifecycleResult.Outcome.MIRROR_PENDING);
        UUID operationId = result.operationId().orElseThrow();
        assertThat(jdbc.queryForObject(
                "SELECT status FROM inventory_reservation WHERE id = UUID_TO_BIN(?)",
                String.class,
                EXPIRE_RESERVATION_ID.toString())).isEqualTo("EXPIRED");
        assertThat(jdbc.queryForObject(
                "SELECT available_quantity FROM inventory_stock_account WHERE ticket_item_id = ?",
                Integer.class,
                TICKET_ITEM_ID)).isEqualTo(INITIAL_STOCK);
        assertThat(jdbc.queryForObject(
                "SELECT state FROM inventory_operation_journal WHERE operation_id = UUID_TO_BIN(?)",
                String.class,
                operationId.toString())).isEqualTo("MIRROR_PENDING");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_event WHERE aggregate_id = ? AND event_type = ?",
                Integer.class,
                EXPIRE_RESERVATION_ID.toString(),
                "reservation.expired")).isEqualTo(1);
        assertThat(redis.opsForHash().get(STOCK_KEY, "available"))
                .isEqualTo(Integer.toString(INITIAL_STOCK - HELD_QUANTITY));
    }

    @Test
    void expireServiceFinalizesCreateAppliedRedisOperation() {
        seedReservationWithCreateJournal(EXPIRE_RESERVATION_ID, EXPIRE_OPERATION_ID, Instant.now().minusSeconds(1));
        seedAppliedCreateOperation(EXPIRE_OPERATION_ID);

        ReservationLifecycleResult result = expiration.expire(EXPIRE_RESERVATION_ID);

        assertThat(result.outcome()).isEqualTo(ReservationLifecycleResult.Outcome.EXPIRED);
        assertThat(result.operationId()).contains(EXPIRE_OPERATION_ID);
        assertThat(jdbc.queryForObject(
                "SELECT status FROM inventory_reservation WHERE id = UUID_TO_BIN(?)",
                String.class,
                EXPIRE_RESERVATION_ID.toString())).isEqualTo("EXPIRED");
        assertThat(jdbc.queryForObject(
                "SELECT available_quantity FROM inventory_stock_account WHERE ticket_item_id = ?",
                Integer.class,
                TICKET_ITEM_ID)).isEqualTo(INITIAL_STOCK);
        assertThat(jdbc.queryForObject(
                "SELECT state FROM inventory_operation_journal WHERE operation_id = UUID_TO_BIN(?)",
                String.class,
                EXPIRE_OPERATION_ID.toString())).isEqualTo("COMMITTED");
        assertThat(jdbc.queryForObject(
                "SELECT result_code FROM inventory_operation_journal WHERE operation_id = UUID_TO_BIN(?)",
                String.class,
                EXPIRE_OPERATION_ID.toString())).isEqualTo("EXPIRED");
        assertThat(redis.opsForHash().get(STOCK_KEY, "available")).isEqualTo(Integer.toString(INITIAL_STOCK));
        assertThat(redis.opsForHash().get(operationKey(EXPIRE_OPERATION_ID), "state"))
                .isEqualTo("MIRRORED");
    }

    @Test
    void confirmAndExpireRaceProducesOneExpiredTerminalTransitionAndNoOrder() throws Exception {
        seedReservationWithCreateJournal(
                RACE_RESERVATION_ID,
                RACE_OPERATION_ID,
                Instant.now().minusMillis(10));
        seedAppliedCreateOperation(RACE_OPERATION_ID);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<ReservationLifecycleResult> confirmResult = executor.submit(
                    () -> confirmation.confirm(RACE_RESERVATION_ID));
            Future<ReservationLifecycleResult> expireResult = executor.submit(
                    () -> expiration.expire(RACE_RESERVATION_ID));

            ReservationLifecycleResult confirmed = confirmResult.get(30, TimeUnit.SECONDS);
            ReservationLifecycleResult expired = expireResult.get(30, TimeUnit.SECONDS);

            assertThat(java.util.EnumSet.of(confirmed.outcome(), expired.outcome()))
                    .doesNotContain(ReservationLifecycleResult.Outcome.CONFIRMED);
            assertThat(jdbc.queryForObject(
                    "SELECT status FROM inventory_reservation WHERE id = UUID_TO_BIN(?)",
                    String.class,
                    RACE_RESERVATION_ID.toString())).isEqualTo("EXPIRED");
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM reservation_order WHERE reservation_id = UUID_TO_BIN(?)",
                    Integer.class,
                    RACE_RESERVATION_ID.toString())).isZero();
            assertThat(jdbc.queryForObject(
                    "SELECT available_quantity FROM inventory_stock_account WHERE ticket_item_id = ?",
                    Integer.class,
                    TICKET_ITEM_ID)).isEqualTo(INITIAL_STOCK);
            assertThat(jdbc.queryForObject(
                "SELECT state FROM inventory_operation_journal WHERE operation_id = UUID_TO_BIN(?)",
                String.class,
                    RACE_OPERATION_ID.toString())).isEqualTo("COMMITTED");
            assertThat(redis.opsForHash().get(
                    operationKey(RACE_OPERATION_ID), "state"))
                    .isEqualTo("MIRRORED");
        } finally {
            executor.shutdownNow();
        }
    }

    private void seedReservationWithCreateJournal(
            UUID reservationId,
            UUID operationId,
            Instant expiresAt
    ) {
        jdbc.update("INSERT INTO inventory_reservation "
                        + "(id, ticket_item_id, demo_actor_id, quantity, status, expires_at, terminal_at, "
                        + "idempotency_key_hash, request_fingerprint, version) "
                        + "VALUES (UUID_TO_BIN(?), ?, ?, ?, 'RESERVED', ?, NULL, UNHEX(?), UNHEX(?), 0)",
                reservationId.toString(),
                TICKET_ITEM_ID,
                ACTOR_ID.toString(),
                HELD_QUANTITY,
                expiresAt,
                "1".repeat(64),
                "2".repeat(64));
        jdbc.update("INSERT INTO inventory_operation_journal "
                        + "(operation_id, reservation_id, operation_type, state, ticket_item_id, quantity, "
                        + "demo_actor_id, idempotency_key_hash, request_fingerprint, fence_version, attempts) "
                        + "VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), 'CREATE', 'COMMITTED', ?, ?, ?, UNHEX(?), UNHEX(?), 0, 0)",
                operationId.toString(),
                reservationId.toString(),
                TICKET_ITEM_ID,
                HELD_QUANTITY,
                ACTOR_ID.toString(),
                "3".repeat(64),
                "2".repeat(64));
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
