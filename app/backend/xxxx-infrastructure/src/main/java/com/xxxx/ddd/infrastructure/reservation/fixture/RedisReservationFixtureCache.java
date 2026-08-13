package com.xxxx.ddd.infrastructure.reservation.fixture;

import com.xxxx.ddd.application.reservation.port.ReservationFixtureCache;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/** Redis adapter for the local reservation fixture stock hash. */
@Component
public class RedisReservationFixtureCache implements ReservationFixtureCache {

    private static final String STOCK_KEY_PREFIX = "flashsale:reservation:stock:";
    private static final Set<String> ADMISSION_STATES = Set.of("OPEN", "DRAINING", "CLOSED");

    private final StringRedisTemplate redis;

    public RedisReservationFixtureCache(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public void reset(long ticketItemId, int initial, int available, int reserved, int confirmed,
                      long fenceVersion, String admissionState) {
        validate(ticketItemId, initial, available, reserved, confirmed, fenceVersion, admissionState);
        String key = stockKey(ticketItemId);
        redis.delete(key);
        redis.opsForHash().putAll(key, Map.of(
                "initial", Integer.toString(initial),
                "available", Integer.toString(available),
                "reserved", Integer.toString(reserved),
                "confirmed", Integer.toString(confirmed),
                "fence", Long.toString(fenceVersion),
                "admission_state", admissionState));
    }

    @Override
    public CacheState read(long ticketItemId) {
        Map<Object, Object> raw = redis.opsForHash().entries(stockKey(ticketItemId));
        if (raw.isEmpty()) {
            throw new IllegalStateException("Redis reservation fixture hash is missing");
        }
        return new CacheState(
                ticketItemId,
                integer(raw, "initial"),
                integer(raw, "available"),
                integer(raw, "reserved"),
                integer(raw, "confirmed"),
                longValue(raw, "fence"),
                text(raw, "admission_state"));
    }

    @Override
    public EvidenceState readEvidence(long ticketItemId) {
        Map<Object, Object> raw = redis.opsForHash().entries(stockKey(ticketItemId));
        if (raw.isEmpty()) {
            throw new IllegalStateException("Redis reservation fixture hash is missing");
        }
        return new EvidenceState(
                ticketItemId,
                integer(raw, "initial"),
                integer(raw, "available"),
                integer(raw, "reserved"),
                integer(raw, "confirmed"),
                longValue(raw, "fence"),
                text(raw, "admission_state"));
    }

    private static int integer(Map<Object, Object> fields, String name) {
        try {
            return Integer.parseInt(text(fields, name));
        } catch (NumberFormatException exception) {
            throw new IllegalStateException("Redis reservation fixture field is not an integer: " + name,
                    exception);
        }
    }

    private static long longValue(Map<Object, Object> fields, String name) {
        try {
            return Long.parseLong(text(fields, name));
        } catch (NumberFormatException exception) {
            throw new IllegalStateException("Redis reservation fixture field is not a long: " + name,
                    exception);
        }
    }

    private static String text(Map<Object, Object> fields, String name) {
        Object value = fields.get(name);
        if (value == null) {
            throw new IllegalStateException("Redis reservation fixture field is missing: " + name);
        }
        return value.toString();
    }

    private static void validate(long ticketItemId, int initial, int available, int reserved,
                                 int confirmed, long fenceVersion, String admissionState) {
        if (ticketItemId <= 0 || initial < 0 || available < 0 || reserved < 0 || confirmed < 0
                || available > initial || initial != available + reserved + confirmed
                || fenceVersion < 0
                || !ADMISSION_STATES.contains(admissionState)) {
            throw new IllegalArgumentException("invalid Redis reservation fixture state");
        }
    }

    private static String stockKey(long ticketItemId) {
        return STOCK_KEY_PREFIX + ticketItemId;
    }

}
