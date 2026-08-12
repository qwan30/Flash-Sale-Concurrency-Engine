package com.xxxx.ddd.integration;

import com.xxxx.StartApplication;
import com.xxxx.ddd.application.reservation.ConfirmReservationService;
import com.xxxx.ddd.application.reservation.CreateReservationCommand;
import com.xxxx.ddd.application.reservation.CreateReservationResult;
import com.xxxx.ddd.application.reservation.CreateReservationService;
import com.xxxx.ddd.application.reservation.ExpireReservationService;
import com.xxxx.ddd.application.reservation.ReleaseReservationService;
import com.xxxx.ddd.application.reservation.ReservationLifecycleResult;
import com.xxxx.ddd.application.reservation.port.InventoryRepository;
import com.xxxx.ddd.application.MQ.OutboxService;
import com.xxxx.ddd.domain.reservation.InventorySnapshot;
import com.xxxx.ddd.domain.reservation.Reservation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

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
                "spring.task.scheduling.enabled=false",
                "flashsale.reservation.recovery-enabled=false",
                "flashsale.reservation.expiry-enabled=false"
        })
class ReservationEndToEndIntegrationTest {

    private static final long TICKET_ITEM_ID = 910_002L;
    private static final int INITIAL_STOCK = 100;
    private static final String STOCK_KEY = "flashsale:reservation:stock:" + TICKET_ITEM_ID;
    private static final int ATTEMPTS = 500;
    private static final int[] QUANTITIES = {1, 1, 1, 2, 2, 3, 4};

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
            .withDatabaseName("vetautet");

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer(
            DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));

    @Autowired
    private CreateReservationService creation;

    @Autowired
    private ConfirmReservationService confirmation;

    @Autowired
    private ReleaseReservationService release;

    @Autowired
    private ExpireReservationService expiration;

    @Autowired
    private OutboxService outbox;

    @Autowired
    private InventoryRepository inventory;

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
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    @BeforeEach
    void seedFixture() {
        deleteFixture();
        jdbc.update("INSERT INTO ticket_item "
                        + "(id, name, description, stock_initial, stock_available, is_stock_prepared, "
                        + "price_original, price_flash, sale_start_time, sale_end_time, status, activity_id) "
                        + "VALUES (?, 'end-to-end', 'end-to-end', ?, ?, FALSE, 100, 50, "
                        + "'2025-01-01 00:00:00', '2030-01-01 00:00:00', 1, 1)",
                TICKET_ITEM_ID, INITIAL_STOCK, INITIAL_STOCK);
        jdbc.update("INSERT INTO inventory_stock_account "
                        + "(ticket_item_id, initial_quantity, available_quantity, admission_state, fence_version, version) "
                        + "VALUES (?, ?, ?, 'OPEN', 0, 1)",
                TICKET_ITEM_ID, INITIAL_STOCK, INITIAL_STOCK);
        redis.opsForHash().putAll(STOCK_KEY, Map.of(
                "initial", Integer.toString(INITIAL_STOCK),
                "available", Integer.toString(INITIAL_STOCK),
                "reserved", "0",
                "confirmed", "0",
                "fence", "0",
                "admission_state", "OPEN"));
    }

    @AfterEach
    void cleanFixture() {
        deleteFixture();
        redis.delete(STOCK_KEY);
    }

    @Test
    void concurrentCreateAndTerminalActionsPreserveTheInventoryInvariant() throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(32);
        List<Future<CreateReservationResult>> futures = new ArrayList<>();
        List<CreateReservationCommand> commands = new ArrayList<>();
        List<Reservation> accepted = new ArrayList<>();
        List<CreateReservationCommand> acceptedCommands = new ArrayList<>();
        int acceptedCount = 0;
        try {
            CreateReservationCommand duplicateCommand = new CreateReservationCommand(
                    TICKET_ITEM_ID,
                    1,
                    UUID.fromString("b5d6dced-fbb4-4e24-91f7-25a7d8f1447f"),
                    "end-to-end-concurrent-duplicate");
            List<Future<CreateReservationResult>> duplicateFutures = new ArrayList<>();
            for (int duplicate = 0; duplicate < 16; duplicate++) {
                duplicateFutures.add(workers.submit(() -> creation.create(duplicateCommand)));
            }

            UUID duplicateReservationId = null;
            for (Future<CreateReservationResult> duplicateFuture : duplicateFutures) {
                CreateReservationResult result = duplicateFuture.get();
                assertThat(result.outcome()).isIn(
                        CreateReservationResult.Outcome.NEW,
                        CreateReservationResult.Outcome.REPLAYED,
                        CreateReservationResult.Outcome.PROCESSING);
                if (duplicateReservationId == null && result.reservation().isPresent()) {
                    duplicateReservationId = result.reservation().orElseThrow().id();
                    accepted.add(result.reservation().orElseThrow());
                    acceptedCommands.add(duplicateCommand);
                }
            }
            for (int retry = 0; retry < 20 && duplicateReservationId == null; retry++) {
                CreateReservationResult replay = creation.create(duplicateCommand);
                if (replay.reservation().isPresent()) {
                    duplicateReservationId = replay.reservation().orElseThrow().id();
                    accepted.add(replay.reservation().orElseThrow());
                    acceptedCommands.add(duplicateCommand);
                } else {
                    Thread.sleep(25);
                }
            }
            assertThat(duplicateReservationId)
                    .as("concurrent duplicate requests should converge to one reservation")
                    .isNotNull();
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM inventory_reservation WHERE id = UUID_TO_BIN(?)",
                    Integer.class,
                    duplicateReservationId.toString())).isEqualTo(1);
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM inventory_operation_journal WHERE reservation_id = UUID_TO_BIN(?)",
                    Integer.class,
                    duplicateReservationId.toString())).isEqualTo(1);

            for (int index = 0; index < ATTEMPTS; index++) {
                int quantity = QUANTITIES[index % QUANTITIES.length];
                int attempt = index;
                CreateReservationCommand command = new CreateReservationCommand(
                        TICKET_ITEM_ID,
                        quantity,
                        UUID.nameUUIDFromBytes(("actor-" + attempt).getBytes()),
                        "end-to-end-" + attempt);
                commands.add(command);
                futures.add(workers.submit(() -> creation.create(command)));
            }

            for (int index = 0; index < futures.size(); index++) {
                CreateReservationResult result = futures.get(index).get();
                CreateReservationCommand command = commands.get(index);
                assertThat(result.outcome()).isIn(
                        CreateReservationResult.Outcome.NEW,
                        CreateReservationResult.Outcome.SOLD_OUT,
                        CreateReservationResult.Outcome.REJECTED);
                result.reservation().ifPresent(reservation -> {
                    accepted.add(reservation);
                    acceptedCommands.add(command);
                });
            }
            acceptedCount = accepted.size();

            InventorySnapshot afterCreates = inventory.findSnapshot(TICKET_ITEM_ID).orElseThrow();
            assertThat(redisAvailable())
                    .as("Redis/MySQL drift immediately after concurrent creates")
                    .isEqualTo(afterCreates.available());

            for (int index = 0; index < accepted.size(); index++) {
                Reservation reservation = accepted.get(index);
                if (index % 3 == 0) {
                    jdbc.update("UPDATE inventory_reservation SET expires_at = UTC_TIMESTAMP(6) - INTERVAL 1 SECOND "
                                    + "WHERE id = UUID_TO_BIN(?)",
                            reservation.id().toString());
                    ReservationLifecycleResult result = expiration.expire(reservation.id());
                    assertThat(result.outcome()).isIn(
                            ReservationLifecycleResult.Outcome.EXPIRED,
                            ReservationLifecycleResult.Outcome.REPLAYED);
                } else if (index % 2 == 0) {
                    ReservationLifecycleResult result = confirmation.confirm(reservation.id());
                    assertThat(result.outcome()).isIn(
                            ReservationLifecycleResult.Outcome.CONFIRMED,
                            ReservationLifecycleResult.Outcome.REPLAYED);
                } else {
                    ReservationLifecycleResult result = release.release(reservation.id());
                    assertThat(result.outcome()).isIn(
                            ReservationLifecycleResult.Outcome.RELEASED,
                            ReservationLifecycleResult.Outcome.REPLAYED);
                }
            }

            if (!accepted.isEmpty()) {
                CreateReservationResult replay = creation.create(acceptedCommands.get(0));
                assertThat(replay.outcome()).isEqualTo(CreateReservationResult.Outcome.REPLAYED);
                assertThat(replay.reservationId()).isEqualTo(accepted.get(0).id());
            }
        } finally {
            workers.shutdownNow();
        }

        int publishBatches = 0;
        while (outbox.countPendingBacklog() > 0 && publishBatches < 10) {
            assertThat(outbox.publishPendingEvents())
                    .as("outbox batch %s should process pending events", publishBatches + 1)
                    .isPositive();
            publishBatches++;
        }
        assertThat(outbox.countPendingBacklog())
                .as("outbox backlog should drain within the bounded integration-test window")
                .isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_event WHERE status IN ('PENDING', 'FAILED')",
                Integer.class)).isZero();

        InventorySnapshot snapshot = inventory.findSnapshot(TICKET_ITEM_ID).orElseThrow();
        assertThat(snapshot.available()).isGreaterThanOrEqualTo(0);
        assertThat(snapshot.initial()).isEqualTo(snapshot.available() + snapshot.reserved() + snapshot.confirmed());
        assertThat(redisAvailable())
                .isEqualTo(snapshot.available());
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM inventory_reservation WHERE ticket_item_id = ?",
                Integer.class,
                TICKET_ITEM_ID)).isEqualTo(acceptedCount);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM reservation_order o "
                        + "JOIN inventory_reservation r ON r.id = o.reservation_id "
                        + "WHERE r.ticket_item_id = ?",
                Integer.class,
                TICKET_ITEM_ID)).isEqualTo(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM inventory_reservation "
                                + "WHERE ticket_item_id = ? AND status = 'CONFIRMED'",
                        Integer.class,
                        TICKET_ITEM_ID));
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM inventory_operation_journal "
                        + "WHERE ticket_item_id = ? AND state IN "
                        + "('RECEIVED','REDIS_APPLYING','REDIS_APPLIED','COMPENSATION_PENDING','MIRROR_PENDING','REPAIR_REQUIRED')",
                Integer.class,
                TICKET_ITEM_ID)).isZero();
    }

    private void deleteFixture() {
        jdbc.update("DELETE FROM reservation_order WHERE ticket_item_id = ?", TICKET_ITEM_ID);
        jdbc.update("DELETE FROM outbox_event WHERE aggregate_id IN "
                + "(SELECT BIN_TO_UUID(id) FROM inventory_reservation WHERE ticket_item_id = ?)", TICKET_ITEM_ID);
        jdbc.update("DELETE FROM inventory_operation_journal WHERE ticket_item_id = ?", TICKET_ITEM_ID);
        jdbc.update("DELETE FROM inventory_reservation WHERE ticket_item_id = ?", TICKET_ITEM_ID);
        jdbc.update("DELETE FROM inventory_stock_account WHERE ticket_item_id = ?", TICKET_ITEM_ID);
        jdbc.update("DELETE FROM ticket_item WHERE id = ?", TICKET_ITEM_ID);
    }

    private int redisAvailable() {
        return Integer.parseInt(String.valueOf(redis.opsForHash().get(STOCK_KEY, "available")));
    }

}
