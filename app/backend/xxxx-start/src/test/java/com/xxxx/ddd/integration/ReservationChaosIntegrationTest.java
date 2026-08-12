package com.xxxx.ddd.integration;

import com.xxxx.StartApplication;
import com.xxxx.ddd.application.MQ.OutboxPublishScheduler;
import com.xxxx.ddd.application.MQ.OutboxService;
import com.xxxx.ddd.application.reservation.ConfirmReservationService;
import com.xxxx.ddd.application.reservation.CreateReservationCommand;
import com.xxxx.ddd.application.reservation.CreateReservationResult;
import com.xxxx.ddd.application.reservation.CreateReservationService;
import com.xxxx.ddd.application.reservation.ReleaseReservationService;
import com.xxxx.ddd.application.reservation.ReservationExpiryScheduler;
import com.xxxx.ddd.application.reservation.ReservationLifecycleResult;
import com.xxxx.ddd.application.reservation.ReservationRecoveryScheduler;
import com.xxxx.ddd.application.reservation.ReservationRecoveryService;
import com.xxxx.ddd.application.reservation.port.FaultInjectionPort;
import com.xxxx.ddd.application.reservation.port.OperationJournalRepository;
import com.xxxx.ddd.application.reservation.port.InventoryRepository;
import com.xxxx.ddd.chaos.ConfigurableFaultInjection;
import com.xxxx.ddd.chaos.InjectedFaultException;
import com.xxxx.ddd.domain.reservation.InventorySnapshot;
import com.xxxx.ddd.domain.reservation.ReservationStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Docker-gated deterministic chaos scenarios.
 *
 * <p>The injected faults exercise durable crash windows and the protocol-boundary
 * Toxiproxy smoke test remains a separate gate in {@link ReservationToxiproxyIntegrationTest}.
 * This class must not be treated as evidence when the integration property is disabled.
 */
@Testcontainers
@EnabledIfSystemProperty(named = "flashsale.integration", matches = "true")
@ActiveProfiles("chaos")
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
class ReservationChaosIntegrationTest {

