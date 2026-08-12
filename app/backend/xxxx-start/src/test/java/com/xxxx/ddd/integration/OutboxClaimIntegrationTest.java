package com.xxxx.ddd.integration;

import com.xxxx.StartApplication;
import com.xxxx.ddd.application.MQ.OutboxEvent;
import com.xxxx.ddd.application.MQ.OutboxRepository;
import com.xxxx.ddd.application.MQ.OutboxStatus;
import com.xxxx.ddd.application.reservation.ReservationExpiryScheduler;
import com.xxxx.ddd.application.reservation.ReservationRecoveryScheduler;
import com.xxxx.ddd.application.MQ.OutboxPublishScheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
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
                "spring.task.scheduling.enabled=false"
        })
class OutboxClaimIntegrationTest {

    private static final String AGGREGATE_ID = "outbox-claim-integration";

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
    private OutboxRepository repository;

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

    @AfterEach
    void cleanFixture() {
        List<OutboxEvent> events = repository.findAll().stream()
                .filter(event -> AGGREGATE_ID.equals(event.getAggregateId()))
                .toList();
        repository.deleteAll(events);
        repository.flush();
    }

    @Test
    void oneLeaseOwnerClaimsAnEventAndExpiredLeaseCanBeReclaimed() {
        repository.saveAndFlush(new OutboxEvent(
                UUID.randomUUID().toString(),
                "Reservation",
                AGGREGATE_ID,
                "reservation.released",
                1,
                "{}"));

        Instant firstLease = Instant.now().plusSeconds(30).truncatedTo(ChronoUnit.MILLIS);
        assertThat(repository.claimPending("worker-a", firstLease, 1)).isEqualTo(1);
        assertThat(repository.findByLeaseOwnerAndLeaseUntilOrderByCreatedAtAsc("worker-a", firstLease))
                .hasSize(1);
        assertThat(repository.claimPending("worker-b", firstLease, 1)).isZero();

        OutboxEvent leased = repository
                .findByLeaseOwnerAndLeaseUntilOrderByCreatedAtAsc("worker-a", firstLease)
                .get(0);
        leased.setLeaseUntil(Instant.now().minusSeconds(1));
        repository.saveAndFlush(leased);

        Instant secondLease = Instant.now().plusSeconds(30).truncatedTo(ChronoUnit.MILLIS);
        assertThat(repository.claimPending("worker-b", secondLease, 1)).isEqualTo(1);
        assertThat(repository.findByLeaseOwnerAndLeaseUntilOrderByCreatedAtAsc("worker-b", secondLease))
                .hasSize(1);
    }

