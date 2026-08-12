package com.xxxx.ddd.integration;

import com.xxxx.StartApplication;
import com.xxxx.ddd.application.reservation.CreateReservationCommand;
import com.xxxx.ddd.application.reservation.CreateReservationResult;
import com.xxxx.ddd.application.reservation.ConfirmReservationService;
import com.xxxx.ddd.application.reservation.ExpireReservationService;
import com.xxxx.ddd.application.reservation.ReservationLifecycleResult;
import com.xxxx.ddd.application.reservation.strategy.ReservationCoordinationStrategy;
import com.xxxx.ddd.application.reservation.strategy.ReservationStrategy;
import com.xxxx.ddd.domain.reservation.InventorySnapshot;
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
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.CountDownLatch;

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
class ReservationStrategyContractTest {

    private static final long TICKET_ITEM_ID = 950_015L;
    private static final String STOCK_KEY = "flashsale:reservation:stock:" + TICKET_ITEM_ID;

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
            .withDatabaseName("vetautet");

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @Autowired
    private List<ReservationCoordinationStrategy> strategies;

    @Autowired
    private ConfirmReservationService confirmation;

    @Autowired
    private ExpireReservationService expiration;

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
    void seedFixture() {
        seedFixture(2);
    }

    private void seedFixture(int initialStock) {
        jdbc.update("DELETE FROM reservation_order WHERE ticket_item_id = ?", TICKET_ITEM_ID);
        jdbc.update("DELETE FROM outbox_event");
        jdbc.update("DELETE FROM inventory_operation_journal WHERE ticket_item_id = ?", TICKET_ITEM_ID);
        jdbc.update("DELETE FROM inventory_reservation WHERE ticket_item_id = ?", TICKET_ITEM_ID);
        jdbc.update("DELETE FROM inventory_stock_account WHERE ticket_item_id = ?", TICKET_ITEM_ID);
        jdbc.update("DELETE FROM ticket_item WHERE id = ?", TICKET_ITEM_ID);
        jdbc.update("INSERT INTO ticket_item "
                        + "(id, name, description, stock_initial, stock_available, is_stock_prepared, "
                        + "price_original, price_flash, sale_start_time, sale_end_time, status, activity_id) "
                        + "VALUES (?, 'strategy', 'strategy', ?, ?, FALSE, 100, 50, "
                        + "'2025-01-01 00:00:00', '2030-01-01 00:00:00', 1, 1)",
                TICKET_ITEM_ID, initialStock, initialStock);
        jdbc.update("INSERT INTO inventory_stock_account "
                        + "(ticket_item_id, initial_quantity, available_quantity, admission_state, fence_version, version) "
                        + "VALUES (?, ?, ?, 'OPEN', 0, 1)", TICKET_ITEM_ID, initialStock, initialStock);
        redis.opsForHash().putAll(STOCK_KEY, Map.of(
                "initial", Integer.toString(initialStock),
                "available", Integer.toString(initialStock),
                "reserved", "0",
                "confirmed", "0",
                "fence", "0",
                "admission_state", "OPEN"));
    }

    @Test
    void everyCoordinationStrategySharesDuplicateSoldOutAndInvariantContract() {
        assertThat(strategies)
                .extracting(ReservationCoordinationStrategy::strategy)
                .containsExactlyInAnyOrder(ReservationStrategy.REDIS_FIRST, ReservationStrategy.MYSQL_CONDITIONAL);

        for (ReservationCoordinationStrategy strategy : strategies) {
            seedFixture();
            CreateReservationCommand command = new CreateReservationCommand(
                    TICKET_ITEM_ID, 2, UUID.nameUUIDFromBytes(
                    ("strategy-" + strategy.strategy()).getBytes()), "strategy-idempotency");

            CreateReservationResult created = strategy.create(command);
            CreateReservationResult replayed = strategy.create(command);
            CreateReservationResult soldOut = strategy.create(new CreateReservationCommand(
                    TICKET_ITEM_ID, 1, UUID.nameUUIDFromBytes(
                    ("sold-out-" + strategy.strategy()).getBytes()), "sold-out-idempotency"));

            assertThat(created.outcome()).isEqualTo(CreateReservationResult.Outcome.NEW);
            assertThat(replayed.outcome()).isEqualTo(CreateReservationResult.Outcome.REPLAYED);
            assertThat(soldOut.outcome()).isEqualTo(CreateReservationResult.Outcome.SOLD_OUT);

            InventorySnapshot snapshot = jdbc.queryForObject(
                    "SELECT s.ticket_item_id, s.initial_quantity, s.available_quantity, "
                            + "COALESCE(SUM(CASE WHEN r.status = 'RESERVED' THEN r.quantity ELSE 0 END), 0), "
                            + "COALESCE(SUM(CASE WHEN r.status = 'CONFIRMED' THEN r.quantity ELSE 0 END), 0) "
                            + "FROM inventory_stock_account s "
                            + "LEFT JOIN inventory_reservation r ON r.ticket_item_id = s.ticket_item_id "
                            + "WHERE s.ticket_item_id = ? GROUP BY s.ticket_item_id, "
                            + "s.initial_quantity, s.available_quantity",
                    (resultSet, rowNum) -> new InventorySnapshot(
                            resultSet.getLong(1),
                            resultSet.getInt(2),
                            resultSet.getInt(3),
                            resultSet.getInt(4),
                            resultSet.getInt(5)),
                    TICKET_ITEM_ID);
            assertThat(snapshot.invariantHolds()).isTrue();
            assertThat(snapshot.available()).isZero();
        }
    }

