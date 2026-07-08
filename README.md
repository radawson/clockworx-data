# clockworx-data

Shared data-access library for Clockworx Paper plugins. Provides the
Hibernate ORM + Flyway + HikariCP bootstrap that was previously copy-pasted
across Vampire, Werewolf, Bookcase, cotr, villages, and BattleArena, so each plugin only maintains its own
JPA entities, Flyway migration SQL, and domain-level database manager.

## What it provides

| Class | Purpose |
|---|---|
| `org.clockworx.data.DatabaseType` | Supported backends (SQLITE, MYSQL, POSTGRES) with JDBC driver class names |
| `org.clockworx.data.DatabaseSettings` | Immutable connection settings record (type, url, credentials, table prefix, pool sizing) |
| `org.clockworx.data.hibernate.HibernateBootstrap` | Builds a `SessionFactory` from settings + entity classes (SQLite single-connection; MySQL/Postgres via HikariCP) |
| `org.clockworx.data.hibernate.HibernateSessionManager` | Lazily-initialized SessionFactory owner with async `executeTransaction` / `executeTransactionVoid` / `executeRead` helpers and graceful shutdown guards |
| `org.clockworx.data.hibernate.PrefixPhysicalNamingStrategy` | Applies a configurable table-name prefix after standard snake_case conversion |
| `org.clockworx.data.flyway.FlywayMigrator` | Runs Flyway migrations with classloader swap, explicit driver loading, `baselineOnMigrate` (baseline version 1 for pre-existing schemas), `${tablePrefix}` placeholder, and prefixed history table |

## What stays in each plugin

- JPA `@Entity` classes and domain POJOs
- Flyway migration SQL under `src/main/resources/db/migration/`
- The domain-level database manager (CRUD API) built on `HibernateSessionManager`
- Config loading that produces a `DatabaseSettings`
- The async `Executor` (typically wrapping Paper's async scheduler with an
  `isEnabled` guard) - this library has no Bukkit/Paper dependency

## Consumption (Gradle composite build)

In the plugin's `settings.gradle.kts`:

```kotlin
includeBuild("../clockworx-data") {
    dependencySubstitution {
        substitute(module("org.clockworx:clockworx-data")).using(project(":"))
    }
}
```

In the plugin's `build.gradle.kts`:

```kotlin
dependencies {
    implementation("org.clockworx:clockworx-data:0.1.0-SNAPSHOT")
}
```

The library and its transitive dependencies (Hibernate, Flyway, HikariCP, JDBC
drivers) are shaded into each plugin's jar. Relocate to the consuming plugin's
namespace (`${project.group}.lib.*`), and exclude `org/sqlite/**` from
relocation to keep SQLite JNI native loading working.

## Typical plugin wiring

```java
DatabaseSettings settings = DatabaseSettings.withDefaults(
        DatabaseType.fromString(cfg.getDatabaseType()),
        cfg.getDatabaseUrl(), cfg.getDatabaseUser(), cfg.getDatabasePassword(),
        cfg.getDatabaseTablePrefix());

// 1. Migrate schema (onEnable, before any DB access)
FlywayMigrator.migrate(getClass().getClassLoader(), settings, getLogger());

// 2. Create the session manager with the plugin's entities
HibernateSessionManager sessions = new HibernateSessionManager(
        settings,
        List.of(MyEntity.class),
        asyncExecutor, // plugin-supplied, e.g. Paper async scheduler w/ guards
        getLogger());

// 3. Use in the plugin's domain database manager
sessions.executeTransactionVoid(session -> session.persist(entity));

// 4. onDisable
sessions.shutdown();
```

## Requirements

- Java 25 toolchain
- Consumers target Paper >= 26.1 (Mojang-mapped, no reobf)
