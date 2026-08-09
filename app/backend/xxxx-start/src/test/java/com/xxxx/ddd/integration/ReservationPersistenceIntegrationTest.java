package com.xxxx.ddd.integration;

import com.xxxx.ddd.application.reservation.port.InventoryRepository;
import com.xxxx.ddd.application.reservation.port.OperationJournalRepository;
import com.xxxx.ddd.application.reservation.port.ReservationRepository;
import com.xxxx.ddd.domain.reservation.InventorySnapshot;
import com.xxxx.ddd.domain.reservation.Reservation;
import com.xxxx.ddd.domain.reservation.ReservationStatus;
import com.xxxx.ddd.infrastructure.reservation.persistence.JpaInventoryRepositoryAdapter;
import com.xxxx.ddd.infrastructure.reservation.persistence.JpaOperationJournalRepositoryAdapter;
import com.xxxx.ddd.infrastructure.reservation.persistence.JpaReservationRepositoryAdapter;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.EntityTransaction;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class ReservationPersistenceIntegrationTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("vetautet");

    private static final AtomicLong IDS = new AtomicLong(800_000L);
    private static EntityManagerFactory entityManagerFactory;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void setUpSchema() {
        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();

        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        jdbc = new JdbcTemplate(dataSource);

        LocalContainerEntityManagerFactoryBean factory = new LocalContainerEntityManagerFactoryBean();
        factory.setDataSource(dataSource);
        factory.setPackagesToScan("com.xxxx.ddd.infrastructure.reservation.persistence");
        factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        Properties properties = new Properties();
        properties.setProperty("hibernate.hbm2ddl.auto", "none");
        factory.setJpaProperties(properties);
        factory.afterPropertiesSet();
        entityManagerFactory = factory.getObject();
    }

    @AfterAll
    static void tearDownEntityManagerFactory() {
        if (entityManagerFactory != null) {
            entityManagerFactory.close();
        }
    }

    @Test
    void decrementRequiresAvailableOpenStockAndMatchingFence() {
        long ticketItemId = seedStock(5);

        assertThat((Boolean) inTransaction(entityManager -> inventory(entityManager)
                .decrementIfAvailable(ticketItemId, 3, 0L))).isTrue();
        assertThat((Boolean) inTransaction(entityManager -> inventory(entityManager)
                .decrementIfAvailable(ticketItemId, 3, 0L))).isFalse();

        jdbc.update("UPDATE inventory_stock_account SET admission_state = 'CLOSED' WHERE ticket_item_id = ?",
                ticketItemId);
        assertThat((Boolean) inTransaction(entityManager -> inventory(entityManager)
                .decrementIfAvailable(ticketItemId, 1, 0L))).isFalse();
    }

    @Test
    void readsTheDurableFenceVersionUsedForRedisAdmission() {
        long ticketItemId = seedStock(5);
        jdbc.update("UPDATE inventory_stock_account SET fence_version = 9 WHERE ticket_item_id = ?", ticketItemId);

        OptionalLong fenceVersion = inTransaction(entityManager -> inventory(entityManager)
                .findFenceVersion(ticketItemId));

        assertThat(fenceVersion.isPresent()).isTrue();
        assertThat(fenceVersion.getAsLong()).isEqualTo(9L);
    }

    @Test
    void oneOfConcurrentConfirmAndExpireTransitionsWins() throws Exception {
        long ticketItemId = seedStock(10);
        UUID reservationId = UUID.randomUUID();
        insertReservation(reservationId, ticketItemId, 1, ReservationStatus.RESERVED,
                Instant.now().plusSeconds(120));
        assertThat((Boolean) inTransaction(entityManager -> inventory(entityManager)
                .decrementIfAvailable(ticketItemId, 1, 0L))).isTrue();

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Optional<Reservation>> confirm = executor.submit(() -> transitionAfter(start, ready,
                    reservationId, ReservationStatus.CONFIRMED, ticketItemId));
            Future<Optional<Reservation>> expire = executor.submit(() -> transitionAfter(start, ready,
                    reservationId, ReservationStatus.EXPIRED, ticketItemId));
            ready.await();
            start.countDown();

            boolean confirmWon = confirm.get().isPresent();
            boolean expireWon = expire.get().isPresent();
            assertThat(confirmWon ^ expireWon).isTrue();
        } finally {
            executor.shutdownNow();
        }

        Reservation persisted = inTransaction(entityManager -> reservation(entityManager)
                .findById(reservationId)).orElseThrow();
        assertThat(persisted.status()).isIn(ReservationStatus.CONFIRMED, ReservationStatus.EXPIRED);
    }

    @Test
    void releaseRestoresStockExactlyOnce() {
        long ticketItemId = seedStock(10);
        UUID reservationId = UUID.randomUUID();
        insertReservation(reservationId, ticketItemId, 3, ReservationStatus.RESERVED,
                Instant.now().plusSeconds(120));
        assertThat((Boolean) inTransaction(entityManager -> inventory(entityManager)
                .decrementIfAvailable(ticketItemId, 3, 0L))).isTrue();

        Optional<Reservation> first = inTransaction(entityManager -> reservation(entityManager)
                .transitionIfCurrent(reservationId, ReservationStatus.RESERVED, ReservationStatus.RELEASED,
                        Instant.now(), 0L));
        Optional<Reservation> duplicate = inTransaction(entityManager -> reservation(entityManager)
                .transitionIfCurrent(reservationId, ReservationStatus.RESERVED, ReservationStatus.RELEASED,
                        Instant.now(), 0L));

        assertThat(first).isPresent();
        assertThat(duplicate).isEmpty();
        InventorySnapshot snapshot = inTransaction(entityManager -> inventory(entityManager)
                .findSnapshot(ticketItemId)).orElseThrow();
        assertThat(snapshot.available()).isEqualTo(10);
    }

    @Test
    void journalLeaseCannotBeClaimedByTwoWorkers() throws Exception {
        long ticketItemId = seedStock(10);
        UUID operationId = UUID.randomUUID();
        OperationJournalRepository.JournalEntry candidate = journalEntry(
                operationId, ticketItemId, "actor-lease", "11", "21");
        inTransaction(entityManager -> journal(entityManager).claimCreate(candidate));

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<List<OperationJournalRepository.JournalEntry>> first = executor.submit(
                    () -> claimAfter(start, ready, "worker-a"));
            Future<List<OperationJournalRepository.JournalEntry>> second = executor.submit(
                    () -> claimAfter(start, ready, "worker-b"));
            ready.await();
            start.countDown();

            assertThat(first.get().size() + second.get().size()).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void sameActorAndIdempotencyHashReusesTheOriginalJournalRow() {
        long ticketItemId = seedStock(10);
        UUID originalOperationId = UUID.randomUUID();
        OperationJournalRepository.JournalEntry original = journalEntry(
                originalOperationId, ticketItemId, "actor-idempotent", "31", "41");
        OperationJournalRepository.JournalEntry replay = journalEntry(
                UUID.randomUUID(), ticketItemId, "actor-idempotent", "31", "41");
        OperationJournalRepository.JournalEntry conflict = journalEntry(
                UUID.randomUUID(), ticketItemId, "actor-idempotent", "31", "42");

        OperationJournalRepository.JournalEntry claimed = inTransaction(entityManager ->
                journal(entityManager).claimCreate(original));
        OperationJournalRepository.JournalEntry replayed = inTransaction(entityManager ->
                journal(entityManager).claimCreate(replay));
        OperationJournalRepository.JournalEntry conflicted = inTransaction(entityManager ->
                journal(entityManager).claimCreate(conflict));

        assertThat(claimed.operationId()).isEqualTo(originalOperationId);
        assertThat(replayed.operationId()).isEqualTo(originalOperationId);
        assertThat(conflicted.operationId()).isEqualTo(originalOperationId);
        assertThat(conflicted.requestFingerprint()).isEqualTo(digest("41"));
        assertThat((Boolean) inTransaction(entityManager -> journal(entityManager).transition(
                originalOperationId,
                OperationJournalRepository.JournalState.RECEIVED,
                OperationJournalRepository.JournalState.COMMITTED,
                null,
                null))).isTrue();
    }

    @Test
    void oldFenceCannotMutateStockAfterAdmissionReopens() {
        long ticketItemId = seedStock(10);
        jdbc.update("UPDATE inventory_stock_account SET fence_version = 2, admission_state = 'OPEN' "
                        + "WHERE ticket_item_id = ?", ticketItemId);

        assertThat((Boolean) inTransaction(entityManager -> inventory(entityManager)
                .decrementIfAvailable(ticketItemId, 1, 1L))).isFalse();
        assertThat((Boolean) inTransaction(entityManager -> inventory(entityManager)
                .restoreIfAdmitted(ticketItemId, 1, 1L))).isFalse();
        assertThat((Boolean) inTransaction(entityManager -> inventory(entityManager)
                .decrementIfAvailable(ticketItemId, 1, 2L))).isTrue();
    }

    private static Optional<Reservation> transitionAfter(
            CountDownLatch start,
            CountDownLatch ready,
            UUID reservationId,
            ReservationStatus targetStatus,
            long ticketItemId
    ) throws InterruptedException {
        ready.countDown();
        start.await();
        return inTransaction(entityManager -> reservation(entityManager).transitionIfCurrent(
                reservationId, ReservationStatus.RESERVED, targetStatus, Instant.now(), 0L));
    }

    private static List<OperationJournalRepository.JournalEntry> claimAfter(
            CountDownLatch start,
            CountDownLatch ready,
            String workerId
    ) throws InterruptedException {
        ready.countDown();
        start.await();
        return inTransaction(entityManager -> journal(entityManager)
                .claimRecoverable(workerId, 1, Duration.ofSeconds(30)));
    }

    private static JpaReservationRepositoryAdapter reservation(EntityManager entityManager) {
        return new JpaReservationRepositoryAdapter(entityManager);
    }

    private static JpaInventoryRepositoryAdapter inventory(EntityManager entityManager) {
        return new JpaInventoryRepositoryAdapter(entityManager);
    }

    private static JpaOperationJournalRepositoryAdapter journal(EntityManager entityManager) {
        return new JpaOperationJournalRepositoryAdapter(entityManager);
    }

    private static long seedStock(int stock) {
        long ticketItemId = IDS.incrementAndGet();
        jdbc.update("INSERT INTO ticket_item "
                        + "(id, name, description, stock_initial, stock_available, is_stock_prepared, "
                        + "price_original, price_flash, sale_start_time, sale_end_time, status, activity_id) "
                        + "VALUES (?, 'reservation-test', 'reservation-test', ?, ?, FALSE, 100, 50, "
                        + "'2025-01-01 00:00:00', '2030-01-01 00:00:00', 1, 1)",
                ticketItemId, stock, stock);
        jdbc.update("INSERT INTO inventory_stock_account "
                        + "(ticket_item_id, initial_quantity, available_quantity, admission_state, fence_version, version) "
                        + "VALUES (?, ?, ?, 'OPEN', 0, 0)",
                ticketItemId, stock, stock);
        return ticketItemId;
    }

    private static void insertReservation(
            UUID reservationId,
            long ticketItemId,
            int quantity,
            ReservationStatus status,
            Instant expiresAt
    ) {
        Reservation reservation = new Reservation(
                reservationId,
                ticketItemId,
                UUID.randomUUID(),
                quantity,
                status,
                expiresAt,
                null);
        assertThat((Boolean) inTransaction(entityManager -> reservation(entityManager).insertReserved(
                reservation,
                0L,
                digest("aa"),
                digest("bb")))).isTrue();
    }

    private static OperationJournalRepository.JournalEntry journalEntry(
            UUID operationId,
            long ticketItemId,
            String actorId,
            String idempotencyKeyHash,
            String requestFingerprint
    ) {
        return new OperationJournalRepository.JournalEntry(
                operationId,
                UUID.randomUUID(),
                UUID.nameUUIDFromBytes(actorId.getBytes()),
                digest(idempotencyKeyHash),
                digest(requestFingerprint),
                ticketItemId,
                1,
                0L,
                OperationJournalRepository.JournalState.RECEIVED,
                null,
                null);
    }

    private static String digest(String suffix) {
        return "0".repeat(64 - suffix.length()) + suffix;
    }

    private static <T> T inTransaction(Function<EntityManager, T> work) {
        EntityManager entityManager = entityManagerFactory.createEntityManager();
        EntityTransaction transaction = entityManager.getTransaction();
        transaction.begin();
        try {
            T result = work.apply(entityManager);
            transaction.commit();
            return result;
        } catch (RuntimeException exception) {
            if (transaction.isActive()) {
                transaction.rollback();
            }
            throw exception;
        } finally {
            entityManager.close();
        }
    }
}