    @Test
    void mysqlConditionalClassifiesClosedAdmissionWithoutConsumingStock() {
        ReservationCoordinationStrategy strategy = strategies.stream()
                .filter(candidate -> candidate.strategy() == ReservationStrategy.MYSQL_CONDITIONAL)
                .findFirst()
                .orElseThrow();
        seedFixture(2);
        jdbc.update("UPDATE inventory_stock_account SET admission_state = 'CLOSED' WHERE ticket_item_id = ?",
                TICKET_ITEM_ID);

        CreateReservationResult result = strategy.create(new CreateReservationCommand(
                TICKET_ITEM_ID,
                1,
                UUID.nameUUIDFromBytes("closed-admission".getBytes()),
                "closed-admission-key"));

        assertThat(result.outcome()).isEqualTo(CreateReservationResult.Outcome.REJECTED);
        assertThat(result.resultCode()).isEqualTo("ADMISSION_CLOSED");
        assertThat(result.reservation()).isEmpty();
        assertThat(jdbc.queryForObject(
                "SELECT available_quantity FROM inventory_stock_account WHERE ticket_item_id = ?",
                Integer.class,
                TICKET_ITEM_ID)).isEqualTo(2);
    }

    @Test
    void redisFirstClassifiesFenceMismatchBeforeConsumingStock() {
        ReservationCoordinationStrategy strategy = strategies.stream()
                .filter(candidate -> candidate.strategy() == ReservationStrategy.REDIS_FIRST)
                .findFirst()
                .orElseThrow();
        seedFixture(2);
        redis.opsForHash().put(STOCK_KEY, "fence", "1");

        CreateReservationResult result = strategy.create(new CreateReservationCommand(
                TICKET_ITEM_ID,
                1,
                UUID.nameUUIDFromBytes("stale-fence".getBytes()),
                "stale-fence-key"));

        assertThat(result.outcome()).isEqualTo(CreateReservationResult.Outcome.FENCE_STALE);
        assertThat(result.resultCode()).isEqualTo("FENCE_STALE");
        assertThat(result.reservation()).isEmpty();
        assertThat(jdbc.queryForObject(
                "SELECT available_quantity FROM inventory_stock_account WHERE ticket_item_id = ?",
                Integer.class,
                TICKET_ITEM_ID)).isEqualTo(2);
        assertThat(Integer.parseInt(String.valueOf(redis.opsForHash().get(STOCK_KEY, "available"))))
                .isEqualTo(2);
    }

