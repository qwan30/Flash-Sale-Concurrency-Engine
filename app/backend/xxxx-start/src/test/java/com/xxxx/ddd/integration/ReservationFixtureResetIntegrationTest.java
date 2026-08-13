package com.xxxx.ddd.integration;

import com.xxxx.StartApplication;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@EnabledIfSystemProperty(named = "flashsale.integration", matches = "true")
@AutoConfigureMockMvc
@SpringBootTest(
        classes = StartApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "debug=false",
                "logging.level.root=WARN",
                "logging.level.com.xxxx=WARN",
                "logging.level.org.springframework=WARN",
                "spring.jpa.show-sql=false",
                "spring.task.scheduling.enabled=false",
                "spring.profiles.active=benchmark",
                "flashsale.reservation.recovery-enabled=false",
                "flashsale.reservation.expiry-enabled=false",
                "benchmark.fixture-reset-enabled=true",
                "benchmark.fixture-reset-token=test-fixture-token"
        })
class ReservationFixtureResetIntegrationTest {

    private static final long TICKET_ITEM_ID = 950_015L;
    private static final String STOCK_KEY = "flashsale:reservation:stock:" + TICKET_ITEM_ID;

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
            .withDatabaseName("vetautet");

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private MockMvc mockMvc;

    private UUID dirtyReservationId;

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
    void seedDirtyFixture() {
        deleteFixture();
        jdbc.update("INSERT INTO ticket_item "
                        + "(id, name, description, stock_initial, stock_available, is_stock_prepared, "
                        + "price_original, price_flash, sale_start_time, sale_end_time, status, activity_id) "
                        + "VALUES (?, 'dirty-fixture', 'dirty-fixture', 9, 5, FALSE, 100, 50, "
                        + "'2025-01-01 00:00:00', '2030-01-01 00:00:00', 1, 1)",
                TICKET_ITEM_ID);
        jdbc.update("INSERT INTO inventory_stock_account "
                        + "(ticket_item_id, initial_quantity, available_quantity, admission_state, fence_version, version) "
                        + "VALUES (?, 9, 5, 'CLOSED', 7, 2)", TICKET_ITEM_ID);

        UUID reservationId = UUID.randomUUID();
        dirtyReservationId = reservationId;
        UUID operationId = UUID.randomUUID();
        UUID repairId = UUID.randomUUID();
        byte[] hash = new byte[32];
        jdbc.update("INSERT INTO inventory_reservation "
                        + "(id, ticket_item_id, demo_actor_id, quantity, status, expires_at, "
                        + "idempotency_key_hash, request_fingerprint, version) "
                        + "VALUES (UUID_TO_BIN(?), ?, 'dirty-actor', 1, 'RESERVED', ?, ?, ?, 0)",
                reservationId.toString(), TICKET_ITEM_ID, Instant.now().plusSeconds(3600), hash, hash);
        jdbc.update("INSERT INTO reservation_order "
                        + "(id, reservation_id, ticket_item_id, demo_actor_id, quantity, confirmed_at) "
                        + "VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), ?, 'dirty-actor', 1, UTC_TIMESTAMP(6))",
                UUID.randomUUID().toString(), reservationId.toString(), TICKET_ITEM_ID);
        jdbc.update("INSERT INTO inventory_operation_journal "
                        + "(operation_id, reservation_id, operation_type, state, ticket_item_id, quantity, "
                        + "demo_actor_id, idempotency_key_hash, request_fingerprint, fence_version, attempts, "
                        + "result_code, result_stock_after) "
                        + "VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), 'CREATE', 'COMMITTED', ?, 1, "
                        + "'dirty-actor', ?, ?, 7, 0, 'SUCCESS', 4)",
                operationId.toString(), reservationId.toString(), TICKET_ITEM_ID, hash, hash);
        jdbc.update("INSERT INTO inventory_repair_journal "
                        + "(repair_id, ticket_item_id, previous_fence_version, new_fence_version, state, "
                        + "disposition, mysql_available_snapshot) VALUES (UUID_TO_BIN(?), ?, 6, 7, "
                        + "'COMPLETED', 'dirty-fixture', 5)", repairId.toString(), TICKET_ITEM_ID);
        jdbc.update("INSERT INTO outbox_event "
                        + "(id, aggregate_type, aggregate_id, event_type, event_version, payload) "
                        + "VALUES (?, 'Reservation', ?, 'reservation.created', 1, '{}')",
                UUID.randomUUID().toString(), reservationId.toString());
        jdbc.update("INSERT INTO outbox_event "
                        + "(id, aggregate_type, aggregate_id, event_type, event_version, payload) "
                        + "VALUES (?, 'Reservation', 'orphan-reservation-950015', 'reservation.created', 1, "
                        + "'{\"ticketItemId\":950015}')", UUID.randomUUID().toString());
        redis.opsForHash().putAll(STOCK_KEY, Map.of(
                "initial", "9",
                "available", "4",
                "reserved", "1",
                "confirmed", "0",
                "fence", "7",
                "admission_state", "CLOSED"));
    }

