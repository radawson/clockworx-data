package org.clockworx.data.hibernate;

import java.util.List;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.clockworx.data.DatabaseSettings;
import org.clockworx.data.DatabaseType;
import org.hibernate.SessionFactory;
import org.hibernate.boot.registry.BootstrapServiceRegistry;
import org.hibernate.boot.registry.BootstrapServiceRegistryBuilder;
import org.hibernate.cfg.Configuration;
import org.hibernate.cfg.Environment;

/**
 * Builds a Hibernate {@link SessionFactory} from {@link DatabaseSettings} and a
 * list of annotated entity classes.
 * <p>
 * Replaces the per-plugin {@code HibernateConfig} classes previously duplicated
 * across the Vampire, Werewolf, and Bookcase plugins. Backend behavior:
 * <ul>
 *   <li><b>SQLite</b> — community SQLite dialect, single connection (pool size 1),
 *       no HikariCP (embedded database)</li>
 *   <li><b>MySQL / PostgreSQL</b> — HikariCP connection provider with pool sizing
 *       from the settings</li>
 * </ul>
 * Schema DDL is owned by Flyway; hbm2ddl is "none" or "validate" per the settings.
 */
public final class HibernateBootstrap {

    private static final String HIKARI_CONNECTION_PROVIDER =
            "org.hibernate.hikaricp.internal.HikariCPConnectionProvider";

    private HibernateBootstrap() {
    }

    /**
     * Builds a new SessionFactory. The caller owns the returned factory and is
     * responsible for closing it (see {@link HibernateSessionManager#shutdown()}).
     *
     * @param settings      the database connection settings
     * @param entityClasses the annotated JPA entity classes to register
     * @param logger        the plugin logger
     * @return a newly built SessionFactory
     * @throws IllegalStateException if the factory cannot be built
     */
    public static SessionFactory buildSessionFactory(DatabaseSettings settings,
                                                     List<Class<?>> entityClasses,
                                                     Logger logger) {
        try {
            logger.log(Level.INFO, "Initializing Hibernate SessionFactory...");

            // The DB stack may be loaded via Paper's library-loader, which places Hibernate in a
            // *separate* classloader from the plugin's entity classes. Hibernate resolves entity
            // class names through its own ClassLoaderService, which by default cannot see the
            // plugin classloader -> "entity class not found" during binding. Register the loader
            // that actually owns the entities so name-based resolution succeeds. (Harmless under
            // the legacy shaded model, where the entities share Hibernate's classloader anyway.)
            ClassLoader entityLoader = entityClasses.isEmpty()
                    ? Thread.currentThread().getContextClassLoader()
                    : entityClasses.get(0).getClassLoader();
            BootstrapServiceRegistry bootstrapRegistry = new BootstrapServiceRegistryBuilder()
                    .applyClassLoader(entityLoader)
                    .build();

            Configuration configuration = new Configuration(bootstrapRegistry);
            Properties properties = new Properties();

            // Common settings
            properties.put(Environment.SHOW_SQL, String.valueOf(settings.showSql()));
            properties.put(Environment.HBM2DDL_AUTO, settings.hbm2ddl());
            properties.put(Environment.CURRENT_SESSION_CONTEXT_CLASS, "thread");

            // Apply table prefix
            properties.put(Environment.PHYSICAL_NAMING_STRATEGY,
                    new PrefixPhysicalNamingStrategy(settings.tablePrefix()));
            logger.log(Level.INFO, "Applying Hibernate table prefix: '" + settings.tablePrefix() + "'");

            DatabaseType type = settings.type();
            switch (type) {
                case SQLITE -> {
                    logger.log(Level.INFO, "Configuring Hibernate for SQLite...");
                    properties.put(Environment.DIALECT, "org.hibernate.community.dialect.SQLiteDialect");
                    properties.put(Environment.JAKARTA_JDBC_URL, settings.url());
                    properties.put(Environment.JAKARTA_JDBC_DRIVER, type.getDriverClassName());
                    // Embedded database: a single connection avoids SQLITE_BUSY contention
                    properties.put(Environment.POOL_SIZE, "1");
                    properties.put(Environment.AUTOCOMMIT, "true");
                }
                case MYSQL -> {
                    logger.log(Level.INFO, "Configuring Hibernate for MySQL...");
                    // Dialect is auto-detected from the JDBC URL
                    applyHikariProperties(properties, settings);
                }
                case POSTGRES -> {
                    logger.log(Level.INFO, "Configuring Hibernate for PostgreSQL...");
                    properties.put(Environment.DIALECT, "org.hibernate.dialect.PostgreSQLDialect");
                    applyHikariProperties(properties, settings);
                }
            }

            configuration.setProperties(properties);

            for (Class<?> entityClass : entityClasses) {
                configuration.addAnnotatedClass(entityClass);
            }

            SessionFactory sessionFactory = configuration.buildSessionFactory();
            logger.log(Level.INFO, "Hibernate SessionFactory built successfully.");
            return sessionFactory;
        } catch (Throwable ex) {
            logger.log(Level.SEVERE, "Failed to initialize Hibernate SessionFactory: " + ex.getMessage(), ex);
            throw new IllegalStateException("Hibernate initialization failed", ex);
        }
    }

    /**
     * Applies the HikariCP connection provider and pool properties shared by the
     * server-based backends (MySQL, PostgreSQL).
     */
    private static void applyHikariProperties(Properties properties, DatabaseSettings settings) {
        properties.put(Environment.CONNECTION_PROVIDER, HIKARI_CONNECTION_PROVIDER);
        properties.put("hibernate.hikari.jdbcUrl", settings.url());
        properties.put("hibernate.hikari.username", settings.user());
        properties.put("hibernate.hikari.password", settings.password());
        properties.put("hibernate.hikari.driverClassName", settings.type().getDriverClassName());
        properties.put("hibernate.hikari.maximumPoolSize", String.valueOf(settings.maxPoolSize()));
        properties.put("hibernate.hikari.minimumIdle", String.valueOf(settings.minIdle()));
        properties.put("hibernate.hikari.idleTimeout", String.valueOf(settings.idleTimeoutMs()));
        properties.put("hibernate.hikari.connectionTimeout", String.valueOf(settings.connectionTimeoutMs()));
        properties.put("hibernate.hikari.autoCommit", "true");
    }
}
