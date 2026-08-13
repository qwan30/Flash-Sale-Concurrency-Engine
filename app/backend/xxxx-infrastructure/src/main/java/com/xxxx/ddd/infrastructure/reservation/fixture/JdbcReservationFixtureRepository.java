package com.xxxx.ddd.infrastructure.reservation.fixture;

import com.xxxx.ddd.application.reservation.port.ReservationFixtureRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * MySQL adapter for the local reservation workload fixture.
 *
 * <p>This is deliberately separate from buyer-facing repositories: the operation
 * removes only reservation-lab state for one ticket item and records no domain
 * event of its own.
 */
@Repository
public class JdbcReservationFixtureRepository implements ReservationFixtureRepository {

    private final JdbcTemplate jdbc;

    public JdbcReservationFixtureRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public DurableState reset(long ticketItemId, int stock) {
        if (ticketItemId <= 0 || stock < 0) {
            throw new IllegalArgumentException("invalid reservation fixture input");
        }

        jdbc.update("DELETE FROM reservation_order WHERE ticket_item_id = ?", ticketItemId);
        jdbc.update("DELETE FROM outbox_event "
                        + "WHERE aggregate_type = 'Reservation' "
                        + "AND (aggregate_id IN "
                        + "(SELECT BIN_TO_UUID(id) FROM inventory_reservation WHERE ticket_item_id = ?) "
                        + "OR payload REGEXP CONCAT('\"ticketItemId\"[[:space:]]*:[[:space:]]*', ?, "
                        + "'([^0-9]|$)'))",
                ticketItemId, ticketItemId);
        jdbc.update("DELETE FROM inventory_operation_journal WHERE ticket_item_id = ?", ticketItemId);
        jdbc.update("DELETE FROM inventory_repair_journal WHERE ticket_item_id = ?", ticketItemId);
        jdbc.update("DELETE FROM inventory_reservation WHERE ticket_item_id = ?", ticketItemId);
        jdbc.update("DELETE FROM inventory_stock_account WHERE ticket_item_id = ?", ticketItemId);

        jdbc.update("INSERT INTO ticket_item "
                        + "(id, name, description, stock_initial, stock_available, is_stock_prepared, "
                        + "price_original, price_flash, sale_start_time, sale_end_time, status, activity_id) "
                        + "VALUES (?, 'reservation-fixture', 'reservation-fixture', ?, ?, FALSE, 100, 50, "
                        + "'2025-01-01 00:00:00', '2030-01-01 00:00:00', 1, 1) "
                        + "ON DUPLICATE KEY UPDATE "
                        + "name = VALUES(name), description = VALUES(description), "
                        + "stock_initial = VALUES(stock_initial), stock_available = VALUES(stock_available), "
                        + "is_stock_prepared = VALUES(is_stock_prepared), price_original = VALUES(price_original), "
                        + "price_flash = VALUES(price_flash), sale_start_time = VALUES(sale_start_time), "
                        + "sale_end_time = VALUES(sale_end_time), status = VALUES(status), "
                        + "activity_id = VALUES(activity_id)",
                ticketItemId, stock, stock);
        jdbc.update("INSERT INTO inventory_stock_account "
                        + "(ticket_item_id, initial_quantity, available_quantity, admission_state, "
                        + "fence_version, version) VALUES (?, ?, ?, 'OPEN', 0, 1)",
                ticketItemId, stock, stock);

        return new DurableState(ticketItemId, stock, stock, 0, 0, 0, "OPEN");
    }

