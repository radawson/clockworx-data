package org.clockworx.data.hibernate;

import org.hibernate.boot.model.naming.Identifier;
import org.hibernate.boot.model.naming.PhysicalNamingStrategyStandardImpl;
import org.hibernate.engine.jdbc.env.spi.JdbcEnvironment;

/**
 * A Hibernate PhysicalNamingStrategy that adds a configured prefix to all table names.
 * The prefix is applied <em>after</em> the standard physical-name conversion
 * (e.g. CamelCase to snake_case), matching the behavior originally used by the
 * Vampire plugin. Used to avoid naming collisions when multiple plugins share a
 * database.
 */
public class PrefixPhysicalNamingStrategy extends PhysicalNamingStrategyStandardImpl {

    private final String tablePrefix;

    /**
     * Creates a new naming strategy with the specified prefix.
     *
     * @param tablePrefix the prefix to add to table names (e.g. "vampire_"); may be null or empty
     */
    public PrefixPhysicalNamingStrategy(String tablePrefix) {
        this.tablePrefix = (tablePrefix != null && !tablePrefix.isEmpty()) ? tablePrefix : "";
    }

    @Override
    public Identifier toPhysicalTableName(Identifier logicalName, JdbcEnvironment context) {
        // 1. Get the standard physical name without the prefix (e.g. "vampire_player_entity")
        Identifier standardPhysicalName = super.toPhysicalTableName(logicalName, context);

        if (standardPhysicalName == null) {
            return null;
        }

        // 2. Apply prefix only if it's not empty
        String prefixedName = tablePrefix.isEmpty()
                ? standardPhysicalName.getText()
                : tablePrefix + standardPhysicalName.getText();

        // 3. Use the context's identifier helper to create the final Identifier.
        return context.getIdentifierHelper().toIdentifier(prefixedName);
    }
}
