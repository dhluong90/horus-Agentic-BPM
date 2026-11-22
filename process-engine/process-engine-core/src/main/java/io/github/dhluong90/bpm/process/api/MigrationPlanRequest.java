package io.github.dhluong90.bpm.process.api;

import java.util.List;

/**
 * A plan to move the running instances of one definition version to another version of the same key (#74). Steps are
 * matched by identical id, unless the plan says otherwise: {@code mappings} move the work of a renamed step, and
 * {@code cancellations} cancel the work of a removed step and continue at a named step (#106, {@code TR-MIG-03}).
 *
 * @param definitionKey the definition key
 * @param sourceVersion the version the instances run on
 * @param targetVersion the active version they would move to
 * @param idempotencyKey idempotency key, or null
 * @param mappings renamed steps, empty for none
 * @param cancellations removed steps whose work is cancelled, empty for none
 */
public record MigrationPlanRequest(String definitionKey, int sourceVersion, int targetVersion, String idempotencyKey,
        List<MigrationStepMapping> mappings, List<MigrationStepCancellation> cancellations) {

    public MigrationPlanRequest {
        mappings = mappings == null ? List.of() : List.copyOf(mappings);
        cancellations = cancellations == null ? List.of() : List.copyOf(cancellations);
    }

    /**
     * A plan that matches steps by identical id only.
     *
     * @param definitionKey the definition key
     * @param sourceVersion the version the instances run on
     * @param targetVersion the active version they would move to
     * @param idempotencyKey idempotency key, or null
     */
    public MigrationPlanRequest(String definitionKey, int sourceVersion, int targetVersion, String idempotencyKey) {
        this(definitionKey, sourceVersion, targetVersion, idempotencyKey, List.of(), List.of());
    }
}
