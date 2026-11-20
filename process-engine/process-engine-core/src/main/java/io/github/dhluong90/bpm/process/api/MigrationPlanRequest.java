package io.github.dhluong90.bpm.process.api;

/**
 * A plan to move the running instances of one definition version to another version of the same key (#74). Steps are
 * mapped by identical id; explicit mappings arrive with M3 ({@code TR-MIG-03}).
 *
 * @param definitionKey the definition key
 * @param sourceVersion the version the instances run on
 * @param targetVersion the active version they would move to
 * @param idempotencyKey idempotency key, or null
 */
public record MigrationPlanRequest(String definitionKey, int sourceVersion, int targetVersion, String idempotencyKey) {
}
