package com.xxxx.ddd.integration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class FlywayMigrationIntegrationTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0");

    @Test
    void migratesFreshDatabaseToReservationSchema() throws Exception {
        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();

        try (Connection connection = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())) {
            assertThat(tableExists(connection, "inventory_stock_account")).isTrue();
            assertThat(tableExists(connection, "inventory_reservation")).isTrue();
            assertThat(tableExists(connection, "inventory_operation_journal")).isTrue();
            assertThat(tableExists(connection, "inventory_repair_journal")).isTrue();
            assertThat(tableExists(connection, "reservation_order")).isTrue();
            assertThat(columnExists(connection, "outbox_event", "event_id")).isTrue();
            assertThat(columnExists(connection, "outbox_event", "lease_owner")).isTrue();
            assertThat(columnExists(connection, "outbox_event", "lease_until")).isTrue();
            assertThat(generatedExpression(connection, "outbox_event", "event_id"))
                    .contains("id");
            assertOutboxInsertWorks(connection);
        }
    }

    @Test
    void migratesPreinitializedLegacySchemaWhenBaselineIsExplicitlyEnabled() throws Exception {
        MySQLContainer<?> legacy = new MySQLContainer<>("mysql:8.0");
        try {
            legacy.start();
            try (Connection connection = DriverManager.getConnection(
                    legacy.getJdbcUrl(), legacy.getUsername(), legacy.getPassword())) {
                ScriptUtils.executeSqlScript(
                        connection,
                        new EncodedResource(new ClassPathResource("db/migration/V1__legacy_schema.sql")));
                try (PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO ticket_item "
                                + "(id, name, description, stock_initial, stock_available, is_stock_prepared, "
                                + "price_original, price_flash, sale_start_time, sale_end_time, status, activity_id) "
                                + "VALUES (42, 'legacy', 'legacy', 10, 7, FALSE, 100, 50, "
                                + "'2025-01-01 00:00:00', '2025-01-02 00:00:00', 1, 1)")) {
                    assertThat(statement.executeUpdate()).isEqualTo(1);
                }
            }

            Flyway.configure()
                    .dataSource(legacy.getJdbcUrl(), legacy.getUsername(), legacy.getPassword())
                    .locations("classpath:db/migration")
                    .baselineOnMigrate(true)
                    .load()
                    .migrate();

            try (Connection connection = DriverManager.getConnection(
                    legacy.getJdbcUrl(), legacy.getUsername(), legacy.getPassword());
                 PreparedStatement statement = connection.prepareStatement(
                         "SELECT initial_quantity, available_quantity "
                                 + "FROM inventory_stock_account WHERE ticket_item_id = 42");
                 ResultSet resultSet = statement.executeQuery()) {
                assertThat(resultSet.next()).isTrue();
                assertThat(resultSet.getInt("initial_quantity")).isEqualTo(10);
                assertThat(resultSet.getInt("available_quantity")).isEqualTo(7);
                assertOutboxInsertWorks(connection);
            }
        } finally {
            legacy.stop();
        }
    }

    private static void assertOutboxInsertWorks(Connection connection) throws SQLException {
        String id = "4dcf3d1d-b39c-4d5d-9ff9-12e19908a5fb";
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO outbox_event "
                        + "(id, aggregate_type, aggregate_id, event_type, event_version, payload, status) "
                        + "VALUES (?, 'Order', 'order-1', 'ORDER_CREATED', 1, '{}', 'PENDING')")) {
            statement.setString(1, id);
            assertThat(statement.executeUpdate()).isEqualTo(1);
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT event_id FROM outbox_event WHERE id = ?")) {
            statement.setString(1, id);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertThat(resultSet.next()).isTrue();
                assertThat(resultSet.getString("event_id")).isEqualTo(id);
            }
        }
    }

    private static boolean tableExists(Connection connection, String tableName) throws SQLException {
        try (ResultSet tables = connection.getMetaData().getTables(
                connection.getCatalog(), null, tableName, new String[]{"TABLE"})) {
            return tables.next();
        }
    }

    private static boolean columnExists(Connection connection, String tableName, String columnName)
            throws SQLException {
        try (ResultSet columns = connection.getMetaData().getColumns(
                connection.getCatalog(), null, tableName, columnName)) {
            return columns.next();
        }
    }

    private static String generatedExpression(Connection connection, String tableName, String columnName)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT generation_expression FROM information_schema.columns "
                        + "WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?")) {
            statement.setString(1, tableName);
            statement.setString(2, columnName);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertThat(resultSet.next()).isTrue();
                return resultSet.getString("generation_expression");
            }
        }
    }
}
