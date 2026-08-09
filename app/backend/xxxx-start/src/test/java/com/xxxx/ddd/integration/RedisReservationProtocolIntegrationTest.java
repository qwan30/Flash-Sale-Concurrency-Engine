package com.xxxx.ddd.integration;

import com.xxxx.ddd.application.reservation.port.ReservationStockPort;
import com.xxxx.ddd.infrastructure.reservation.redis.RedisReservationStockAdapter;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class RedisReservationProtocolIntegrationTest {

    private static final long TICKET_ITEM_ID = 42L;

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    private static RedisConnectionFactory connectionFactory;
    private static StringRedisTemplate redis;
    private RedisReservationStockAdapter adapter;

    @BeforeAll
    static void connectRedis() {
        LettuceConnectionFactory lettuce = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        lettuce.afterPropertiesSet();
        connectionFactory = lettuce;
        redis = new StringRedisTemplate(lettuce);
        redis.afterPropertiesSet();
    }

    @AfterAll
    static void closeRedis() {
        if (connectionFactory instanceof LettuceConnectionFactory lettuce) {
            lettuce.destroy();
        }
    }

    @BeforeEach
    void resetRedis() {
        redis.execute((RedisCallback<Void>) connection -> {
            connection.serverCommands().flushDb();
            return null;
        });
        adapter = new RedisReservationStockAdapter(redis);
    }

    @Test
    void firstApplyDecrementsOnceAndReplayReturnsTheOriginalStock() {
        seedStock(10, 10, 0, "OPEN");
        UUID operationId = UUID.randomUUID();

        ReservationStockPort.RedisApplyResult first = adapter.applyOnce(operationId, TICKET_ITEM_ID, 2, 0L);
        ReservationStockPort.RedisApplyResult replay = adapter.applyOnce(operationId, TICKET_ITEM_ID, 2, 0L);

        assertThat(first).isEqualTo(ReservationStockPort.RedisApplyResult.applied(8));
        assertThat(replay).isEqualTo(ReservationStockPort.RedisApplyResult.replayed(8));
        assertThat(stockField("available")).isEqualTo("8");
        assertThat(redis.getExpire(operationKey(operationId), TimeUnit.SECONDS)).isBetween(1L, 604800L);
        assertThat(adapter.operationState(operationId))
                .contains(ReservationStockPort.RedisOperationState.applied(8));
    }

    @Test
    void insufficientStockNeverDecrements() {
        seedStock(3, 3, 0, "OPEN");

        ReservationStockPort.RedisApplyResult result = adapter.applyOnce(UUID.randomUUID(), TICKET_ITEM_ID, 4, 0L);

        assertThat(result).isEqualTo(ReservationStockPort.RedisApplyResult.soldOut(3));
        assertThat(stockField("available")).isEqualTo("3");
    }

    @Test
    void staleFenceNeverMutatesAndDoesNotExposeStock() {
        seedStock(10, 10, 4, "OPEN");
        UUID operationId = UUID.randomUUID();

        ReservationStockPort.RedisApplyResult result = adapter.applyOnce(operationId, TICKET_ITEM_ID, 2, 3L);

        assertThat(result).isEqualTo(ReservationStockPort.RedisApplyResult.staleFence());
        assertThat(stockField("available")).isEqualTo("10");
        assertThat(adapter.operationState(operationId))
                .contains(new ReservationStockPort.RedisOperationState(
                        ReservationStockPort.RedisOperationState.Status.STALE_FENCE, null));
    }

    @Test
    void fencePublicationAcceptsOnlyGreaterVersionsAndPublishesStateAtomically() {
        seedStock(10, 10, 0, "OPEN");

        assertThat(adapter.publishFence(TICKET_ITEM_ID, 1L, "DRAINING")).isEqualTo("PUBLISHED");
        assertThat(adapter.publishFence(TICKET_ITEM_ID, 1L, "CLOSED")).isEqualTo("STALE_FENCE");
        assertThat(adapter.publishFence(TICKET_ITEM_ID, 0L, "OPEN")).isEqualTo("STALE_FENCE");

        assertThat(stockField("fence")).isEqualTo("1");
        assertThat(stockField("admission_state")).isEqualTo("DRAINING");
    }

    @Test
    void repairMirrorWritesOnlyWhileClosedAndRecordsDisposition() {
        UUID repairId = UUID.randomUUID();
        seedStock(10, 0, 2, "CLOSED");

        assertThat(adapter.repairMirror(repairId, TICKET_ITEM_ID, 2L, 10, 7, 2, 1, "VERIFIED"))
                .isEqualTo("REPAIRED");
        assertThat(stockField("available")).isEqualTo("7");
        assertThat(stockField("reserved")).isEqualTo("2");
        assertThat(stockField("confirmed")).isEqualTo("1");
        assertThat(redis.opsForHash().get(operationKey(repairId), "disposition"))
                .isEqualTo("VERIFIED");

        assertThat(adapter.publishFence(TICKET_ITEM_ID, 3L, "OPEN")).isEqualTo("PUBLISHED");
        assertThat(adapter.repairMirror(UUID.randomUUID(), TICKET_ITEM_ID, 2L, 10, 8, 1, 1, "VERIFIED"))
                .isEqualTo("REPAIR_REQUIRED");
    }

    @Test
    void compensationIncrementsOnlyFromAppliedAndSecondCompensationIsNoOp() {
        seedStock(10, 8, 0, "OPEN");
        UUID operationId = UUID.randomUUID();
        adapter.applyOnce(operationId, TICKET_ITEM_ID, 2, 0L);

        ReservationStockPort.RedisCompensationResult first = adapter.compensateOnce(
                operationId, TICKET_ITEM_ID, 2, 0L);
        ReservationStockPort.RedisCompensationResult replay = adapter.compensateOnce(
                operationId, TICKET_ITEM_ID, 2, 0L);

        assertThat(first).isEqualTo(ReservationStockPort.RedisCompensationResult.compensated(8));
        assertThat(replay).isEqualTo(ReservationStockPort.RedisCompensationResult.replayed(8));
        assertThat(stockField("available")).isEqualTo("8");
    }

    @Test
    void terminalMirrorAppliesDeltaExactlyOnce() {
        seedStock(10, 5, 0, "OPEN");
        UUID operationId = UUID.randomUUID();

        adapter.mirrorTerminalOnce(operationId, TICKET_ITEM_ID, 3, 0L);
        adapter.mirrorTerminalOnce(operationId, TICKET_ITEM_ID, 3, 0L);

        assertThat(stockField("available")).isEqualTo("8");
    }

    @Test
    void mismatchedOperationArgumentsCannotReuseTheOperationToken() {
        seedStock(10, 10, 0, "OPEN");
        UUID operationId = UUID.randomUUID();
        adapter.applyOnce(operationId, TICKET_ITEM_ID, 2, 0L);

        assertThat(adapter.applyOnce(operationId, TICKET_ITEM_ID, 3, 0L))
                .isEqualTo(ReservationStockPort.RedisApplyResult.conflict());
        assertThat(stockField("available")).isEqualTo("8");
        assertThatThrownBy(() -> adapter.mirrorTerminalOnce(operationId, TICKET_ITEM_ID, 3, 0L))
                .isInstanceOf(IllegalStateException.class);
    }

    private void seedStock(int initial, int available, long fence, String admissionState) {
        redis.opsForHash().putAll(stockKey(), Map.of(
                "initial", Integer.toString(initial),
                "available", Integer.toString(available),
                "reserved", "0",
                "confirmed", "0",
                "fence", Long.toString(fence),
                "admission_state", admissionState));
    }

    private String stockField(String field) {
        Object value = redis.opsForHash().get(stockKey(), field);
        return value == null ? null : value.toString();
    }

    private String stockKey() {
        return "flashsale:reservation:stock:" + TICKET_ITEM_ID;
    }

    private static String operationKey(UUID operationId) {
        return "flashsale:reservation:op:" + operationId;
    }
}