    @Test
    void resetClearsReservationStateAndProvesDurableRedisParity() throws Exception {
        mockMvc.perform(post("/admin/reservation-fixtures/reset")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Flashsale-Synthetic", "true")
                        .header("X-Flashsale-Fixture-Token", "test-fixture-token")
                        .content("""
                                {
                                  "ticketItemId": 950015,
                                  "stock": 1000,
                                  "strategy": "REDIS_FIRST",
                                  "reservationFixture": true
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.result.success").value(true))
                .andExpect(jsonPath("$.result.reservationFixtureReset").value(true))
                .andExpect(jsonPath("$.result.ticketItemId").value(950015))
                .andExpect(jsonPath("$.result.stock").value(1000))
                .andExpect(jsonPath("$.result.reservationStockAfter").value(1000))
                .andExpect(jsonPath("$.result.reservationRedisStockAfter").value(1000))
                .andExpect(jsonPath("$.result.fenceVersion").value(0))
                .andExpect(jsonPath("$.result.admissionState").value("OPEN"));
        mockMvc.perform(get("/admin/reservation-fixtures/evidence")
                        .param("ticketItemId", "950015")
                        .header("X-Flashsale-Synthetic", "true")
                        .header("X-Flashsale-Fixture-Token", "test-fixture-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.ticketItemId").value(950015))
                .andExpect(jsonPath("$.result.pendingJournal").value(0))
                .andExpect(jsonPath("$.result.pendingOutbox").value(0))
                .andExpect(jsonPath("$.result.invariantPass").value(true))
                .andExpect(jsonPath("$.result.parityPass").value(true));
        Map<String, Object> durableStock = jdbc.queryForObject(
                "SELECT initial_quantity, available_quantity, fence_version, admission_state "
                        + "FROM inventory_stock_account WHERE ticket_item_id = ?",
                (rs, rowNum) -> Map.<String, Object>of(
                        "initial", rs.getInt(1),
                        "available", rs.getInt(2),
                        "fence", rs.getLong(3),
                        "state", rs.getString(4)),
                TICKET_ITEM_ID);
        assertThat(durableStock).containsEntry("initial", 1000)
                .containsEntry("available", 1000)
                .containsEntry("fence", 0L)
                .containsEntry("state", "OPEN");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inventory_reservation WHERE ticket_item_id = ?",
                Integer.class, TICKET_ITEM_ID)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM reservation_order WHERE ticket_item_id = ?",
                Integer.class, TICKET_ITEM_ID)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inventory_operation_journal WHERE ticket_item_id = ?",
                Integer.class, TICKET_ITEM_ID)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inventory_repair_journal WHERE ticket_item_id = ?",
                Integer.class, TICKET_ITEM_ID)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event "
                        + "WHERE aggregate_type = 'Reservation' AND aggregate_id IN (?, ?)",
                Integer.class, dirtyReservationId.toString(), "orphan-reservation-950015")).isZero();
        assertThat(redis.opsForHash().entries(STOCK_KEY))
                .containsEntry("initial", "1000")
                .containsEntry("available", "1000")
                .containsEntry("reserved", "0")
                .containsEntry("confirmed", "0")
                .containsEntry("fence", "0")
                .containsEntry("admission_state", "OPEN");
    }

    @Test
    void resetRejectsRequestsWithoutTheSyntheticMarkerThroughTheRealAdvice() throws Exception {
        mockMvc.perform(post("/admin/reservation-fixtures/reset")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Flashsale-Fixture-Token", "test-fixture-token")
                        .content("""
                                {
                                  "ticketItemId": 950015,
                                  "stock": 1000,
                                  "strategy": "REDIS_FIRST",
                                  "reservationFixture": true
                                }
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("HTTP_403"));
    }

    private void deleteFixture() {
        jdbc.update("DELETE FROM reservation_order WHERE ticket_item_id = ?", TICKET_ITEM_ID);
        jdbc.update("DELETE FROM outbox_event WHERE aggregate_id IN "
                        + "(SELECT BIN_TO_UUID(id) FROM inventory_reservation WHERE ticket_item_id = ?)",
                TICKET_ITEM_ID);
        jdbc.update("DELETE FROM outbox_event WHERE aggregate_id = 'orphan-reservation-950015'");
        jdbc.update("DELETE FROM inventory_operation_journal WHERE ticket_item_id = ?", TICKET_ITEM_ID);
        jdbc.update("DELETE FROM inventory_repair_journal WHERE ticket_item_id = ?", TICKET_ITEM_ID);
        jdbc.update("DELETE FROM inventory_reservation WHERE ticket_item_id = ?", TICKET_ITEM_ID);
        jdbc.update("DELETE FROM inventory_stock_account WHERE ticket_item_id = ?", TICKET_ITEM_ID);
        jdbc.update("DELETE FROM ticket_item WHERE id = ?", TICKET_ITEM_ID);
        redis.delete(STOCK_KEY);
    }
}
