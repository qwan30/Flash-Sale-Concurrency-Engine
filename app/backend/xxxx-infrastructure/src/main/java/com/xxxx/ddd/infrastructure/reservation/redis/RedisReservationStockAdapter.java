package com.xxxx.ddd.infrastructure.reservation.redis;

import com.xxxx.ddd.application.reservation.port.ReservationStockPort;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Component
public class RedisReservationStockAdapter implements ReservationStockPort {

    public static final int OPERATION_TTL_SECONDS = 7 * 24 * 60 * 60;

    private static final Set<String> REPAIR_DISPOSITIONS = Set.of(
            "VERIFIED", "COMPENSATED", "COMMITTED", "REJECTED");

    private final StringRedisTemplate redis;
    private final DefaultRedisScript<String> applyScript;
    private final DefaultRedisScript<String> compensateScript;
    private final DefaultRedisScript<String> terminalMirrorScript;
    private final DefaultRedisScript<String> fencePublishScript;
    private final DefaultRedisScript<String> repairMirrorScript;

    public RedisReservationStockAdapter(StringRedisTemplate redis) {
        this.redis = Objects.requireNonNull(redis, "redis must not be null");
        this.applyScript = script("redis/reservation-apply-once.lua");
        this.compensateScript = script("redis/reservation-compensate-once.lua");
        this.terminalMirrorScript = script("redis/reservation-terminal-mirror-once.lua");
        this.fencePublishScript = script("redis/reservation-fence-publish.lua");
        this.repairMirrorScript = script("redis/reservation-repair-mirror.lua");
    }

    @Override
    public RedisApplyResult applyOnce(UUID operationId, long ticketItemId, int quantity, long fenceVersion) {
        validateOperation(operationId, ticketItemId, quantity, fenceVersion);
        String raw = execute(
                applyScript,
                List.of(stockKey(ticketItemId), operationKey(operationId)),
                Integer.toString(quantity),
                Long.toString(ticketItemId),
                Long.toString(fenceVersion),
                Integer.toString(OPERATION_TTL_SECONDS));
        return parseApplyResult(raw);
    }

    @Override
    public RedisCompensationResult compensateOnce(
            UUID operationId,
            long ticketItemId,
            int quantity,
            long fenceVersion
    ) {
        validateOperation(operationId, ticketItemId, quantity, fenceVersion);
        String raw = execute(
                compensateScript,
                List.of(stockKey(ticketItemId), operationKey(operationId)),
                Integer.toString(quantity),
                Long.toString(ticketItemId),
                Long.toString(fenceVersion),
                Integer.toString(OPERATION_TTL_SECONDS));
        return parseCompensationResult(raw);
    }

    @Override
    public void mirrorTerminalOnce(UUID operationId, long ticketItemId, int delta, long fenceVersion) {
        if (delta == 0 || delta < -4 || delta > 4) {
            throw new IllegalArgumentException("delta must be between -4 and 4 and not zero");
        }
        validateOperation(operationId, ticketItemId, Math.abs(delta), fenceVersion);
        String raw = execute(
                terminalMirrorScript,
                List.of(stockKey(ticketItemId), operationKey(operationId)),
                Integer.toString(delta),
                Long.toString(ticketItemId),
                Long.toString(fenceVersion),
                Integer.toString(OPERATION_TTL_SECONDS));
        if (!(raw.startsWith("MIRRORED:") || raw.startsWith("REPLAYED:"))) {
            throw new IllegalStateException("terminal mirror rejected: " + raw);
        }
    }

    @Override
    public Optional<RedisOperationState> operationState(UUID operationId) {
        Objects.requireNonNull(operationId, "operationId must not be null");
        Map<Object, Object> fields = redis.opsForHash().entries(operationKey(operationId));
        if (fields.isEmpty()) {
            return Optional.empty();
        }

        String state = value(fields, "state");
        Integer stockAfter = optionalInteger(fields, "stock_after");
        ReservationStockPort.RedisOperationState.Status status = switch (state) {
            case "APPLIED" -> ReservationStockPort.RedisOperationState.Status.APPLIED;
            case "COMPENSATED" -> ReservationStockPort.RedisOperationState.Status.COMPENSATED;
            case "MIRRORED" -> ReservationStockPort.RedisOperationState.Status.MIRRORED;
            case "REPAIRED" -> ReservationStockPort.RedisOperationState.Status.REPAIRED;
            case "SOLD_OUT" -> ReservationStockPort.RedisOperationState.Status.SOLD_OUT;
            case "STALE_FENCE" -> ReservationStockPort.RedisOperationState.Status.STALE_FENCE;
            case "CONFLICT" -> ReservationStockPort.RedisOperationState.Status.CONFLICT;
            default -> throw new IllegalStateException("unknown Redis operation state");
        };
        return Optional.of(new RedisOperationState(status, stockAfter));
    }

