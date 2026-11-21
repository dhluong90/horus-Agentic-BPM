package io.github.dhluong90.bpm.process.api;

import java.util.List;

/**
 * Executing a migration plan (#90, PRE-015, DESIGN section 4.3). The holder of {@code processes:migration:manage}
 * proposes the execution with a reason; a second holder, never the proposer, approves it; only the approval lets the
 * engine's runner move instances, in bounded batches (PD-114, PD-131). With {@code migration.approval=single}, accepted
 * on the {@code dev} profile only, the proposal is approved by the proposer at once and recorded as such.
 *
 * <p>The tenant and the caller come from the resolvers; another tenant's plan answers
 * {@link MigrationNotFoundException}. Reading needs {@code processes:migration:manage} or {@code processes:read}.
 * Every command takes an idempotency key (null for none): a replay returns the first answer and writes nothing.
 */
public interface MigrationExecutionService {

    /**
     * Proposes the execution of a plan. The engine runs the dry run of the plan itself, now; the plan must have no
     * conflict. Writes the proposal, one history row and {@code processes.migration.proposed.v1} (and, in the
     * {@code single} mode, the approval) in one transaction; moves nothing.
     *
     * @param migrationId the plan
     * @param reason why the instances move, required, at most 2,000 characters
     * @param idempotencyKey idempotency key, or null
     * @return the execution, {@code PROPOSED}
     * @throws DryRunRequiredException when the dry run reports conflicts
     * @throws InvalidStateException when the plan is not {@code PLANNED}, {@code REJECTED}, or a proposal whose dry run
     *     has expired
     * @throws InputInvalidException when the reason is blank or too long
     */
    MigrationExecution propose(String migrationId, String reason, String idempotencyKey);

    /**
     * Approves a proposal as a second person. The dry run is run again, and the proposal's dry run must still be
     * fresh. The plan becomes {@code APPROVED}; the runner then moves the instances.
     *
     * @param migrationId the plan
     * @param expectedLockVersion the {@code lockVersion} of the proposal the approver read
     * @param idempotencyKey idempotency key, or null
     * @return the execution, {@code APPROVED}
     * @throws SelfApprovalForbiddenException when the caller proposed it; nothing is written
     * @throws DryRunRequiredException when the dry run now reports conflicts or the proposal's dry run has expired
     * @throws VersionConflictException when the proposal changed since it was read
     * @throws InvalidStateException when the plan is not {@code PROPOSED}
     */
    MigrationExecution approve(String migrationId, long expectedLockVersion, String idempotencyKey);

    /**
     * Rejects a proposal as a second person; the plan becomes {@code REJECTED} and may be proposed again.
     *
     * @param migrationId the plan
     * @param expectedLockVersion the {@code lockVersion} of the proposal
     * @param reason why, required
     * @param idempotencyKey idempotency key, or null
     * @return the execution, {@code REJECTED}
     * @throws SelfApprovalForbiddenException when the caller proposed it
     * @throws VersionConflictException when the proposal changed since it was read
     * @throws InvalidStateException when the plan is not {@code PROPOSED}
     */
    MigrationExecution reject(String migrationId, long expectedLockVersion, String reason, String idempotencyKey);

    /**
     * @param migrationId the plan
     * @return its execution state and progress
     * @throws MigrationNotFoundException when the caller's tenant has no such plan
     */
    MigrationExecution execution(String migrationId);

    /**
     * The per-instance outcomes of the plan in instance id order, one bounded page.
     *
     * @param migrationId the plan
     * @param outcome {@code MIGRATED}, {@code FAILED} or {@code SKIPPED}, or null for all
     * @param afterInstanceId list the instances after this id; null for the first page
     * @param limit at most this many, 1 to 200
     * @return the outcomes
     * @throws MigrationNotFoundException when the caller's tenant has no such plan
     * @throws InputInvalidException when the outcome or the limit is not valid
     */
    List<MigrationOutcome> outcomes(String migrationId, String outcome, String afterInstanceId, int limit);
}
