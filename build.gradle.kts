plugins {
    id("java-library")
}

// Group and version are loaded from gradle.properties.
// Consumed by plugins via Gradle composite build (includeBuild) with dependency
// substitution for the coordinate "org.clockworx:clockworx-data".

repositories {
    mavenCentral()
}

dependencies {
    // Hibernate ORM stack - exposed as api so consuming plugins compile against
    // Session/SessionFactory/etc. without re-declaring versions.
    api("org.hibernate:hibernate-core:6.6.40.Final")
    api("org.hibernate:hibernate-community-dialects:6.6.40.Final")
    api("org.hibernate.orm:hibernate-hikaricp:6.6.40.Final")
    api("jakarta.persistence:jakarta.persistence-api:3.1.0")

    // Flyway schema migrations
    api("org.flywaydb:flyway-core:12.10.0")
    api("org.flywaydb:flyway-mysql:12.10.0")

    // Connection pooling
    api("com.zaxxer:HikariCP:7.1.0")

    // Logging bridge required by Hibernate
    api("org.jboss.logging:jboss-logging:3.6.1.Final")

    // JDBC drivers - bundled so consuming plugins get all three backends
    api("org.xerial:sqlite-jdbc:3.53.2.0")
    api("com.mysql:mysql-connector-j:9.1.0")
    api("org.postgresql:postgresql:42.7.11")

    // SLF4J API is provided at runtime by Paper
    compileOnly("org.slf4j:slf4j-api:2.0.9")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

tasks.test {
    useJUnitPlatform()
}
