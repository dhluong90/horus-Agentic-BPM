package io.github.dhluong90.bpm.process.internal.store;

import io.github.dhluong90.bpm.process.api.MigrationOutcome;
import io.github.dhluong90.bpm.process.api.MigrationPlan;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Storage of migration plans ({@code pre_migration}, {@code pre_migration_history}) and the reads of a dry run (#74,
 * PRE-014). Every method takes the tenant, which leads every key and index. The dry-run reads only read: nothing here
 * writes {@code pre_instance}, {@code pre_token} or {@code pre_history}.
 */
public interface MigrationStore {

    /**
     * A stored plan.
     *
     * @param tenantId the tenant
     * @param view the plan as the API shows it
     * @param sourceDefinitionId the definition row of the source version
     * @param targetDefinitionId the definition row of the target version
     */
    record StoredPlan(String tenantId, MigrationPlan view, String sourceDefinitionId, String targetDefinitionId) {
    }

    void insertPlan(StoredPlan plan);

    /** Appends one row to the plan's history. */
    void insertHistory(String tenant, String id, String migrationId, String action, String actorId,
            Map<String, Object> details, Instant occurredAt);

    Optional<StoredPlan> findPlan(String tenant, String migrationId);

    /** @return live tokens per step of one definition version, from {@code idx_pre_token_live} */
    Map<String, Long> liveTokensByNode(String tenant, String definitionId);

    /** @return running instances of one definition version */
    long runningInstances(String tenant, String definitionId);

    /** @return running instances of one definition version with a live token at any of the steps */
    long instancesAt(String tenant, String definitionId, Collection<String> nodeIds);

    /** @return up to {@code limit} instance ids with a live token at the step, in id order */
    List<String> instanceIdsAt(String tenant, String definitionId, String nodeId, int limit);

    /** @return up to {@code limit} running instance ids with no live token at any of the steps, in id order */
    List<String> movableInstanceIds(String tenant, String definitionId, Collection<String> blockedNodeIds, int limit);

    // ---- execution (#90, V34) --------------------------------------------------------------------------------

    /**
     * The execution columns of a plan row.
     *
     * @param tenantId the tenant
     * @param migrationId the plan id
     * @param status the plan status
     * @param approval the approval the plan needs
     * @param lockVersion the optimistic lock version of the proposal commands
     * @param proposedBy who proposed, or null
     * @param proposedAt when
     * @param reason the reason of the proposal
     * @param dryRunCheckedAt when the dry run the proposal rests on was made
     * @param approvedBy who approved
     * @param approvedAt when
     * @param rejectedBy who rejected
     * @param rejectedAt when
     * @param rejectionReason why
     * @param total running instances of the source version at proposal or approval
     * @param migrated instances moved
     * @param failed instances that failed, each with an incident
     * @param skipped instances that had ended or moved on
     * @param cursor the last instance id the runner passed, empty before the first
     * @param leaseOwner the runner that holds the plan, or null
     * @param leaseUntil when its lease ends
     * @param startedAt when a runner first took the plan
     * @param finishedAt when the plan completed
     */
    record Execution(String tenantId, String migrationId, String status, MigrationPlan.Approval approval,
            long lockVersion, String proposedBy, Instant proposedAt, String reason, Instant dryRunCheckedAt,
            String approvedBy, Instant approvedAt, String rejectedBy, Instant rejectedAt, String rejectionReason,
            long total, long migrated, long failed, long skipped, String cursor, String leaseOwner, Instant leaseUntil,
            Instant startedAt, Instant finishedAt) {
    }

    /**
     * A proposed execution waiting for a second person, as the pending approvals list shows it (#473).
     *
     * @param migrationId the plan id, the tie-break of the keyset
     * @param definitionKey the definition key
     * @param sourceVersion the version the instances run on
     * @param targetVersion the version they would move to
     * @param planHash the plan hash, the value an approver sends back as the expected plan hash
     * @param lockVersion the lock version an approval names in {@code If-Match}
     * @param proposedBy who proposed
     * @param proposedAt when
     * @param reason why the instances move
     */
    record ProposedRow(String migrationId, String definitionKey, int sourceVersion, int targetVersion,
            String planHash, long lockVersion, String proposedBy, Instant proposedAt, String reason) {
    }

    /**
     * A page of the tenant's plans in {@code PROPOSED}, oldest proposal first on {@code (proposed_at, id)} (#473).
     *
     * @param tenant tenant
     * @param afterAt keyset: {@code proposedAt} of the last row of the previous page, or null
     * @param afterId keyset: its id, or null
     * @param limit most rows
     * @return the rows
     */
    default List<ProposedRow> proposedExecutions(String tenant, Instant afterAt, String afterId, int limit) {
        throw new UnsupportedOperationException("proposed executions are not supported by this store");
    }

    /** A live token of an instance: its id and the step it waits at. */
    record LiveToken(String id, String nodeId) {
    }

    /** What the runner needs to know of a locked instance. */
    record LockedInstance(String definitionId, String status) {
    }

