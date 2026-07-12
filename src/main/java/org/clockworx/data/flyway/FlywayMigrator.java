package org.clockworx.data.flyway;

import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.clockworx.data.DatabaseSettings;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.configuration.FluentConfiguration;

/**
 * Runs Flyway schema migrations for a Clockworx plugin.
 * <p>
 * Encapsulates the migration bootstrap previously duplicated across the
 * Vampire, Werewolf, and Bookcase plugins:
 * <ul>
 *   <li>swaps the thread context classloader so Flyway can discover JDBC
 *       drivers and migration resources inside the plugin JAR</li>
 *   <li>loads the JDBC driver for the configured backend explicitly</li>
     *   <li>configures {@code baselineOnMigrate(true)} with baseline version 1 so
     *       pre-existing databases (already at the V1 schema) are adopted without
     *       re-running DDL; empty databases still receive the V1 migration</li>
 *   <li>exposes the table prefix to SQL scripts as the {@code ${tablePrefix}}
 *       placeholder</li>
 *   <li>stores history in {@code {prefix}flyway_schema_history}</li>
 * </ul>
 * Migration SQL remains plugin-specific, under {@code db/migration} in each
 * plugin's resources.
 */
public final class FlywayMigrator {

    /** Default classpath location of migration scripts within a plugin JAR. */
    public static final String DEFAULT_LOCATION = "classpath:db/migration";

    private FlywayMigrator() {
    }

    /**
     * Runs migrations from the default location {@link #DEFAULT_LOCATION}.
     *
     * @param classLoader the plugin's classloader (for driver and resource discovery)
     * @param settings    the database connection settings
     * @param logger      the plugin logger
     * @throws FlywayException if migration fails
     */
    public static void migrate(ClassLoader classLoader, DatabaseSettings settings, Logger logger) {
        migrate(classLoader, settings, logger, DEFAULT_LOCATION);
    }

    /**
     * Runs migrations from one or more custom classpath locations.
     *
     * @param classLoader the plugin's classloader (for driver and resource discovery)
     * @param settings    the database connection settings
     * @param logger      the plugin logger
     * @param locations   classpath locations of migration scripts (e.g. "classpath:db/migration/common")
     * @throws FlywayException if migration fails
     */
    public static void migrate(ClassLoader classLoader, DatabaseSettings settings, Logger logger,
                               String... locations) {
        logger.info("Starting database migration check...");
        ClassLoader originalClassLoader = Thread.currentThread().getContextClassLoader();
        try {
            // Set context class loader for Flyway to find drivers/resources
            Thread.currentThread().setContextClassLoader(classLoader);

            // Load the JDBC driver explicitly through the plugin classloader
            try {
                Class.forName(settings.type().getDriverClassName(), true, classLoader);
            } catch (ClassNotFoundException e) {
                throw new FlywayException(
                        "Could not find JDBC driver for database type: " + settings.type(), e);
            }

            FluentConfiguration flywayConfig = Flyway.configure(classLoader)
                    .dataSource(settings.url(), settings.user(), settings.password())
                    .locations(locations)
                    .encoding("UTF-8")
                    .baselineOnMigrate(true)
                    // Baseline at v0 (not v1): when a plugin first migrates against a schema that is
                    // non-empty (shared with other plugins) but has no history table of its own,
                    // Flyway inserts a baseline marker and then applies migrations with version >
                    // baselineVersion. With baselineVersion=1 the V1 script (version 1 <= 1) was
                    // treated as already-applied and SKIPPED, so the plugin's tables were never
                    // created (observed with villages on the shared `minecraft` schema). v0 lets V1+
                    // run; migrations use CREATE TABLE IF NOT EXISTS so re-running is safe.
                    .baselineVersion("0")
                    .placeholders(Map.of("tablePrefix", settings.tablePrefix()))
                    .table(settings.historyTableName());

            logger.info("Using Flyway history table: " + settings.historyTableName());

            flywayConfig.load().migrate();
            logger.info("Database migration check completed successfully.");
        } catch (FlywayException e) {
            logger.log(Level.SEVERE, "Database migration failed!", e);
            if (e.getCause() != null) {
                logger.log(Level.SEVERE, "Cause: " + e.getCause().getMessage(), e.getCause());
            }
            throw e;
        } finally {
            // Restore original class loader
            Thread.currentThread().setContextClassLoader(originalClassLoader);
        }
    }
}