    private static final long TICKET_ITEM_ID = 910_010L;
    private static final int INITIAL_STOCK = 10;
    private static final String STOCK_KEY = "flashsale:reservation:stock:" + TICKET_ITEM_ID;

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
            .withDatabaseName("vetautet");

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer(
            DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));

    @MockBean
    private OutboxPublishScheduler outboxScheduler;

    @MockBean
    private ReservationRecoveryScheduler recoveryScheduler;

    @MockBean
    private ReservationExpiryScheduler expiryScheduler;

    @Autowired
    private ConfigurableFaultInjection faults;

    @Autowired
    private CreateReservationService creation;

    @Autowired
    private ConfirmReservationService confirmation;

    @Autowired
    private ReleaseReservationService release;

    @Autowired
    private ReservationRecoveryService recovery;

    @Autowired
    private OperationJournalRepository journal;

    @Autowired
    private InventoryRepository inventory;

    @Autowired
    private OutboxService outbox;

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
        faults.clear();
        deleteFixture();
        jdbc.update("INSERT INTO ticket_item "
                        + "(id, name, description, stock_initial, stock_available, is_stock_prepared, "
                        + "price_original, price_flash, sale_start_time, sale_end_time, status, activity_id) "
                        + "VALUES (?, 'chaos-integration', 'chaos-integration', ?, ?, FALSE, 100, 50, "
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
        faults.clear();
        deleteFixture();
        redis.delete(STOCK_KEY);
        Set<String> operationKeys = redis.keys("flashsale:reservation:op:*");
        if (operationKeys != null && !operationKeys.isEmpty()) {
            redis.delete(operationKeys);
        }
    }

    @Test
    void chaosProfileExposesFiniteScenarioCatalog() {
        assertThat(faults.catalog()).containsExactly(FaultInjectionPort.FaultPoint.values());
        assertThat(faults.active()).isNull();
    }

    @Test
    void afterRedisBeforeDatabaseConvergesThroughRecoveryWithoutDoubleApply() {
        CreateReservationCommand command = command("after-redis");
        faults.activate(FaultInjectionPort.FaultPoint.AFTER_REDIS_BEFORE_DB);

        assertThatThrownBy(() -> creation.create(command))
                .isInstanceOf(InjectedFaultException.class);
        faults.clear();

        UUID operationId = latestOperationId();
        recovery.recover(journal.findByOperationId(operationId).orElseThrow());

        assertThat(journal.findByOperationId(operationId).orElseThrow().state())
                .isEqualTo(OperationJournalRepository.JournalState.COMMITTED);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM inventory_reservation WHERE ticket_item_id = ?",
                Integer.class,
                TICKET_ITEM_ID)).isEqualTo(1);
        publishAndAwaitConverged();
    }

    @Test
    void afterDatabaseCommitBeforeResponseReplaysTheSingleCommittedReservation() {
        CreateReservationCommand command = command("after-db");
        faults.activate(FaultInjectionPort.FaultPoint.AFTER_DB_COMMIT_BEFORE_RESPONSE);

        assertThatThrownBy(() -> creation.create(command))
                .isInstanceOf(InjectedFaultException.class);
        faults.clear();

        CreateReservationResult replay = creation.create(command);
        assertThat(replay.outcome()).isEqualTo(CreateReservationResult.Outcome.REPLAYED);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM inventory_reservation WHERE ticket_item_id = ?",
                Integer.class,
                TICKET_ITEM_ID)).isEqualTo(1);

        UUID reservationId = replay.reservation().orElseThrow().id();
        ReservationLifecycleResult confirmed = confirmation.confirm(reservationId);
        ReservationLifecycleResult confirmedReplay = confirmation.confirm(reservationId);
        assertThat(confirmed.outcome()).isEqualTo(ReservationLifecycleResult.Outcome.CONFIRMED);
        assertThat(confirmedReplay.outcome()).isEqualTo(ReservationLifecycleResult.Outcome.REPLAYED);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM reservation_order WHERE reservation_id = UUID_TO_BIN(?)",
                Integer.class,
                reservationId.toString())).isEqualTo(1);
        publishAndAwaitConverged();
    }

    @Test
    void terminalMirrorTimeoutConvergesAfterDependencyRecovery() {
        CreateReservationResult created = creation.create(command("mirror-timeout"));
        assertThat(created.reservation()).isPresent();

        faults.activate(FaultInjectionPort.FaultPoint.REDIS_MIRROR_TIMEOUT);
        ReservationLifecycleResult pending = release.release(created.reservation().orElseThrow().id());
        assertThat(pending.outcome()).isEqualTo(ReservationLifecycleResult.Outcome.MIRROR_PENDING);
        faults.clear();

        OperationJournalRepository.JournalEntry pendingJournal = journal.findPendingTerminal(
                        created.reservation().orElseThrow().id(),
                OperationJournalRepository.OperationType.RELEASE)
                .orElseThrow();
        recovery.recover(pendingJournal);

        assertThat(journal.findByOperationId(pendingJournal.operationId()).orElseThrow().state())
                .isEqualTo(OperationJournalRepository.JournalState.COMMITTED);
        assertThat(jdbc.queryForObject(
                "SELECT status FROM inventory_reservation WHERE id = UUID_TO_BIN(?)",
                String.class,
                created.reservation().orElseThrow().id().toString()))
                .isEqualTo(ReservationStatus.RELEASED.name());
        publishAndAwaitConverged();
    }

    @Test
    void kafkaUnavailableLeavesOutboxRetryableUntilKafkaRecovers() {
        creation.create(command("kafka-unavailable"));
        faults.activate(FaultInjectionPort.FaultPoint.KAFKA_UNAVAILABLE);

        outbox.publishPendingEvents();
        faults.clear();

        assertThat(failedOutboxCount()).isEqualTo(1);
        jdbc.update("UPDATE outbox_event SET next_attempt_at = UTC_TIMESTAMP(6) WHERE status = 'FAILED'");
        publishAndAwaitConverged();

        assertThat(failedOutboxCount()).isZero();
        assertThat(pendingOutboxCount()).isZero();
    }

    @Test
    void confirmExpireRaceLeavesExpiryAsTheDurableWinner() {
        CreateReservationResult created = creation.create(command("confirm-expire-race"));
        UUID reservationId = created.reservation().orElseThrow().id();
        jdbc.update("UPDATE inventory_reservation SET expires_at = UTC_TIMESTAMP(6) - INTERVAL 1 SECOND "
                        + "WHERE id = UUID_TO_BIN(?)", reservationId.toString());

        faults.activate(FaultInjectionPort.FaultPoint.CONFIRM_EXPIRE_RACE);
        assertThatThrownBy(() -> confirmation.confirm(reservationId))
                .isInstanceOf(InjectedFaultException.class);
        faults.clear();

        ReservationLifecycleResult result = confirmation.confirm(reservationId);
        assertThat(result.outcome()).isEqualTo(ReservationLifecycleResult.Outcome.LATE_CONFLICT);
        assertThat(jdbc.queryForObject(
                "SELECT status FROM inventory_reservation WHERE id = UUID_TO_BIN(?)",
                String.class,
                reservationId.toString())).isEqualTo(ReservationStatus.EXPIRED.name());
        publishAndAwaitConverged();
    }

    private CreateReservationCommand command(String suffix) {
        return new CreateReservationCommand(
                TICKET_ITEM_ID,
                1,
                UUID.nameUUIDFromBytes(("chaos-actor-" + suffix).getBytes(StandardCharsets.UTF_8)),
                "chaos-" + suffix);
    }

    private UUID latestOperationId() {
        String operationId = jdbc.queryForObject(
                "SELECT BIN_TO_UUID(operation_id) FROM inventory_operation_journal "
                        + "WHERE ticket_item_id = ? ORDER BY created_at DESC, operation_id DESC LIMIT 1",
                String.class,
                TICKET_ITEM_ID);
        return UUID.fromString(operationId);
    }

    private void publishAndAwaitConverged() {
        AssertionError lastFailure = null;
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline) {
            try {
                outbox.retryFailedEvents();
                outbox.publishPendingEvents();
                assertConverged();
                return;
            } catch (AssertionError convergenceFailure) {
                lastFailure = convergenceFailure;
                sleepBriefly();
            }
        }
        if (lastFailure != null) {
            throw lastFailure;
        }
        assertConverged();
    }

    private void sleepBriefly() {
        try {
            Thread.sleep(100);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("chaos convergence wait was interrupted", interrupted);
        }
    }

    private void assertConverged() {
        InventorySnapshot snapshot = inventory.findSnapshot(TICKET_ITEM_ID).orElseThrow();
        assertThat(snapshot.available()).isGreaterThanOrEqualTo(0);
        assertThat(snapshot.initial())
                .isEqualTo(snapshot.available() + snapshot.reserved() + snapshot.confirmed());
        assertThat(redis.opsForHash().get(STOCK_KEY, "available"))
                .isEqualTo(Integer.toString(snapshot.available()));
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM inventory_operation_journal "
                        + "WHERE ticket_item_id = ? AND state IN "
                        + "('RECEIVED','REDIS_APPLYING','REDIS_APPLIED','COMPENSATION_PENDING',"
                        + "'MIRROR_PENDING','REPAIR_REQUIRED')",
                Integer.class,
                TICKET_ITEM_ID)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) - COUNT(DISTINCT reservation_id) FROM reservation_order "
                        + "WHERE ticket_item_id = ?",
                Integer.class,
                TICKET_ITEM_ID)).isZero();
        assertThat(failedOutboxCount()).isZero();
        assertThat(pendingOutboxCount()).isZero();
    }

    private int pendingOutboxCount() {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_event WHERE status = 'PENDING'", Integer.class);
    }

    private int failedOutboxCount() {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_event WHERE status = 'FAILED'", Integer.class);
    }

    private void deleteFixture() {
        jdbc.update("DELETE FROM reservation_order WHERE ticket_item_id = ?", TICKET_ITEM_ID);
        jdbc.update("DELETE FROM outbox_event WHERE aggregate_type = 'Reservation'");
        jdbc.update("DELETE FROM inventory_operation_journal WHERE ticket_item_id = ?", TICKET_ITEM_ID);
        jdbc.update("DELETE FROM inventory_repair_journal WHERE ticket_item_id = ?", TICKET_ITEM_ID);
        jdbc.update("DELETE FROM inventory_reservation WHERE ticket_item_id = ?", TICKET_ITEM_ID);
        jdbc.update("DELETE FROM inventory_stock_account WHERE ticket_item_id = ?", TICKET_ITEM_ID);
        jdbc.update("DELETE FROM ticket_item WHERE id = ?", TICKET_ITEM_ID);
    }
}
