package com.xxxx.ddd.integration;

import com.xxxx.ddd.application.MQ.OutboxEvent;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.EntityTransaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@EnabledIfSystemProperty(named = "flashsale.integration", matches = "true")
class FlywayMigrationIntegrationTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("vetautet");

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
        assertJpaOutboxPersistence(MYSQL);
    }

    @Test
    void migratesPreinitializedLegacySchemaWhenBaselineIsExplicitlyEnabled() throws Exception {
        MySQLContainer<?> legacy = new MySQLContainer<>("mysql:8.0")
                .withDatabaseName("vetautet");
        try {
            legacy.start();
            try (Connection connection = DriverManager.getConnection(
                    legacy.getJdbcUrl(), legacy.getUsername(), legacy.getPassword())) {
                executeLegacyInitScripts(connection);
                try (PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO ticket_item "
                                + "(id, name, description, stock_initial, stock_available, is_stock_prepared, "
                                + "price_original, price_flash, sale_start_time, sale_end_time, status, activity_id) "
                                + "VALUES (424242, 'legacy', 'legacy', 10, 7, FALSE, 100, 50, "
                                + "'2025-01-01 00:00:00', '2025-01-02 00:00:00', 1, 1)")) {
                    assertThat(statement.executeUpdate()).isEqualTo(1);
                }
                insertLegacyOutboxRow(connection, "legacy-event-424242");
            }

            Flyway flyway = Flyway.configure()
                    .dataSource(legacy.getJdbcUrl(), legacy.getUsername(), legacy.getPassword())
                    .locations("classpath:db/migration")
                    .baselineOnMigrate(true)
                    .load();
            flyway.migrate();

            try (Connection connection = DriverManager.getConnection(
                    legacy.getJdbcUrl(), legacy.getUsername(), legacy.getPassword());
                 PreparedStatement statement = connection.prepareStatement(
                         "SELECT initial_quantity, available_quantity "
                                 + "FROM inventory_stock_account WHERE ticket_item_id = 424242");
                 ResultSet resultSet = statement.executeQuery()) {
                assertThat(resultSet.next()).isTrue();
                assertThat(resultSet.getInt("initial_quantity")).isEqualTo(10);
                assertThat(resultSet.getInt("available_quantity")).isEqualTo(7);
                assertThat(tableExists(connection, "flyway_schema_history")).isTrue();
                assertThat(latestMigrationVersion(connection)).isEqualTo("3");
                assertOutboxEventIdentity(connection, "legacy-event-424242");
                assertOutboxInsertWorks(connection);
            }

            // A second invocation must be a no-op after baseline + V2 + V3 are recorded.
            flyway.migrate();
        } finally {
            legacy.stop();
        }
    }

    @Test
    void refusesLegacyOversoldStockBeforeCreatingReservationSchema() throws Exception {
        MySQLContainer<?> invalid = new MySQLContainer<>("mysql:8.0")
                .withDatabaseName("vetautet");
        try {
            invalid.start();
            try (Connection connection = DriverManager.getConnection(
                    invalid.getJdbcUrl(), invalid.getUsername(), invalid.getPassword())) {
                ScriptUtils.executeSqlScript(
                        connection,
                        new EncodedResource(new ClassPathResource("db/migration/V1__legacy_schema.sql")));
                try (PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO ticket_item "
                                + "(id, name, description, stock_initial, stock_available, is_stock_prepared, "
                                + "price_original, price_flash, sale_start_time, sale_end_time, status, activity_id) "
                                + "VALUES (424243, 'oversold', 'oversold', 2, 3, FALSE, 100, 50, "
                                + "'2025-01-01 00:00:00', '2025-01-02 00:00:00', 1, 1)")) {
                    assertThat(statement.executeUpdate()).isEqualTo(1);
                }
            }

            assertThatThrownBy(() -> Flyway.configure()
                    .dataSource(invalid.getJdbcUrl(), invalid.getUsername(), invalid.getPassword())
                    .locations("classpath:db/migration")
                    .baselineOnMigrate(true)
                    .load()
                    .migrate())
                    .isInstanceOf(FlywayException.class);

            try (Connection connection = DriverManager.getConnection(
                    invalid.getJdbcUrl(), invalid.getUsername(), invalid.getPassword())) {
                assertThat(tableExists(connection, "inventory_stock_account")).isFalse();
            }
        } finally {
            invalid.stop();
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

    private static void assertOutboxEventIdentity(Connection connection, String id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT event_id FROM outbox_event WHERE id = ?")) {
            statement.setString(1, id);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertThat(resultSet.next()).isTrue();
                assertThat(resultSet.getString("event_id")).isEqualTo(id);
            }
        }
    }

    private static void assertJpaOutboxPersistence(MySQLContainer<?> mysql) {
        LocalContainerEntityManagerFactoryBean factory = new LocalContainerEntityManagerFactoryBean();
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
        factory.setDataSource(dataSource);
        factory.setPackagesToScan("com.xxxx.ddd.application.MQ");
        factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        Properties properties = new Properties();
        properties.setProperty("hibernate.hbm2ddl.auto", "none");
        factory.setJpaProperties(properties);
        factory.afterPropertiesSet();

        EntityManagerFactory entityManagerFactory = factory.getObject();
        assertThat(entityManagerFactory).isNotNull();
        EntityManager entityManager = entityManagerFactory.createEntityManager();
        String id = "jpa-event-424242";
        try {
            EntityTransaction transaction = entityManager.getTransaction();
            transaction.begin();
            entityManager.persist(new OutboxEvent(
                    id,
                    "Order",
                    "order-jpa-1",
                    "ORDER_CREATED",
                    OutboxEvent.DEFAULT_EVENT_VERSION,
                    "{}"));
            transaction.commit();

            entityManager.clear();
            OutboxEvent persisted = entityManager.find(OutboxEvent.class, id);
            assertThat(persisted).isNotNull();
            assertThat(persisted.getEventId()).isEqualTo(id);
        } finally {
            entityManager.close();
            entityManagerFactory.close();
        }
    }

    private static void insertLegacyOutboxRow(Connection connection, String id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO outbox_event "
                        + "(id, aggregate_type, aggregate_id, event_type, event_version, payload, status) "
                        + "VALUES (?, 'Order', 'legacy-order-1', 'ORDER_CREATED', 1, '{}', 'PENDING')")) {
            statement.setString(1, id);
            assertThat(statement.executeUpdate()).isEqualTo(1);
        }
    }

    private static void executeLegacyInitScripts(Connection connection) throws SQLException {
        ScriptUtils.executeSqlScript(
                connection,
                new EncodedResource(repositoryResource("environment/mysql/init/ticket_init.sql"),
                        StandardCharsets.UTF_8));
        ScriptUtils.executeSqlScript(
                connection,
                new EncodedResource(repositoryResource("environment/mysql/init/outbox_init.sql"),
                        StandardCharsets.UTF_8));
    }

    private static FileSystemResource repositoryResource(String relativePath) {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (current != null) {
            Path candidate = current.resolve(relativePath);
            if (Files.isRegularFile(candidate)) {
                return new FileSystemResource(candidate);
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Cannot locate repository file: " + relativePath);
    }

    private static String latestMigrationVersion(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT version FROM flyway_schema_history "
                        + "WHERE success = TRUE ORDER BY installed_rank DESC LIMIT 1");
             ResultSet resultSet = statement.executeQuery()) {
            assertThat(resultSet.next()).isTrue();
            return resultSet.getString("version");
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