    @Override
    public EvidenceState evidence(long ticketItemId) {
        if (ticketItemId <= 0) {
            throw new IllegalArgumentException("ticketItemId must be positive");
        }
        Map<String, Object> account = jdbc.queryForMap(
                "SELECT initial_quantity, available_quantity, fence_version, admission_state "
                        + "FROM inventory_stock_account WHERE ticket_item_id = ?",
                ticketItemId);
        Map<String, Object> reservations = jdbc.queryForMap(
                "SELECT COALESCE(SUM(CASE WHEN status = 'RESERVED' THEN quantity ELSE 0 END), 0) AS reserved, "
                        + "COALESCE(SUM(CASE WHEN status = 'CONFIRMED' THEN quantity ELSE 0 END), 0) AS confirmed "
                        + "FROM inventory_reservation WHERE ticket_item_id = ?",
                ticketItemId);
        Number pendingJournal = jdbc.queryForObject(
                "SELECT COUNT(*) FROM inventory_operation_journal "
                        + "WHERE ticket_item_id = ? AND state IN "
                        + "('RECEIVED', 'REDIS_APPLYING', 'REDIS_APPLIED', 'COMPENSATION_PENDING', 'MIRROR_PENDING', 'REPAIR_REQUIRED')",
                Number.class,
                ticketItemId);
        Number pendingOutbox = jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_event "
                        + "WHERE aggregate_type = 'Reservation' AND status IN ('PENDING', 'FAILED') "
                        + "AND (aggregate_id IN "
                        + "(SELECT BIN_TO_UUID(id) FROM inventory_reservation WHERE ticket_item_id = ?) "
                        + "OR payload REGEXP CONCAT('\"ticketItemId\"[[:space:]]*:[[:space:]]*', ?, "
                        + "'([^0-9]|$)'))",
                Number.class,
                ticketItemId,
                ticketItemId);
        Number oldestOutboxAge = jdbc.queryForObject(
                "SELECT COALESCE(TIMESTAMPDIFF(MICROSECOND, MIN(created_at), UTC_TIMESTAMP(6)) / 1000000, 0) "
                        + "FROM outbox_event "
                        + "WHERE aggregate_type = 'Reservation' AND status IN ('PENDING', 'FAILED') "
                        + "AND (aggregate_id IN "
                        + "(SELECT BIN_TO_UUID(id) FROM inventory_reservation WHERE ticket_item_id = ?) "
                        + "OR payload REGEXP CONCAT('\"ticketItemId\"[[:space:]]*:[[:space:]]*', ?, "
                        + "'([^0-9]|$)'))",
                Number.class,
                ticketItemId,
                ticketItemId);
        Number duplicateReservations = jdbc.queryForObject(
                "SELECT COALESCE(SUM(duplicate_count), 0) FROM ("
                        + "SELECT COUNT(*) - 1 AS duplicate_count FROM inventory_reservation "
                        + "WHERE ticket_item_id = ? GROUP BY demo_actor_id, idempotency_key_hash "
                        + "HAVING COUNT(*) > 1) AS duplicates",
                Number.class,
                ticketItemId);
        Number duplicateOrders = jdbc.queryForObject(
                "SELECT COALESCE(SUM(duplicate_count), 0) FROM ("
                        + "SELECT COUNT(*) - 1 AS duplicate_count FROM reservation_order "
                        + "WHERE ticket_item_id = ? GROUP BY reservation_id HAVING COUNT(*) > 1) AS duplicates",
                Number.class,
                ticketItemId);
        return new EvidenceState(
                ticketItemId,
                number(account.get("initial_quantity")).intValue(),
                number(account.get("available_quantity")).intValue(),
                number(reservations.get("reserved")).intValue(),
                number(reservations.get("confirmed")).intValue(),
                number(account.get("fence_version")).longValue(),
                String.valueOf(account.get("admission_state")),
                number(pendingJournal).longValue(),
                number(pendingOutbox).longValue(),
                number(oldestOutboxAge).doubleValue(),
                number(duplicateReservations).longValue(),
                number(duplicateOrders).longValue());
    }

    private static Number number(Object value) {
        if (value instanceof Number number) {
            return number;
        }
        throw new IllegalStateException("reservation fixture evidence value is not numeric");
    }
}
