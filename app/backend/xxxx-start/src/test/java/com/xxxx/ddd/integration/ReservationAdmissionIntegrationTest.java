package com.xxxx.ddd.integration;

import com.xxxx.StartApplication;
import com.xxxx.ddd.application.MQ.OutboxPublishScheduler;
import com.xxxx.ddd.application.reservation.ConfirmReservationService;
import com.xxxx.ddd.application.reservation.CreateReservationResult;
import com.xxxx.ddd.application.reservation.CreateReservationService;
import com.xxxx.ddd.application.reservation.ReleaseReservationService;
import com.xxxx.ddd.application.reservation.ReservationExpiryScheduler;
import com.xxxx.ddd.application.reservation.ReservationLifecycleResult;
import com.xxxx.ddd.application.reservation.ReservationRecoveryScheduler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;

@Testcontainers
@EnabledIfSystemProperty(named = "flashsale.integration", matches = "true")
@SpringBootTest(
        classes = StartApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "debug=false",
                "logging.level.root=WARN",
                "logging.level.com.xxxx=WARN",
                "logging.level.org.springframework=WARN",
                "spring.jpa.show-sql=false",
                "spring.task.scheduling.enabled=false"
        })
@AutoConfigureMockMvc
class ReservationAdmissionIntegrationTest {

    private static final UUID ACTOR_ID = UUID.fromString("73e4b5c9-1d67-4d3a-8db8-2e3e21c27844");
    private static final UUID RESERVATION_ID = UUID.fromString("2f03a82c-ab45-4f87-b8ea-3d407198f5c2");

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

    @MockBean
    private CreateReservationService creation;

    @MockBean
    private ConfirmReservationService confirmation;

    @MockBean
    private ReleaseReservationService release;

    @Autowired
    private MockMvc mvc;

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
    void resetRateLimiter() throws InterruptedException {
        Thread.sleep(1_100);
    }

    @Test
    void shedsCreateBurstAfterRateLimitWindowIsConsumed() throws Exception {
        when(creation.create(any())).thenReturn(new CreateReservationResult(
                CreateReservationResult.Outcome.NEW,
                UUID.randomUUID(),
                RESERVATION_ID,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                "NEW",
                OptionalInt.of(7)));

        ExecutorService executor = Executors.newFixedThreadPool(45);
        try {
            CyclicBarrier startTogether = new CyclicBarrier(45);
            var futures = IntStream.range(0, 45)
                    .mapToObj(index -> executor.submit(() -> {
                        startTogether.await(10, TimeUnit.SECONDS);
                        return createRequest("rate-key-" + index);
                    }))
                    .toList();
            var results = futures.stream().map(this::getUnchecked).toList();
            var rejected = results.stream().filter(result -> result.getResponse().getStatus() == 429).toList();

            assertThat(rejected).isNotEmpty();
            assertThat(rejected.get(0).getResponse().getHeader("Retry-After")).isEqualTo("1");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void createBulkheadShedsWithoutBlockingTerminalLane() throws Exception {
        CountDownLatch fourCreatesEntered = new CountDownLatch(4);
        CountDownLatch releaseCreates = new CountDownLatch(1);
        AtomicInteger createCalls = new AtomicInteger();
        when(creation.create(any())).thenAnswer(invocation -> {
            if (createCalls.incrementAndGet() <= 4) {
                fourCreatesEntered.countDown();
                assertThat(releaseCreates.await(10, TimeUnit.SECONDS)).isTrue();
            }
            return new CreateReservationResult(
                    CreateReservationResult.Outcome.NEW,
                    UUID.randomUUID(),
                    RESERVATION_ID,
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    "NEW",
                    OptionalInt.of(7));
        });
        when(release.release(RESERVATION_ID)).thenReturn(new ReservationLifecycleResult(
                ReservationLifecycleResult.Outcome.RELEASED,
                Optional.empty(),
                Optional.empty(),
                Optional.empty()));

        ExecutorService executor = Executors.newFixedThreadPool(5);
        try {
            Future<Integer>[] blockedCreates = IntStream.range(0, 4)
                    .mapToObj(index -> executor.submit(() -> createStatus("bulkhead-key-" + index)))
                    .toArray(Future[]::new);
            assertThat(fourCreatesEntered.await(10, TimeUnit.SECONDS)).isTrue();

            MvcResult overflow = createRequest("bulkhead-overflow");
            assertThat(overflow.getResponse().getStatus()).isEqualTo(503);
            assertThat(overflow.getResponse().getHeader("Retry-After")).isEqualTo("1");
            mvc.perform(post("/api/v1/reservations/{id}/release", RESERVATION_ID))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());

            releaseCreates.countDown();
            for (Future<Integer> blockedCreate : blockedCreates) {
                assertThat(blockedCreate.get(10, TimeUnit.SECONDS)).isEqualTo(201);
            }
        } finally {
            releaseCreates.countDown();
            executor.shutdownNow();
        }
    }

    private int createStatus(String idempotencyKey) throws Exception {
        return createRequest(idempotencyKey).getResponse().getStatus();
    }

    private MvcResult createRequest(String idempotencyKey) throws Exception {
        return mvc.perform(post("/api/v1/reservations")
                        .header("Idempotency-Key", UUID.nameUUIDFromBytes(idempotencyKey.getBytes()))
                        .header("X-Demo-Actor-Id", ACTOR_ID)
                        .contentType("application/json")
                        .content("{\"ticketItemId\":42,\"quantity\":1}"))
                .andReturn();
    }

    private MvcResult getUnchecked(Future<MvcResult> future) {
        try {
            return future.get(10, TimeUnit.SECONDS);
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }
}