    @Test
    void mysqlConditionalPreservesInvariantUnderConcurrentCreates() throws Exception {
        ReservationCoordinationStrategy strategy = strategies.stream()
                .filter(candidate -> candidate.strategy() == ReservationStrategy.MYSQL_CONDITIONAL)
                .findFirst()
                .orElseThrow();
        seedFixture(100);

        ExecutorService workers = Executors.newFixedThreadPool(16);
        List<Future<CreateReservationResult>> futures = new java.util.ArrayList<>();
        try {
            for (int index = 0; index < 100; index++) {
                int attempt = index;
                futures.add(workers.submit(() -> strategy.create(new CreateReservationCommand(
                        TICKET_ITEM_ID,
                        1,
                        UUID.nameUUIDFromBytes(("concurrent-" + attempt).getBytes()),
                        "concurrent-" + attempt))));
            }
            List<CreateReservationResult> results = new java.util.ArrayList<>();
            for (Future<CreateReservationResult> future : futures) {
                results.add(future.get());
            }
            assertThat(results).allMatch(result -> result.outcome() == CreateReservationResult.Outcome.NEW);
        } finally {
            workers.shutdownNow();
        }

        InventorySnapshot snapshot = jdbc.queryForObject(
                "SELECT s.ticket_item_id, s.initial_quantity, s.available_quantity, "
                        + "COALESCE(SUM(CASE WHEN r.status = 'RESERVED' THEN r.quantity ELSE 0 END), 0), "
                        + "COALESCE(SUM(CASE WHEN r.status = 'CONFIRMED' THEN r.quantity ELSE 0 END), 0) "
                        + "FROM inventory_stock_account s "
                        + "LEFT JOIN inventory_reservation r ON r.ticket_item_id = s.ticket_item_id "
                        + "WHERE s.ticket_item_id = ? GROUP BY s.ticket_item_id, "
                        + "s.initial_quantity, s.available_quantity",
                (resultSet, rowNum) -> new InventorySnapshot(
                        resultSet.getLong(1),
                        resultSet.getInt(2),
                        resultSet.getInt(3),
                        resultSet.getInt(4),
                        resultSet.getInt(5)),
                TICKET_ITEM_ID);
        assertThat(snapshot.invariantHolds()).isTrue();
        assertThat(snapshot.available()).isZero();
        assertThat(Integer.parseInt(String.valueOf(redis.opsForHash().get(STOCK_KEY, "available"))))
                .isEqualTo(snapshot.available());
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM inventory_reservation WHERE ticket_item_id = ?",
                Integer.class,
                TICKET_ITEM_ID)).isEqualTo(100);
    }

    @Test
    void everyCoordinationStrategyDeduplicatesConcurrentSameKey() throws Exception {
        for (ReservationCoordinationStrategy strategy : strategies) {
            seedFixture(4);
            CreateReservationCommand command = new CreateReservationCommand(
                    TICKET_ITEM_ID,
                    1,
                    UUID.nameUUIDFromBytes(("duplicate-" + strategy.strategy()).getBytes()),
                    "duplicate-key");
            ExecutorService workers = Executors.newFixedThreadPool(16);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<CreateReservationResult>> futures = new java.util.ArrayList<>();
            try {
                for (int index = 0; index < 16; index++) {
                    futures.add(workers.submit(() -> {
                        start.await();
                        return strategy.create(command);
                    }));
                }
                start.countDown();
                for (Future<CreateReservationResult> future : futures) {
                    assertThat(future.get().outcome()).isIn(
                            CreateReservationResult.Outcome.NEW,
                            CreateReservationResult.Outcome.REPLAYED,
                            CreateReservationResult.Outcome.PROCESSING);
                }
            } finally {
                workers.shutdownNow();
            }

            assertThat(strategy.create(command).outcome())
                    .isIn(CreateReservationResult.Outcome.REPLAYED, CreateReservationResult.Outcome.PROCESSING);
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM inventory_reservation WHERE ticket_item_id = ?",
                    Integer.class,
                    TICKET_ITEM_ID)).isEqualTo(1);
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM inventory_operation_journal WHERE ticket_item_id = ?",
                    Integer.class,
                    TICKET_ITEM_ID)).isEqualTo(1);
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM outbox_event WHERE event_type = 'reservation.created'",
                    Integer.class)).isEqualTo(1);
            assertThat(Integer.parseInt(String.valueOf(redis.opsForHash().get(STOCK_KEY, "available"))))
                    .isEqualTo(3);
        }
    }

    @Test
    void everyCoordinationStrategyKeepsConfirmAndExpireMutuallyExclusive() throws Exception {
        for (ReservationCoordinationStrategy strategy : strategies) {
            seedFixture(1);
            CreateReservationResult created = strategy.create(new CreateReservationCommand(
                    TICKET_ITEM_ID,
                    1,
                    UUID.nameUUIDFromBytes(("terminal-" + strategy.strategy()).getBytes()),
                    "terminal-key"));
            UUID reservationId = created.reservation().orElseThrow().id();
            jdbc.update("UPDATE inventory_reservation SET expires_at = UTC_TIMESTAMP(6) - INTERVAL 1 SECOND "
                    + "WHERE id = UUID_TO_BIN(?)", reservationId.toString());

            ExecutorService workers = Executors.newFixedThreadPool(2);
            CountDownLatch start = new CountDownLatch(1);
            try {
                Future<ReservationLifecycleResult> confirm = workers.submit(() -> {
                    start.await();
                    return confirmation.confirm(reservationId);
                });
                Future<ReservationLifecycleResult> expire = workers.submit(() -> {
                    start.await();
                    return expiration.expire(reservationId);
                });
                start.countDown();
                confirm.get();
                expire.get();
            } finally {
                workers.shutdownNow();
            }

            assertThat(jdbc.queryForObject(
                    "SELECT status FROM inventory_reservation WHERE id = UUID_TO_BIN(?)",
                    String.class,
                    reservationId.toString())).isEqualTo("EXPIRED");
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM reservation_order WHERE reservation_id = UUID_TO_BIN(?)",
                    Integer.class,
                    reservationId.toString())).isZero();
            assertThat(Integer.parseInt(String.valueOf(redis.opsForHash().get(STOCK_KEY, "available"))))
                    .isEqualTo(1);
        }
    }
}
