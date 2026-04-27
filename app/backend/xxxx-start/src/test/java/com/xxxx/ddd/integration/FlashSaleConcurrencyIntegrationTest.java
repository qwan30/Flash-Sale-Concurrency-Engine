package com.xxxx.ddd.integration;

import com.xxxx.StartApplication;
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
