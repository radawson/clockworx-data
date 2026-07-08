package org.clockworx.data;

/**
 * Immutable connection settings for the Clockworx data layer.
 * Built by each plugin from its own configuration file and passed to
 * {@link org.clockworx.data.flyway.FlywayMigrator} and
 * {@link org.clockworx.data.hibernate.HibernateSessionManager}.
 *
 * @param type                the database backend
 * @param url                 the full JDBC URL (e.g. jdbc:sqlite:/path/to/db, jdbc:mysql://host:3306/db)
 * @param user                the database user (may be empty for SQLite)
 * @param password            the database password (may be empty for SQLite)
 * @param tablePrefix         prefix applied to all table names, may be empty (e.g. "vampire_")
 * @param maxPoolSize         HikariCP maximum pool size (ignored for SQLite)
 * @param minIdle             HikariCP minimum idle connections (ignored for SQLite)
 * @param idleTimeoutMs       HikariCP idle timeout in milliseconds (ignored for SQLite)
 * @param connectionTimeoutMs HikariCP connection timeout in milliseconds (ignored for SQLite)
 * @param showSql             whether Hibernate should log generated SQL
 * @param hbm2ddl             Hibernate schema handling mode; "none" or "validate" (Flyway owns DDL)
 */
public record DatabaseSettings(
        DatabaseType type,
        String url,
        String user,
        String password,
        String tablePrefix,
        int maxPoolSize,
        int minIdle,
        long idleTimeoutMs,
        long connectionTimeoutMs,
        boolean showSql,
        String hbm2ddl) {

    public DatabaseSettings {
        if (type == null) {
            throw new IllegalArgumentException("Database type must not be null");
        }
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("Database URL must not be empty");
        }
        if (user == null) {
            user = "";
        }
        if (password == null) {
            password = "";
        }
        if (tablePrefix == null) {
            tablePrefix = "";
        }
        if (hbm2ddl == null || hbm2ddl.isBlank()) {
            hbm2ddl = "none";
        }
    }

    /**
     * Creates settings with the pool defaults used historically by the Clockworx
     * plugins (pool size 10, min idle 5, idle timeout 300000 ms, connection
     * timeout 10000 ms), SQL logging off, and hbm2ddl "none".
     *
     * @param type        the database backend
     * @param url         the full JDBC URL
     * @param user        the database user
     * @param password    the database password
     * @param tablePrefix prefix applied to all table names, may be empty
     * @return settings with default pool sizing
     */
    public static DatabaseSettings withDefaults(DatabaseType type, String url, String user,
                                                String password, String tablePrefix) {
        return new DatabaseSettings(type, url, user, password, tablePrefix,
                10, 5, 300_000L, 10_000L, false, "none");
    }

    /**
     * @param mode the hbm2ddl mode ("none" or "validate")
     * @return a copy of these settings with the given hbm2ddl mode
     */
    public DatabaseSettings withHbm2ddl(String mode) {
        return new DatabaseSettings(type, url, user, password, tablePrefix,
                maxPoolSize, minIdle, idleTimeoutMs, connectionTimeoutMs, showSql, mode);
    }

    /**
     * @return the Flyway schema history table name, respecting the table prefix
     */
    public String historyTableName() {
        return tablePrefix.isEmpty() ? "flyway_schema_history" : tablePrefix + "flyway_schema_history";
    }
}
