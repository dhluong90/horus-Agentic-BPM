package io.github.dhluong90.bpm.process.api;

/**
 * Migration plans for running instances (#74, DESIGN section 4.3). A plan names the version the instances run on and
 * the active version they would move to; a dry run reports which instances would move and which steps have no
 * counterpart, and changes nothing. Executing a plan needs four eyes (PD-131) and follows in a later release.
 *
 * <p>The tenant and the caller come from the resolvers; another tenant's plan or definition is not found. Creating a
 * plan and the dry run need {@code processes:migration:manage}; reading a plan needs it or {@code processes:read}.
 */
public interface MigrationService {

    /**
     * Creates a plan. Writes the plan, one history row and {@code processes.migration.planned.v1} in one
     * transaction; moves nothing.
     *
     * @param request the key and the two versions
     * @return the plan; a repeat with the same idempotency key and request returns the first plan
     * @throws DefinitionNotFoundException when the tenant has no such version
     * @throws InputInvalidException when the two versions are the same
     * @throws InvalidStateException when the target is not active, or the source was never approved
     * @throws IdempotencyKeyReuseException when the key was used with a different request
     */
    MigrationPlan plan(MigrationPlanRequest request);

    /**
     * @param migrationId the plan id
     * @return the plan
     * @throws MigrationNotFoundException when the caller's tenant has no such plan
     */
    MigrationPlan find(String migrationId);

    /**
     * Runs a dry run of the plan (PRE-014). Read-only: no row of any table is written, and no approval is needed.
     *
     * @param migrationId the plan id
     * @return the report
     * @throws MigrationNotFoundException when the caller's tenant has no such plan
     */
    MigrationDryRun validate(String migrationId);
}
