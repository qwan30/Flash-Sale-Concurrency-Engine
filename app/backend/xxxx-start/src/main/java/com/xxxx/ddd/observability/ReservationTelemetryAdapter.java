package com.xxxx.ddd.observability;

import com.xxxx.ddd.application.reservation.port.ReservationTelemetryPort;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.TimeGauge;
import io.micrometer.core.instrument.Timer;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public final class ReservationTelemetryAdapter implements ReservationTelemetryPort {

    private static final Set<String> OPERATIONS = Set.of("create", "confirm", "release", "expire", "recover", "outbox.publish");
    private static final Set<String> OUTCOMES = Set.of(
            "NEW", "REPLAYED", "PROCESSING", "SOLD_OUT", "FENCE_STALE", "REJECTED", "CONFLICT", "EXCEPTION",
            "CONFIRMED", "RELEASED", "EXPIRED", "LATE_CONFLICT", "NOT_FOUND", "MIRROR_PENDING",
            "REPAIR_REQUIRED", "RECOVERED", "PUBLISHED", "IDLE", "FAILED", "PARTIAL", "RECEIVED",
            "REDIS_APPLYING", "REDIS_APPLIED", "COMMITTED", "COMPENSATED", "COMPENSATION_PENDING");
    private static final Set<String> REASONS = Set.of(
            "NEW", "SOLD_OUT", "FENCE_STALE", "CONFLICT", "REPAIR_REQUIRED", "COMPENSATION_PENDING",
            "MIRROR_PENDING", "DATABASE_FAILURE", "UNHANDLED", "CONFIRMED", "RELEASED", "EXPIRED",
            "REPLAYED", "PROCESSING", "LATE_CONFLICT", "NOT_FOUND", "RECOVERED", "PUBLISHED", "IDLE",
            "FAILED", "PARTIAL", "RECEIVED", "REDIS_APPLYING", "REDIS_APPLIED", "COMMITTED", "COMPENSATED", "REJECTED",
            "MAX_ATTEMPTS_EXCEEDED");

    private final MeterRegistry registry;
    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;
    private final Counter gaugeReadFailureCounter;

    public ReservationTelemetryAdapter(MeterRegistry registry) {
        this(registry, null, null);
    }

    public ReservationTelemetryAdapter(
            MeterRegistry registry,
            JdbcTemplate jdbc,
            StringRedisTemplate redis
    ) {
        this.registry = registry;
        this.jdbc = jdbc;
        this.redis = redis;
        this.gaugeReadFailureCounter = Counter.builder("flashsale.telemetry.read.failure")
                .register(registry);
        if (jdbc != null) {
            registerGauges();
        }
    }

    @Override
    public void record(String operation, String outcome, String reason, Duration duration) {
        String boundedOperation = bounded(operation, OPERATIONS);
        String boundedOutcome = bounded(outcome, OUTCOMES);
        String boundedReason = bounded(reason, REASONS);
        Timer.builder("flashsale.reservation.operation")
                .tag("operation", boundedOperation)
                .tag("outcome", boundedOutcome)
                .register(registry)
                .record(duration.isNegative() ? Duration.ZERO : duration);
        Counter.builder("flashsale.reservation.transitions")
                .tag("operation", boundedOperation)
                .tag("outcome", boundedOutcome)
                .tag("reason", boundedReason)
                .register(registry)
                .increment();
    }

    private static String bounded(String value, Set<String> allowed) {
        return value != null && allowed.contains(value) ? value : "OTHER";
    }

    private void registerGauges() {
        for (String status : List.of("available", "reserved", "confirmed")) {
            io.micrometer.core.instrument.Gauge.builder(
                            "flashsale.reservation.units",
                            this,
                            adapter -> adapter.readUnits(status))
                    .tag("status", status)
                    .register(registry);
        }
        for (String state : List.of(
                "RECEIVED", "REDIS_APPLYING", "REDIS_APPLIED", "COMPENSATION_PENDING", "MIRROR_PENDING",
                "REPAIR_REQUIRED", "COMMITTED", "COMPENSATED", "REJECTED")) {
            io.micrometer.core.instrument.Gauge.builder(
                            "flashsale.recovery.operations",
                            this,
                            adapter -> adapter.readRecoveryOperations(state))
                    .tag("state", state)
                    .register(registry);
        }
        TimeGauge.builder(
                        "flashsale.outbox.oldest.age",
                        this,
                        TimeUnit.SECONDS,
                        ReservationTelemetryAdapter::readOldestOutboxAge)
                .register(registry);
        io.micrometer.core.instrument.Gauge.builder(
                        "flashsale.redis.mirror.pending",
                        this,
                        ReservationTelemetryAdapter::readMirrorPending)
                .register(registry);
        io.micrometer.core.instrument.Gauge.builder(
                        "flashsale.inventory.drift.units",
                        this,
                        ReservationTelemetryAdapter::readInventoryDrift)
                .register(registry);
    }

    private double readUnits(String status) {
        try {
            return switch (status) {
                case "available" -> scalar("SELECT COALESCE(SUM(available_quantity), 0) FROM inventory_stock_account");
                case "reserved" -> scalar("SELECT COALESCE(SUM(quantity), 0) FROM inventory_reservation WHERE status = 'RESERVED'");
                case "confirmed" -> scalar("SELECT COALESCE(SUM(quantity), 0) FROM inventory_reservation WHERE status = 'CONFIRMED'");
                default -> 0;
            };
        } catch (RuntimeException ignored) {
            return unavailableGauge();
        }
    }

    private double readRecoveryOperations(String state) {
        try {
            return scalar("SELECT COUNT(*) FROM inventory_operation_journal WHERE state = ?", state);
        } catch (RuntimeException ignored) {
            return unavailableGauge();
        }
    }

    private double readOldestOutboxAge() {
        try {
            Number age = jdbc.queryForObject(
                    "SELECT COALESCE(TIMESTAMPDIFF(MICROSECOND, MIN(created_at), UTC_TIMESTAMP(6)) / 1000000, 0) "
                            + "FROM outbox_event WHERE status IN ('PENDING', 'FAILED')",
                    Number.class);
            return age == null ? 0 : age.doubleValue();
        } catch (RuntimeException ignored) {
            return unavailableGauge();
        }
    }

    private double readMirrorPending() {
        try {
            return scalar("SELECT COUNT(*) FROM inventory_operation_journal WHERE state = 'MIRROR_PENDING'");
        } catch (RuntimeException ignored) {
            return unavailableGauge();
        }
    }

    private double readInventoryDrift() {
        if (redis == null) {
            return unavailableGauge();
        }
        try {
            List<StockRow> rows = jdbc.query(
                    "SELECT ticket_item_id, available_quantity FROM inventory_stock_account",
                    (resultSet, rowNum) -> new StockRow(
                            resultSet.getLong("ticket_item_id"),
                            resultSet.getInt("available_quantity")));
            double drift = 0;
            for (StockRow row : rows) {
                Object redisValue = redis.opsForHash().get(
                        "flashsale:reservation:stock:" + row.ticketItemId(), "available");
                if (redisValue == null) {
                    return unavailableGauge();
                }
                try {
                    drift += Math.abs(row.available() - Integer.parseInt(redisValue.toString()));
                } catch (NumberFormatException ignored) {
                    return unavailableGauge();
                }
            }
            return drift;
        } catch (RuntimeException ignored) {
            return unavailableGauge();
        }
    }

    private double unavailableGauge() {
        gaugeReadFailureCounter.increment();
        return Double.NaN;
    }

    private double scalar(String sql, Object... arguments) {
        Number value = jdbc.queryForObject(sql, Number.class, arguments);
        return value == null ? 0 : value.doubleValue();
    }

    private record StockRow(long ticketItemId, int available) {
    }
}
