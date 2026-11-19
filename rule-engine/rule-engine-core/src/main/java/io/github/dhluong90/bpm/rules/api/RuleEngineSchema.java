package io.github.dhluong90.bpm.rules.api;

/**
 * Names the Rule Engine owns in the host database.
 *
 * <p>The engine reads and writes only tables whose names start with {@link #TABLE_PREFIX} and
 * records its own migrations in {@link #HISTORY_TABLE}, never in the host's Flyway history.
 */
public final class RuleEngineSchema {

    /** Prefix of every table the Rule Engine creates. */
    public static final String TABLE_PREFIX = "rle_";

    /** Flyway history table of the Rule Engine's own migrations. */
    public static final String HISTORY_TABLE = TABLE_PREFIX + "schema_history";

    private RuleEngineSchema() {
    }

    /**
     * Tells whether a table belongs to the Rule Engine.
     *
     * @param tableName an unquoted table name; must not be null
     * @return true when the name starts with {@link #TABLE_PREFIX}
     */
    public static boolean ownsTable(String tableName) {
        if (tableName == null) {
            throw new IllegalArgumentException("tableName must not be null");
        }
        return tableName.startsWith(TABLE_PREFIX);
    }
}