    public String publishFence(long ticketItemId, long fenceVersion, String admissionState) {
        if (ticketItemId <= 0 || fenceVersion < 0 || admissionState == null
                || !Set.of("OPEN", "DRAINING", "CLOSED").contains(admissionState)) {
            throw new IllegalArgumentException("invalid fence publication");
        }
        return execute(
                fencePublishScript,
                List.of(stockKey(ticketItemId)),
                Long.toString(fenceVersion),
                admissionState);
    }

    public String repairMirror(
            UUID repairId,
            long ticketItemId,
            long fenceVersion,
            int initial,
            int available,
            int reserved,
            int confirmed,
            String disposition
    ) {
        Objects.requireNonNull(repairId, "repairId must not be null");
        if (ticketItemId <= 0 || fenceVersion < 0 || initial < 0 || available < 0
                || reserved < 0 || confirmed < 0 || available > initial
                || initial != (long) available + reserved + confirmed
                || disposition == null || !REPAIR_DISPOSITIONS.contains(disposition)) {
            throw new IllegalArgumentException("invalid repair mirror");
        }
        return execute(
                repairMirrorScript,
                List.of(stockKey(ticketItemId), operationKey(repairId)),
                Long.toString(fenceVersion),
                Integer.toString(initial),
                Integer.toString(available),
                Integer.toString(reserved),
                Integer.toString(confirmed),
                disposition,
                Integer.toString(OPERATION_TTL_SECONDS));
    }

    private String execute(DefaultRedisScript<String> script, List<String> keys, Object... args) {
        String result = redis.execute(script, keys, args);
        if (result == null || result.isBlank()) {
            throw new IllegalStateException("Redis reservation script returned no result");
        }
        return result;
    }

    private static RedisApplyResult parseApplyResult(String raw) {
        if (raw.equals("STALE_FENCE")) {
            return RedisApplyResult.staleFence();
        }
        if (raw.equals("CONFLICT")) {
            return RedisApplyResult.conflict();
        }
        return parseStockResult(raw, "apply");
    }

    private static RedisApplyResult parseStockResult(String raw, String operation) {
        int separator = raw.indexOf(':');
        if (separator < 1 || separator == raw.length() - 1) {
            throw new IllegalStateException(operation + " script returned an invalid result");
        }
        String code = raw.substring(0, separator);
        int stockAfter = parseStock(raw.substring(separator + 1));
        return switch (code) {
            case "APPLIED" -> RedisApplyResult.applied(stockAfter);
            case "REPLAYED" -> RedisApplyResult.replayed(stockAfter);
            case "SOLD_OUT" -> RedisApplyResult.soldOut(stockAfter);
            default -> throw new IllegalStateException(operation + " script returned an unknown result");
        };
    }

    private static RedisCompensationResult parseCompensationResult(String raw) {
        if (raw.equals("NOT_APPLIED")) {
            return RedisCompensationResult.notApplied();
        }
        if (raw.equals("STALE_FENCE")) {
            return RedisCompensationResult.staleFence();
        }
        if (raw.equals("CONFLICT")) {
            return RedisCompensationResult.conflict();
        }
        int separator = raw.indexOf(':');
        if (separator < 1 || separator == raw.length() - 1) {
            throw new IllegalStateException("compensation script returned an invalid result");
        }
        int stockAfter = parseStock(raw.substring(separator + 1));
        return switch (raw.substring(0, separator)) {
            case "COMPENSATED" -> RedisCompensationResult.compensated(stockAfter);
            case "REPLAYED" -> RedisCompensationResult.replayed(stockAfter);
            default -> throw new IllegalStateException("compensation script returned an unknown result");
        };
    }

    private static int parseStock(String rawStock) {
        try {
            int stock = Integer.parseInt(rawStock);
            if (stock < 0) {
                throw new NumberFormatException("negative stock");
            }
            return stock;
        } catch (NumberFormatException exception) {
            throw new IllegalStateException("Redis reservation script returned invalid stock", exception);
        }
    }

    private static String value(Map<Object, Object> fields, String key) {
        Object value = fields.get(key);
        if (value == null) {
            throw new IllegalStateException("Redis operation is missing a state field");
        }
        return value.toString();
    }

    private static Integer optionalInteger(Map<Object, Object> fields, String key) {
        Object value = fields.get(key);
        return value == null ? null : parseStock(value.toString());
    }

    private static void validateOperation(UUID operationId, long ticketItemId, int quantity, long fenceVersion) {
        Objects.requireNonNull(operationId, "operationId must not be null");
        if (ticketItemId <= 0 || quantity < 1 || quantity > 4 || fenceVersion < 0) {
            throw new IllegalArgumentException("invalid reservation operation");
        }
    }

    private static String stockKey(long ticketItemId) {
        return "flashsale:reservation:stock:" + ticketItemId;
    }

    private static String operationKey(UUID operationId) {
        return "flashsale:reservation:op:" + operationId;
    }

    private static DefaultRedisScript<String> script(String path) {
        try (InputStream sourceStream = new ClassPathResource(path).getInputStream()) {
            String source = StreamUtils.copyToString(sourceStream, StandardCharsets.UTF_8);
            return new DefaultRedisScript<>(source, String.class);
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to load Redis script " + path, ex);
        }
    }
}