    @Test
    void concurrentRelayWorkersOnlyOneClaimsAnEventBeforeLeaseExpiry() throws Exception {
        OutboxEvent event = repository.saveAndFlush(new OutboxEvent(
                UUID.randomUUID().toString(),
                "Reservation",
                AGGREGATE_ID,
                "reservation.created",
                1,
                "{}"));

        Instant firstLease = Instant.now().plusSeconds(30).truncatedTo(ChronoUnit.MILLIS);
        Instant secondLease = firstLease.plus(1, ChronoUnit.MILLIS);
        CyclicBarrier startGate = new CyclicBarrier(2);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> firstClaim = workers.submit(() -> {
                startGate.await();
                return repository.claimPending("worker-a", firstLease, 1);
            });
            Future<Integer> secondClaim = workers.submit(() -> {
                startGate.await();
                return repository.claimPending("worker-b", secondLease, 1);
            });

            assertThat(firstClaim.get() + secondClaim.get()).isEqualTo(1);
            assertThat(repository.findByLeaseOwnerAndLeaseUntilOrderByCreatedAtAsc("worker-a", firstLease).size()
                    + repository.findByLeaseOwnerAndLeaseUntilOrderByCreatedAtAsc("worker-b", secondLease).size())
                    .isEqualTo(1);
            assertThat(event.getEventId()).isEqualTo(event.getId());
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    void reclaimedLeaseCannotBeFinalizedByStaleWorkerAndCurrentOwnerIsIdempotent() {
        OutboxEvent event = repository.saveAndFlush(new OutboxEvent(
                UUID.randomUUID().toString(),
                "Reservation",
                AGGREGATE_ID,
                "reservation.created",
                1,
                "{}"));

        Instant firstLease = Instant.now().plusSeconds(30).truncatedTo(ChronoUnit.MILLIS);
        assertThat(repository.claimPending("worker-a", firstLease, 1)).isEqualTo(1);
        OutboxEvent leased = repository
                .findByLeaseOwnerAndLeaseUntilOrderByCreatedAtAsc("worker-a", firstLease)
                .get(0);
        leased.setLeaseUntil(Instant.now().minusSeconds(1));
        repository.saveAndFlush(leased);

        Instant secondLease = Instant.now().plusSeconds(30).truncatedTo(ChronoUnit.MILLIS);
        assertThat(repository.claimPending("worker-b", secondLease, 1)).isEqualTo(1);
        Instant renewedLease = Instant.now().plusSeconds(30).truncatedTo(ChronoUnit.MILLIS);
        assertThat(repository.renewLeaseIfOwned(
                event.getId(), "worker-a", firstLease, renewedLease, Instant.now())).isZero();
        assertThat(repository.renewLeaseIfOwned(
                event.getId(), "worker-b", secondLease, renewedLease, Instant.now())).isEqualTo(1);
        assertThat(repository.markPublishedIfOwned(
                event.getId(), "worker-a", firstLease, Instant.now())).isZero();
        assertThat(repository.markPublishedIfOwned(
                event.getId(), "worker-b", renewedLease, Instant.now())).isEqualTo(1);
        assertThat(repository.markPublishedIfOwned(
                event.getId(), "worker-b", renewedLease, Instant.now())).isZero();
        assertThat(repository.findById(event.getId()).orElseThrow().getStatus())
                .isEqualTo(OutboxStatus.PUBLISHED);
    }

    @Test
    void failureFinalizationPreservesRetryBudgetAndStopsAtTheConfiguredMaximum() {
        OutboxEvent event = repository.saveAndFlush(new OutboxEvent(
                UUID.randomUUID().toString(),
                "Reservation",
                AGGREGATE_ID,
                "reservation.created",
                1,
                "{}"));
        Instant firstLease = Instant.now().plusSeconds(30).truncatedTo(ChronoUnit.MILLIS);
        Instant firstNow = Instant.now();
        assertThat(repository.claimPending("worker-a", firstLease, 1)).isEqualTo(1);
        assertThat(repository.markFailedIfOwned(
                event.getId(),
                "worker-a",
                firstLease,
                "kafka unavailable",
                firstNow.plusSeconds(10),
                firstNow,
                2)).isEqualTo(1);

        OutboxEvent firstFailure = repository.findById(event.getId()).orElseThrow();
        assertThat(firstFailure.getAttemptCount()).isEqualTo(1);
        assertThat(firstFailure.getNextAttemptAt()).isNotNull();

        firstFailure.setNextAttemptAt(Instant.now().minusSeconds(1));
        repository.saveAndFlush(firstFailure);
        assertThat(repository.requeueFailed(Instant.now(), 1)).isEqualTo(1);
        Instant secondLease = Instant.now().plusSeconds(30).truncatedTo(ChronoUnit.MILLIS);
        Instant secondNow = Instant.now();
        assertThat(repository.claimPending("worker-b", secondLease, 1)).isEqualTo(1);
        assertThat(repository.markFailedIfOwned(
                event.getId(),
                "worker-b",
                secondLease,
                "kafka unavailable",
                secondNow.plusSeconds(10),
                secondNow,
                2)).isEqualTo(1);

        OutboxEvent exhausted = repository.findById(event.getId()).orElseThrow();
        assertThat(exhausted.getAttemptCount()).isEqualTo(2);
        assertThat(exhausted.getNextAttemptAt()).isNull();
        assertThat(exhausted.getStatus()).isEqualTo(OutboxStatus.FAILED);
    }

    @Test
    void concurrentRetryWorkersRequeueADueEventOnlyOnce() throws Exception {
        OutboxEvent event = new OutboxEvent(
                UUID.randomUUID().toString(),
                "Reservation",
                AGGREGATE_ID,
                "reservation.created",
                1,
                "{}");
        event.setStatus(OutboxStatus.FAILED);
        event.setNextAttemptAt(Instant.now().minusSeconds(1));
        repository.saveAndFlush(event);

        CyclicBarrier startGate = new CyclicBarrier(2);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> firstRetry = workers.submit(() -> {
                startGate.await();
                return repository.requeueFailed(Instant.now(), 1);
            });
            Future<Integer> secondRetry = workers.submit(() -> {
                startGate.await();
                return repository.requeueFailed(Instant.now(), 1);
            });

            assertThat(firstRetry.get() + secondRetry.get()).isEqualTo(1);
            assertThat(repository.findById(event.getId()).orElseThrow().getStatus())
                    .isEqualTo(OutboxStatus.PENDING);
        } finally {
            workers.shutdownNow();
        }
    }
}
