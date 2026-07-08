package org.clockworx.data;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.ForkJoinPool;
import java.util.logging.Logger;

import org.clockworx.data.flyway.FlywayMigrator;
import org.clockworx.data.hibernate.HibernateSessionManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Smoke tests for Flyway migrations and Hibernate bootstrap on SQLite.
 * Validates fresh schema creation and baseline adoption of pre-existing tables.
 */
class MigrationSmokeTest {

    private static final Logger LOGGER = Logger.getLogger(MigrationSmokeTest.class.getName());

    /** Minimal entity for Hibernate bootstrap validation. */
    @jakarta.persistence.Entity
    @jakarta.persistence.Table(name = "smoke_test")
    static class SmokeTestEntity {
        @jakarta.persistence.Id
        private Long id;

        @jakarta.persistence.Column(name = "value")
        private String value;
    }

    @Test
    void freshSqlite_createsSchemaAndOpensSession(@TempDir Path tempDir) {
        Path dbFile = tempDir.resolve("fresh.db");
        String url = "jdbc:sqlite:" + dbFile.toAbsolutePath();

        DatabaseSettings settings = DatabaseSettings.withDefaults(
                DatabaseType.SQLITE, url, "", "", "test_");

        assertDoesNotThrow(() -> FlywayMigrator.migrate(
                getClass().getClassLoader(), settings, LOGGER));

        HibernateSessionManager manager = new HibernateSessionManager(
                settings, List.of(SmokeTestEntity.class), ForkJoinPool.commonPool(), LOGGER);

        assertNotNull(manager.getSessionFactory());
        manager.shutdown();
        assertTrue(Files.exists(dbFile));
    }

    @Test
    void baselineOnMigrate_adoptsPreExistingTable(@TempDir Path tempDir) throws Exception {
        Path dbFile = tempDir.resolve("existing.db");
        String url = "jdbc:sqlite:" + dbFile.toAbsolutePath();

        // Simulate a pre-migration database with tables already present
        try (Connection conn = DriverManager.getConnection(url);
             Statement stmt = conn.createStatement()) {
            stmt.execute("""
                CREATE TABLE adopt_banks (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    name VARCHAR(255) NOT NULL UNIQUE,
                    owner_uuid VARCHAR(36) NOT NULL,
                    world_name VARCHAR(255),
                    balance DECIMAL(20, 2) NOT NULL DEFAULT 0,
                    created_at BIGINT NOT NULL,
                    updated_at BIGINT NOT NULL
                )
                """);
            stmt.execute("INSERT INTO adopt_banks (name, owner_uuid, balance, created_at, updated_at) "
                    + "VALUES ('test_bank', '00000000-0000-0000-0000-000000000001', 100.00, 1, 1)");
        }

        DatabaseSettings settings = DatabaseSettings.withDefaults(
                DatabaseType.SQLITE, url, "", "", "adopt_");

        // Flyway should baseline without re-running DDL or losing data
        assertDoesNotThrow(() -> FlywayMigrator.migrate(
                getClass().getClassLoader(),
                settings,
                LOGGER,
                "classpath:db/migration/cotr"));

        try (Connection conn = DriverManager.getConnection(url);
             var ps = conn.prepareStatement("SELECT COUNT(*) FROM adopt_banks");
             var rs = ps.executeQuery()) {
            assertTrue(rs.next());
            assertTrue(rs.getInt(1) >= 1, "Pre-existing row should survive baseline adoption");
        }
    }
}
