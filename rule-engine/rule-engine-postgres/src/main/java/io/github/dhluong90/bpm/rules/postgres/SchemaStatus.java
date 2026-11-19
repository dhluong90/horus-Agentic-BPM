package io.github.dhluong90.bpm.rules.postgres;

/**
 * State of the engine schema compared with the migrations in this build.
 *
 * @param currentVersion the latest applied engine version, or null when none is applied
 * @param pendingMigrations how many migrations of this build are not yet applied
 */
public record SchemaStatus(String currentVersion, int pendingMigrations) {

    public SchemaStatus {
        if (pendingMigrations < 0) {
            throw new IllegalArgumentException("pendingMigrations must be >= 0: " + pendingMigrations);
        }
    }

    /**
     * Tells whether the schema matches this build.
     *
     * @return true when a version is applied and nothing is pending
     */
    public boolean isCurrent() {
        return currentVersion != null && pendingMigrations == 0;
    }
}
