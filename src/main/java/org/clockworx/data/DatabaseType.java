package org.clockworx.data;

/**
 * Supported database backends for the Clockworx data layer.
 * Each type knows its JDBC driver class so bootstrap code (Hibernate, Flyway)
 * can load the driver explicitly through the plugin's classloader.
 */
public enum DatabaseType {
    SQLITE("org.sqlite.JDBC"),
    MYSQL("com.mysql.cj.jdbc.Driver"),
    POSTGRES("org.postgresql.Driver");

    private final String driverClassName;

    DatabaseType(String driverClassName) {
        this.driverClassName = driverClassName;
    }

    /**
     * @return the fully qualified JDBC driver class name for this backend
     */
    public String getDriverClassName() {
        return driverClassName;
    }

    /**
     * Parses a configuration string (e.g. from config.yml) into a DatabaseType.
     * Accepts the aliases historically used across Clockworx plugins:
     * "sqlite", "mysql", "postgres", "postgresql" (case-insensitive).
     *
     * @param value the configured database type string
     * @return the matching DatabaseType
     * @throws IllegalArgumentException if the value is null or not a supported type
     */
    public static DatabaseType fromString(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Database type is not configured");
        }
        return switch (value.trim().toLowerCase()) {
            case "sqlite" -> SQLITE;
            case "mysql" -> MYSQL;
            case "postgres", "postgresql" -> POSTGRES;
            default -> throw new IllegalArgumentException("Unsupported database type: " + value);
        };
    }
}
