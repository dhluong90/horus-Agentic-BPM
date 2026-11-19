package io.github.dhluong90.bpm.rules.postgres;

/**
 * Outcome of one run of the engine's migrations.
 *
 * @param initialVersion the engine schema version before the run, or null on a clean database
 * @param currentVersion the engine schema version after the run, or null when nothing is applied
 * @param migrationsExecuted how many migrations this run applied; 0 when the schema was current
 */
public record SchemaMigrationResult(String initialVersion, String currentVersion, int migrationsExecuted) {

    public SchemaMigrationResult {
        if (migrationsExecuted < 0) {
            throw new IllegalArgumentException("migrationsExecuted must be >= 0: " + migrationsExecuted);
        }
    }
}