    /** The instance row is locked by another transaction; thrown by {@link #lockInstance}. */
    final class InstanceBusyException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public InstanceBusyException(String instanceId) {
            super("Instance " + instanceId + " is locked by another transaction");
        }
    }

    /**
     * What a cancelled step had open at a token (#106): the task, if any, and the jobs that were still due.
     *
     * @param taskId the cancelled task, or null
     * @param jobIds the cancelled jobs, in id order
     */
    record CancelledWork(String taskId, List<String> jobIds) {
        public CancelledWork {
            jobIds = List.copyOf(jobIds);
        }
    }

    /**
     * @return running instances of one definition version with a remote call in flight (a {@code CLAIMED} service or
     *     agent job) at any of the steps (#106)
     */
    long inFlightInstances(String tenant, String definitionId, Collection<String> nodeIds);

    /** @return up to {@code limit} such instance ids, in id order (#106) */
    List<String> inFlightInstanceIds(String tenant, String definitionId, Collection<String> nodeIds, int limit);

    /** @return true when the token has a remote call in flight: a {@code CLAIMED} service or agent job (#106) */
    boolean callInFlight(String tenant, String instanceId, String tokenId);

    /**
     * Moves a token and everything that waits for it from a step to its renamed counterpart: the token, its open task
     * (which keeps its id, claim, due time and four-eyes restriction), its pending jobs and its open incidents (#106).
     */
    void renameStep(String tenant, String instanceId, String tokenId, String fromNode, String toNode);

    /**
     * Cancels the open task and the due jobs of a token, as a removed step's work is cancelled (#106). Compensation
     * stack entries are not touched.
     *
     * @return what was cancelled
     */
    CancelledWork cancelOpenWork(String tenant, String instanceId, String tokenId, Instant now);

    /** Locks the plan row for the proposal commands. */
    Optional<Execution> lockExecution(String tenant, String migrationId);

    Optional<Execution> findExecution(String tenant, String migrationId);

    /**
     * Writes the proposal columns and the status, and increments the lock version.
     *
     * @param e the new state; only status, proposal, approval, rejection and total are written
     */
    void updateExecution(Execution e);

    /**
     * Takes one approved or running plan whose lease is free or expired, in any tenant (an engine sweep, like the job
     * claim), and leases it to {@code owner}. {@code SKIP LOCKED}: two runners never take the same plan.
     *
     * @return the plan as claimed, status {@code RUNNING}
     */
    Optional<Execution> claimRunnable(String owner, Instant now, Instant leaseUntil);

    /**
     * Passes the cursor, adds to the counts and extends the lease, only while {@code owner} still holds it.
     *
     * @return false when the lease was lost, which the caller answers by rolling back
     */
    boolean advance(String tenant, String migrationId, String owner, Instant now, Instant leaseUntil, String cursor,
            int migrated, int failed, int skipped);

    /**
     * Completes the plan, only while {@code owner} holds the lease.
     *
     * @return false when the lease was lost
     */
    boolean finish(String tenant, String migrationId, String owner, String status, Instant now);

    /** Gives the lease back so another poll takes the plan at once. */
    void release(String tenant, String migrationId, String owner);

    /** @return up to {@code limit} running instance ids of the version after {@code cursor}, in id order */
    List<String> nextBatch(String tenant, String definitionId, String cursor, int limit);

    /** @return the live tokens of the instance */
    List<LiveToken> liveTokens(String tenant, String instanceId);

    /**
     * Locks the instance row without waiting.
     *
     * @return the locked instance, empty when it does not exist
     * @throws InstanceBusyException when another transaction holds the row
     */
    Optional<LockedInstance> lockInstance(String tenant, String instanceId);

    /**
     * Points the instance and every row that carries its definition at the target: the instance, its tokens, open
     * tasks, due jobs, recorded step results and unresolved incidents. History rows keep the version they were written
     * under.
     */
    void moveInstance(String tenant, String instanceId, String targetDefinitionId, int targetVersion);

    /** @return true when the outcome of the instance is already recorded for the plan */
    boolean hasOutcome(String tenant, String migrationId, String instanceId);

    /**
     * Records the outcome of an instance once.
     *
     * @return false when one is already recorded
     */
    boolean insertOutcome(String tenant, String migrationId, MigrationOutcome outcome);

    /** @return up to {@code limit} outcomes after {@code afterInstanceId} ({@code ""} for the first page), in order */
    List<MigrationOutcome> outcomes(String tenant, String migrationId, String outcome, String afterInstanceId,
            int limit);

    /**
     * Failures that have no incident yet, after {@code afterInstanceId} ({@code ""} for the first), in id order. A
     * failure caused by a lock conflict gets its incident when the plan completes, because opening one needs the lock
     * another transaction holds.
     */
    List<MigrationOutcome> failuresWithoutIncident(String tenant, String migrationId, String afterInstanceId,
            int limit);

    /** Records the incident opened for a failure. */
    void setIncident(String tenant, String migrationId, String instanceId, String incidentId);

    /**
     * Limits how long the rest of the current transaction waits for a lock held by another transaction, so a stuck
     * transaction cannot stall the runner: the statement fails and the plan is picked up again.
     */
    void limitLockWait(java.time.Duration wait);

    /**
     * Extends the lease without touching the cursor or the counts, only while {@code owner} still holds it.
     *
     * @return false when the lease was lost
     */
    boolean renewLease(String tenant, String migrationId, String owner, Instant now, Instant leaseUntil);
}
