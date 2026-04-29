package com.xxxx.ddd.integration;

import com.xxxx.StartApplication;
import com.xxxx.ddd.application.service.order.cache.StockOrderCacheService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        classes = StartApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "debug=false",
                "logging.level.root=WARN",
                "logging.level.com.xxxx=WARN",
                "logging.level.org.springframework=WARN",
                "spring.jpa.show-sql=false"
        }
)
@EnabledIfSystemProperty(named = "flashsale.integration", matches = "true")
class FlashSaleConcurrencyIntegrationTest {

    private static final long TICKET_ITEM_ID = 4L;
    private static final int STOCK = 20;
    private static final int REQUESTS = 80;
    private static final String YEAR_MONTH = YearMonth.now().format(DateTimeFormatter.ofPattern("yyyyMM"));

    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.4"))
            .withDatabaseName("vetautet")
            .withUsername("root")
            .withPassword("root1234");

    private static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    static {
        if (integrationEnabled()) {
            MYSQL.start();
            REDIS.start();
        }
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private StockOrderCacheService stockOrderCacheService;

    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("app.redisson.single-address", () -> "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
        registry.add("app.warmup.enabled", () -> "false");
    }

    @Test
    void compensatedRedisStrategyPreservesStockInvariantsUnderConcurrency() throws Exception {
        loadSchema();
        post("/admin/benchmarks/reset", Map.of(
                "ticketItemId", TICKET_ITEM_ID,
                "stock", STOCK,
                "yearMonth", YEAR_MONTH
        ));
        post("/admin/tickets/" + TICKET_ITEM_ID + "/stock/warmup", null);

        ExecutorService executor = Executors.newFixedThreadPool(20);
        try {
            List<Callable<Void>> tasks = IntStream.range(0, REQUESTS)
                    .mapToObj(index -> (Callable<Void>) () -> {
                        ResponseEntity<Map> response = post("/orders", Map.of(
                                "ticketItemId", TICKET_ITEM_ID,
                                "userId", 10_000 + index,
                                "quantity", 1,
                                "strategy", "REDIS_LUA_WITH_COMPENSATION",
                                "idempotencyKey", "it-" + index
                        ));
                        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
                        return null;
                    })
                    .toList();
            for (Future<Void> future : executor.invokeAll(tasks)) {
                future.get();
            }
        } finally {
            executor.shutdownNow();
        }

        ResponseEntity<Map> consistency = restTemplate.getForEntity(
                "/admin/benchmarks/consistency?ticketItemId={ticketItemId}&yearMonth={yearMonth}",
                Map.class,
                TICKET_ITEM_ID,
                YEAR_MONTH
        );
        Map<?, ?> result = (Map<?, ?>) consistency.getBody().get("result");

        assertThat(consistency.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Number) result.get("dbOrderCount")).intValue()).isEqualTo(STOCK);
        assertThat(((Number) result.get("oversoldCount")).intValue()).isZero();
        assertThat(((Number) result.get("redisDbInconsistencyCount")).intValue()).isZero();
        assertThat(((Number) result.get("redisStockAfter")).intValue()).isZero();
        assertThat(((Number) result.get("dbStockAfter")).intValue()).isZero();
    }

    /**
     * Proves the reconciliation mechanism detects and repairs Redis drift.
     *
     * <p>Scenario: After a normal run, we manually decrement Redis by 3 units
     * (simulating 3 "lost stock" events from JVM crashes or compensation failures).
     * The consistency endpoint should detect a drift of -3.
     * After calling the reconcile endpoint, Redis should match DB again.
     */
    @Test
    void reconciliationRepairsRedisDriftAfterCompensationFailure() throws Exception {
        loadSchema();

        // Reset to a known state: stock = 10, no orders
        int initialStock = 10;
        post("/admin/benchmarks/reset", Map.of(
                "ticketItemId", TICKET_ITEM_ID,
                "stock", initialStock,
                "yearMonth", YEAR_MONTH
        ));
        post("/admin/tickets/" + TICKET_ITEM_ID + "/stock/warmup", null);

        // Place 5 orders normally
        int ordersToPlace = 5;
        for (int i = 0; i < ordersToPlace; i++) {
            ResponseEntity<Map> orderResp = post("/orders", Map.of(
                    "ticketItemId", TICKET_ITEM_ID,
                    "userId", 20_000 + i,
                    "quantity", 1,
                    "strategy", "REDIS_LUA_WITH_COMPENSATION",
                    "idempotencyKey", "recon-" + i
            ));
            assertThat(orderResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        // Verify: Redis=5, DB=5, no drift
        Map<?, ?> beforeDrift = getConsistencyResult();
        assertThat(((Number) beforeDrift.get("redisStockAfter")).intValue()).isEqualTo(initialStock - ordersToPlace);
        assertThat(((Number) beforeDrift.get("dbStockAfter")).intValue()).isEqualTo(initialStock - ordersToPlace);
        assertThat(((Number) beforeDrift.get("driftAmount")).intValue()).isZero();

        // Simulate "Lost Stock" drift: directly set Redis 3 units below what it should be.
        // This mimics JVM crashes or compensation failures where Redis was decremented
        // but the corresponding DB order was never committed.
        int simulatedLostUnits = 3;
        int currentRedis = ((Number) beforeDrift.get("redisStockAfter")).intValue();
        int corruptedRedis = currentRedis - simulatedLostUnits; // 5 - 3 = 2
        stockOrderCacheService.setStockCache(TICKET_ITEM_ID, corruptedRedis);

        // Verify drift is detected
        Map<?, ?> driftedState = getConsistencyResult();
        assertThat(((Number) driftedState.get("redisStockAfter")).intValue()).isEqualTo(corruptedRedis);
        assertThat(((Number) driftedState.get("dbStockAfter")).intValue()).isEqualTo(initialStock - ordersToPlace);
        assertThat(((Number) driftedState.get("driftAmount")).intValue()).isEqualTo(-simulatedLostUnits);
        assertThat(((Number) driftedState.get("redisDbInconsistencyCount")).intValue()).isEqualTo(1);

        // Trigger reconciliation
        ResponseEntity<Map> reconcileResp = post(
                "/admin/benchmarks/reconcile?ticketItemId=" + TICKET_ITEM_ID + "&yearMonth=" + YEAR_MONTH,
                null
        );
        assertThat(reconcileResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> reconcileResult = (Map<?, ?>) reconcileResp.getBody().get("result");
        assertThat(((Boolean) reconcileResult.get("driftDetected"))).isTrue();
        assertThat(((Boolean) reconcileResult.get("repaired"))).isTrue();
        assertThat(((Number) reconcileResult.get("driftAmount")).intValue()).isEqualTo(-simulatedLostUnits);

        // Verify: after reconciliation, drift should be zero
        Map<?, ?> healedState = getConsistencyResult();
        assertThat(((Number) healedState.get("redisStockAfter")).intValue())
                .isEqualTo(initialStock - ordersToPlace);
        assertThat(((Number) healedState.get("driftAmount")).intValue()).isZero();
        assertThat(((Number) healedState.get("redisDbInconsistencyCount")).intValue()).isZero();
    }

    private Map<?, ?> getConsistencyResult() {
        ResponseEntity<Map> consistency = restTemplate.getForEntity(
                "/admin/benchmarks/consistency?ticketItemId={ticketItemId}&yearMonth={yearMonth}",
                Map.class,
                TICKET_ITEM_ID,
                YEAR_MONTH
        );
        assertThat(consistency.getStatusCode()).isEqualTo(HttpStatus.OK);
        return (Map<?, ?>) consistency.getBody().get("result");
    }

    private ResponseEntity<Map> post(String url, Object body) {
        return restTemplate.postForEntity(url, body, Map.class);
    }

    private void loadSchema() {
        Path initScript = Path.of("../../../environment/mysql/init/ticket_init.sql").normalize();
        if (!Files.isRegularFile(initScript)) {
            initScript = Path.of("environment/mysql/init/ticket_init.sql").normalize();
        }
        new ResourceDatabasePopulator(new FileSystemResource(initScript)).execute(dataSource);
    }

    private static boolean integrationEnabled() {
        return Boolean.parseBoolean(System.getProperty("flashsale.integration", "false"));
    }
}

