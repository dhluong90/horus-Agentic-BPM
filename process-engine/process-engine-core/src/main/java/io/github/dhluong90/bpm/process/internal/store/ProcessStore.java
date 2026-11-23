package io.github.dhluong90.bpm.process.internal.store;

import io.github.dhluong90.bpm.process.internal.exec.HistoryEntry;
import io.github.dhluong90.bpm.process.internal.exec.Token;
import io.github.dhluong90.bpm.process.api.DefinitionSummary;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Storage port of the shell. Every method takes the tenant and every query filters by it (TR-TEN-01). Methods
 * join the caller's transaction; none opens its own. Implementations must be thread-safe.
 */
public interface ProcessStore {

    /** Definition version as stored, with its lifecycle fields (DESIGN sections 3.2 and 7). */
    record StoredDefinition(String tenantId, String id, String key, int version, String status, String name,
            String definitionJson, String definitionHash, String sourceFormat, String sourceDocument, String sourceHash,
            int nodeCount, String source, Instant effectiveFrom, String createdBy, Instant createdAt, String approvedBy,
            Instant approvedAt, long lockVersion, String requestedBy, Instant requestedAt, String decisionComment,
            String retiredBy, Instant retiredAt, Integer supersededBy) {

        /** A new version as imported, lock version 0, no lifecycle fields beyond the approval of a dev import. */
        public StoredDefinition(String tenantId, String id, String key, int version, String status, String name,
                String definitionJson, String definitionHash, String sourceFormat, String sourceDocument,
                String sourceHash, int nodeCount, String source, Instant effectiveFrom, String createdBy,
                Instant createdAt, String approvedBy, Instant approvedAt) {
            this(tenantId, id, key, version, status, name, definitionJson, definitionHash, sourceFormat, sourceDocument,
                    sourceHash, nodeCount, source, effectiveFrom, createdBy, createdAt, approvedBy, approvedAt, 0, null,
                    null, null, null, null, null);
        }

        /** @return a copy {@code PENDING_APPROVAL}, requested by the actor */
        public StoredDefinition requested(String actor, Instant at, Instant requestedEffectiveFrom) {
            return new StoredDefinition(tenantId, id, key, version, "PENDING_APPROVAL", name, definitionJson,
                    definitionHash, sourceFormat, sourceDocument, sourceHash, nodeCount, source, requestedEffectiveFrom,
                    createdBy, createdAt, null, null, lockVersion + 1, actor, at, null, null, null, null);
        }

        /** @return a copy {@code ACTIVE}, approved by the approver, effective from the given time */
        public StoredDefinition approved(String approver, Instant at, Instant effective, String comment) {
            return new StoredDefinition(tenantId, id, key, version, "ACTIVE", name, definitionJson, definitionHash,
                    sourceFormat, sourceDocument, sourceHash, nodeCount, source, effective, createdBy, createdAt,
                    approver, at, lockVersion + 1, requestedBy, requestedAt, comment, null, null, null);
        }

        /** @return a copy back in {@code DRAFT}, with the reason; the request is cleared */
        public StoredDefinition rejected(String reason) {
            return new StoredDefinition(tenantId, id, key, version, "DRAFT", name, definitionJson, definitionHash,
                    sourceFormat, sourceDocument, sourceHash, nodeCount, source, null, createdBy, createdAt, null, null,
                    lockVersion + 1, null, null, reason, null, null, null);
        }

        /** @return a copy {@code RETIRED} by the actor, superseded by a version number or null */
        public StoredDefinition retired(String actor, Instant at, Integer superseded) {
            return new StoredDefinition(tenantId, id, key, version, "RETIRED", name, definitionJson, definitionHash,
                    sourceFormat, sourceDocument, sourceHash, nodeCount, source, effectiveFrom, createdBy, createdAt,
                    approvedBy, approvedAt, lockVersion + 1, requestedBy, requestedAt, decisionComment, actor, at,
                    superseded);
        }
    }

    /**
     * One lifecycle transition of a definition version ({@code pre_definition_history}, append-only).
     *
     * @param tenantId tenant
     * @param id row id
     * @param definitionId version id
     * @param action IMPORTED, REQUESTED, APPROVED, REJECTED or RETIRED
     * @param fromStatus status before, or null
     * @param toStatus status after
     * @param actorId who
     * @param comment comment or reason, or null
     * @param detailsJson further fields, for example the approved source hash
     * @param occurredAt when
     */
    record DefinitionHistoryRow(String tenantId, String id, String definitionId, String action, String fromStatus,
            String toStatus, String actorId, String comment, String detailsJson, Instant occurredAt) {
    }

    /** Instance row; {@code sequence} is the kernel's id counter. */
    record InstanceRow(String tenantId, String id, String definitionId, String definitionKey, int definitionVersion,
            String businessKey, String status, String variablesJson, long sequence, long lockVersion, String startedBy,
            Instant startedAt, Instant endedAt) {
    }

    /**
     * Incident row ({@code pre_incident}, #58, #68): a step that failed for good, and how it was resolved.
     *
     * @param tenantId tenant
     * @param id ULID
     * @param instanceId instance
     * @param definitionId definition version
     * @param tokenId the token left on the step, or null
     * @param nodeId the step
     * @param errorCode the error code
     * @param causeSummary built from codes only, never from an adapter's detail
     * @param retryCount attempts after the first
     * @param status {@code OPEN}, {@code RESOLUTION_PENDING} or {@code RESOLVED}
     * @param lockVersion optimistic lock version
     * @param createdAt when it was opened
     * @param resolvedAt when it was resolved, or null
     * @param resolution {@code RETRY_SUCCEEDED}, {@code COMPLETED_MANUALLY} or {@code INSTANCE_CANCELLED}, or null
     * @param resolvedBy who resolved it, or null
     * @param approvalMode {@code four-eyes} or {@code single} for a proposal, or null
     * @param proposedBy the proposer of a resolution, or null
     * @param proposedAt when it was proposed, or null
     * @param proposalReason the proposer's reason, or null
     * @param proposalOutputJson the output the step completes with on approval, or null
     * @param retrying whether a retry is in flight
     */
    /**
     * The latest recorded result of a step that a later agent step reads as memory (#101, {@code pre_step_result}).
     *
     * @param tenantId the instance's tenant
     * @param instanceId the instance
     * @param nodeId the step
     * @param definitionId the version the instance ran when the result was recorded
     * @param resultJson the result as the step stored it: an accepted answer or a reviewer's answer
     * @param source {@code ANSWER} or {@code REVIEW}
     * @param activityInstanceId the attempt id of the accepted answer, or the review task id
     * @param recordedAt engine clock
     */
    record StepResultRow(String tenantId, String instanceId, String nodeId, String definitionId, String resultJson,
            String source, String activityInstanceId, Instant recordedAt) {
    }

    record IncidentRow(String tenantId, String id, String instanceId, String definitionId, String tokenId,
            String nodeId, String errorCode, String causeSummary, int retryCount, String status, long lockVersion,
            Instant createdAt, Instant resolvedAt, String resolution, String resolvedBy, String approvalMode,
            String proposedBy, Instant proposedAt, String proposalReason, String proposalOutputJson,
            boolean retrying) {

        /** A new or pre-#68 row: no resolution, no proposal, no retry in flight. */
        public IncidentRow(String tenantId, String id, String instanceId, String definitionId, String tokenId,
                String nodeId, String errorCode, String causeSummary, int retryCount, String status, long lockVersion,
                Instant createdAt, Instant resolvedAt) {
            this(tenantId, id, instanceId, definitionId, tokenId, nodeId, errorCode, causeSummary, retryCount, status,
                    lockVersion, createdAt, resolvedAt, null, null, null, null, null, null, null, false);
        }

        /** @return a copy one lock version on, with this status and retry flag */
        public IncidentRow next(String newStatus, int newRetryCount, boolean newRetrying) {
            return new IncidentRow(tenantId, id, instanceId, definitionId, tokenId, nodeId, errorCode, causeSummary,
                    newRetryCount, newStatus, lockVersion + 1, createdAt, resolvedAt, resolution, resolvedBy,
                    approvalMode, proposedBy, proposedAt, proposalReason, proposalOutputJson, newRetrying);
        }

        /** @return a copy one lock version on that failed again with this cause, no retry in flight */
        public IncidentRow failedAgain(String newErrorCode, String newCauseSummary) {
            return new IncidentRow(tenantId, id, instanceId, definitionId, tokenId, nodeId, newErrorCode,
                    newCauseSummary, retryCount, status, lockVersion + 1, createdAt, resolvedAt, resolution, resolvedBy,
                    approvalMode, proposedBy, proposedAt, proposalReason, proposalOutputJson, false);
        }

        /** @return a copy one lock version on with this proposal, {@code RESOLUTION_PENDING} */
        public IncidentRow proposed(String by, Instant at, String reason, String outputJson, String mode) {
            return new IncidentRow(tenantId, id, instanceId, definitionId, tokenId, nodeId, errorCode, causeSummary,
                    retryCount, "RESOLUTION_PENDING", lockVersion + 1, createdAt, resolvedAt, resolution, resolvedBy,
                    mode, by, at, reason, outputJson, false);
        }

        /** @return a copy one lock version on without its proposal, {@code OPEN} again */
        public IncidentRow proposalRejected() {
            return new IncidentRow(tenantId, id, instanceId, definitionId, tokenId, nodeId, errorCode, causeSummary,
                    retryCount, "OPEN", lockVersion + 1, createdAt, resolvedAt, null, null, null, null, null, null,
                    null, false);
        }

        /** @return a copy one lock version on, {@code RESOLVED} with this resolution */
        public IncidentRow resolved(String how, String by, Instant at) {
            return new IncidentRow(tenantId, id, instanceId, definitionId, tokenId, nodeId, errorCode, causeSummary,
                    retryCount, "RESOLVED", lockVersion + 1, createdAt, at, how, by, approvalMode, proposedBy,
                    proposedAt, proposalReason, proposalOutputJson, false);
        }
    }

    /**
     * One undo on the compensation stack of a scope token (#67, V31).
     *
     * @param seq push order within the scope token, from 1
     * @param instanceSeq push order within the instance, from 1, assigned by the store: a compensation of an enclosing
     *     scope walks the entries of its sub-processes too, and takes the highest {@code PENDING} one first (#488)
     * @param forwardJobId the forward step's job, or null for a local step
     * @param forwardAttemptId the accepted forward attempt
     * @param handler the handler or adapter that undoes the step, or {@code manual}
     * @param mode {@code local}, {@code remote} or {@code manual}
     * @param inputSnapshotJson the forward inputs and result, plain: only the undo reads them
     * @param status {@code PENDING}, {@code RUNNING}, {@code DONE}, {@code FAILED} or {@code MANUAL}
     * @param jobId the undo's own job, the idempotency key of a remote undo; null until it is scheduled
     */
    record CompensationEntry(String tenantId, String id, String instanceId, String scopeTokenId, int seq, String stepId,
            String forwardJobId, String forwardAttemptId, String handler, String mode, String inputSnapshotJson,
            String status, String jobId, Instant pushedAt, Instant completedAt, int instanceSeq) {

        /** Status of an entry not yet undone. */
        public static final String PENDING = "PENDING";
        /** Status of an entry whose undo job is due or running. */
        public static final String RUNNING = "RUNNING";
        /** Status of an entry undone, by its job or by an approved resolution. */
        public static final String DONE = "DONE";
        /** Status of an entry whose undo failed for good; an incident is open. */
        public static final String FAILED = "FAILED";
        /** Status of an entry that waits for an operator; an incident is open. */
        public static final String MANUAL = "MANUAL";

        /** @return a copy with this status, job and completion time */
        public CompensationEntry with(String newStatus, String newJobId, Instant newCompletedAt) {
            return new CompensationEntry(tenantId, id, instanceId, scopeTokenId, seq, stepId, forwardJobId,
                    forwardAttemptId, handler, mode, inputSnapshotJson, newStatus, newJobId, pushedAt, newCompletedAt,
                    instanceSeq);
        }
    }

    /** User task row; {@code claimedBy} is set while it is {@code CLAIMED} and kept once completed or cancelled. */
    record TaskRow(String tenantId, String id, String instanceId, String tokenId, String definitionId, String nodeId,
            String name, String assignee, boolean fourEyes, String status, long lockVersion, String claimedBy,
            Instant claimedAt, String completedBy, Instant createdAt, Instant completedAt, String reviewJson,
            Instant dueAt) {

        /** A task without a due time. */
        public TaskRow(String tenantId, String id, String instanceId, String tokenId, String definitionId,
                String nodeId, String name, String assignee, boolean fourEyes, String status, long lockVersion,
                String claimedBy, Instant claimedAt, String completedBy, Instant createdAt, Instant completedAt,
                String reviewJson) {
            this(tenantId, id, instanceId, tokenId, definitionId, nodeId, name, assignee, fourEyes, status,
                    lockVersion, claimedBy, claimedAt, completedBy, createdAt, completedAt, reviewJson, null);
        }

        /** A task without review context. */
        public TaskRow(String tenantId, String id, String instanceId, String tokenId, String definitionId,
                String nodeId, String name, String assignee, boolean fourEyes, String status, long lockVersion,
                String claimedBy, Instant claimedAt, String completedBy, Instant createdAt, Instant completedAt) {
            this(tenantId, id, instanceId, tokenId, definitionId, nodeId, name, assignee, fourEyes, status,
                    lockVersion, claimedBy, claimedAt, completedBy, createdAt, completedAt, null);
        }
    }

    /**
     * The escalation of one user task (PRE-178): the levels resolved at creation, how many were applied, and when the
     * next one is due.
     *
     * @param tenantId tenant
     * @param taskId task
     * @param instanceId the task's instance
     * @param level levels already applied, 0 when none
     * @param nextAt when the next level is due, or null when none is left
     * @param json the resolved levels and the claim rule
     */
    record EscalationRow(String tenantId, String taskId, String instanceId, int level, Instant nextAt, String json) {
    }

    /** Recorded answer to an idempotent write; {@code responseJson} is null while the claim is pending. */
    record IdempotencyRecord(String tenantId, String operation, String key, String requestHash, String responseJson,
            Instant expiresAt) {
    }

    Optional<StoredDefinition> latestDefinition(String tenant, String key);

    Optional<StoredDefinition> effectiveDefinition(String tenant, String key, Instant now);

    Optional<StoredDefinition> definition(String tenant, String id);

    Optional<StoredDefinition> definitionVersion(String tenant, String key, int version);

    /**
     * The {@code ACTIVE} definition versions of every tenant, at most {@code limit}, by tenant, key and version, for the
     * start-up summary of unclassified fields (PD-146, #438). Engine-internal, across tenants on purpose: read once at
     * start-up outside any request, never exposed to a caller.
     *
     * @param limit the most versions read
     * @return the versions
     */
    default List<StoredDefinition> activeDefinitions(int limit) {
        return List.of();
    }

    /**
     * Records a broadcast signal of a tenant (#492). The id is the idempotency key of the broadcast: a second insert of
     * the same id writes nothing.
     *
     * @param tenant tenant
     * @param id the broadcast id
     * @param name the signal name
     * @param thrownBy the caller or the engine identity that threw it
     * @param now when
     * @return whether this call recorded it
     */
    default boolean insertSignal(String tenant, String id, String name, String thrownBy, Instant now) {
        throw new UnsupportedOperationException("This store keeps no signals");
    }

    /**
     * Stores the wait of a token on a signal (#492): one row of the tenant, deleted when it is woken or cancelled.
     *
     * @param tenant tenant
     * @param id the subscription id, which is the waiting token's activity instance id
     * @param name the signal name
     * @param definitionId definition version of the instance
     * @param instanceId the waiting instance
     * @param tokenId the waiting token
     * @param nodeId the catching event
     * @param now when
     */
    default void insertSignalSubscription(String tenant, String id, String name, String definitionId, String instanceId,
            String tokenId, String nodeId, Instant now) {
        throw new UnsupportedOperationException("This store keeps no signal subscriptions");
    }

    /**
     * Deletes a subscription of a tenant; a missing one is not an error.
     *
     * @param tenant tenant
     * @param id subscription id
     */
    default void deleteSubscription(String tenant, String id) {
        throw new UnsupportedOperationException("This store keeps no signal subscriptions");
    }

    /**
     * Writes one {@code SIGNAL_DELIVERY} job, due now, for each waiting subscription of the tenant for the signal, in one
     * statement and without locking any instance (#492). The job id is the subscription id, so a subscription is woken at
     * most once however many broadcasts reach it before it is consumed.
     *
     * @param tenant the broadcaster's tenant, the only one read or written
     * @param name signal name
     * @param signalId the broadcast the jobs belong to, kept in the job payload
     * @param lease lease of a claim
     * @param maxAttempts claims after which a job fails
     * @param now due time and creation time
     * @return the number of jobs written
     */
    default int wakeSignalWaiters(String tenant, String name, String signalId, java.time.Duration lease,
            int maxAttempts, Instant now) {
        throw new UnsupportedOperationException("This store keeps no signal subscriptions");
    }

    /**
     * The active versions of a tenant whose start event is a signal start for the name, newest first (#492). The caller
     * keeps only a version that is the key's effective one.
     *
     * @param tenant tenant
     * @param name signal name
     * @param limit the most versions read
     * @return the versions
     */
    default List<StoredDefinition> signalStartDefinitions(String tenant, String name, int limit) {
        return List.of();
    }

    /**
     * One row per key, ordered by key, after {@code afterKey} (exclusive) when it is not null.
     *
     * @param now the time that decides the effective version
     */
    List<DefinitionSummary> definitionKeys(String tenant, String afterKey, int limit, Instant now);

    /**
     * A definition version waiting for approval, without its definition or source (#31, PRE-190).
     *
     * @param id the version id, the tie-break of the keyset
     * @param key the definition key
     * @param name the process name, or null
     * @param version the version number
     * @param definitionHash the definition hash
     * @param lockVersion the optimistic lock
     * @param createdBy the importer or creator
     * @param requestedBy the requester
     * @param requestedAt when approval was requested
     */
    record PendingApprovalRow(String id, String key, String name, int version, String definitionHash,
            long lockVersion, String createdBy, String requestedBy, Instant requestedAt) {
    }

    /**
     * A page of the tenant's definition versions in {@code PENDING_APPROVAL}, oldest request first on
     * {@code (requested_at, id)} (PRE-190).
     *
     * @param tenant tenant
     * @param afterRequestedAt keyset: the {@code requestedAt} of the last row of the previous page, or null
     * @param afterId keyset: its id, or null
     * @param limit most rows
     * @return the rows
     */
    default List<PendingApprovalRow> pendingApprovals(String tenant, Instant afterRequestedAt, String afterId,
            int limit) {
        throw new UnsupportedOperationException("pending approvals are not supported by this store");
    }

    void insertDefinition(StoredDefinition definition);

    /**
     * Locks every version row of a key ({@code FOR UPDATE}, in version order), so lifecycle commands and the
     * effective-date job of one key serialise.
     *
     * @return the versions, oldest first; empty when the key has none in the tenant
     */
    List<StoredDefinition> lockDefinitions(String tenant, String key);

    /**
     * Writes the lifecycle fields and the incremented lock version of a version.
     *
     * @param updated the new state; its {@code lockVersion} is the expected one plus 1
     * @return rows updated: 1, or 0 when the stored lock version is not {@code updated.lockVersion() - 1}
     */
    int updateDefinition(StoredDefinition updated);

    void appendDefinitionHistory(DefinitionHistoryRow row);

    /** @return the history of a version, oldest first */
    List<DefinitionHistoryRow> definitionHistory(String tenant, String definitionId);

    /**
     * @return false when an instance with the same business key exists (a concurrent start committed first)
     */
    boolean insertInstance(InstanceRow row);

    /** Reads and locks the instance row ({@code FOR UPDATE}); all transitions of one instance serialise on it. */
    Optional<InstanceRow> lockInstance(String tenant, String id);

    Optional<InstanceRow> findInstance(String tenant, String id);

    Optional<InstanceRow> findInstanceByBusinessKey(String tenant, String key, String businessKey);

    // ---- called child processes (#92, V42, V43) ------------------------------------------------------------------

    /**
     * The link of a called child to the parent token it was started for.
     *
     * @param parentInstanceId the parent instance
     * @param parentTokenId the parent's token waiting on the call
     * @param callAttempt 0 for the first child of the token, one more for each child a retry started
     * @param callDepth 1 for a child of an instance nobody called, one more for each level below
     */
    record ParentLink(String parentInstanceId, String parentTokenId, int callAttempt, int callDepth) {
    }

    /** @return the link of the instance to its parent, empty when nobody called it */
    default Optional<ParentLink> parentLink(String tenant, String instanceId) {
        return Optional.empty();
    }

    /** @return how many children have been started for the parent token, which is the next call attempt */
    default int callAttempts(String tenant, String parentTokenId) {
        return 0;
    }

    /**
     * Links a new child to the parent token it was started for. The unique index on the tenant, the token and the
     * attempt refuses a second child for the same attempt, whichever replica starts it.
     */
    default void linkChild(String tenant, String childId, String parentInstanceId, String parentTokenId,
            int callAttempt, int callDepth) {
        throw new UnsupportedOperationException("called processes need a store that implements linkChild");
    }

    /** @return up to {@code limit} children of the parent that are still {@code RUNNING} or {@code COMPENSATING}, in id order */
    default List<String> activeChildren(String tenant, String parentInstanceId, int limit) {
        return List.of();
    }

    /**
     * A token waiting for a message (#491).
     *
     * @param tenantId the tenant
     * @param id the subscription id, the waiting token's activity instance id
     * @param instanceId the instance
     * @param tokenId the waiting token
     * @param nodeId the step
     * @param messageName the message it waits for
     * @param correlationKey the key the message must carry
     * @param createdAt when the token began to wait
     */
    record MessageSubscriptionRow(String tenantId, String id, String instanceId, String tokenId, String nodeId,
            String messageName, String correlationKey, Instant createdAt) {
    }

    /** Stores a subscription; called after the instance's tokens are saved. */
    default void insertSubscription(MessageSubscriptionRow row) {
        throw new UnsupportedOperationException("messages need a store that implements insertSubscription");
    }

    /**
     * Deletes the subscriptions of an instance whose id is not in {@code keep}: the tokens that waited have left.
     *
     * @param keep the subscription ids of the tokens that still wait
     */
    default void retainSubscriptions(String tenant, String instanceId, java.util.Collection<String> keep) {
    }

    /**
     * @param limit the most rows read; one more than the caller tolerates tells it the match is not unique
     * @return the subscriptions of the tenant for the message and key, in id order
     */
    default List<MessageSubscriptionRow> subscriptions(String tenant, String messageName, String correlationKey,
            int limit) {
        return List.of();
    }

    /**
     * Instances newest first ({@code started_at DESC, id DESC}), strictly after the keyset position when
     * {@code afterStartedAt} is not null.
     *
     * @param statuses the statuses, never empty, each one of the instance statuses
     * @param definitionKey null for every key
     * @param from inclusive lower bound, or null
     * @param to exclusive upper bound, or null
     */
    List<InstanceRow> instances(String tenant, List<String> statuses, String definitionKey, Instant from, Instant to,
            Instant afterStartedAt, String afterId, int limit);

    /** Updates status, variables, sequence and end time, and increments the lock version. */
    void updateInstance(InstanceRow row);

    List<Token> tokens(String tenant, String instanceId);

    /**
     * When each token of the instance entered its current node, read through the instance's token index. Used for
     * the duration of a step that waited in an earlier transaction ({@code processes.step.completed.v1}).
     *
     * @return token id to entry time; empty when the store keeps no entry times
     */
    default java.util.Map<String, java.time.Instant> tokenEnteredAt(String tenant, String instanceId) {
        return java.util.Map.of();
    }

    /** Inserts new tokens and updates changed ones. */
    void saveTokens(String tenant, String instanceId, String definitionId, List<Token> tokens, Instant now);

    void insertTask(TaskRow task);

    Optional<TaskRow> findTask(String tenant, String id);

    /** @return rows updated: 1, or 0 when the lock version is stale or the task is not claimed by {@code completedBy} */
    int completeTask(String tenant, String id, long expectedLockVersion, String completedBy, Instant at);

    /** @return rows updated: 1, or 0 when the lock version is stale or the task is not open */
    int claimTask(String tenant, String id, long expectedLockVersion, String claimant, Instant at);

    /** @return rows updated: 1, or 0 when the lock version is stale or the task is not claimed by {@code claimant} */
    int unclaimTask(String tenant, String id, long expectedLockVersion, String claimant);

    /**
     * Gives a task to a new claimant, the lock version moving by one.
     *
     * @param expectedStatus {@code OPEN} for a reassignment, {@code CLAIMED} for a delegation
     * @param previousClaimant the claimant a delegation must still find, or null for a reassignment
     * @return rows updated: 1, or 0 when the lock version is stale or the task is no longer in that state
     */
    int handOverTask(String tenant, String id, long expectedLockVersion, String expectedStatus,
            String previousClaimant, String newClaimant, Instant at);

    void cancelTask(String tenant, String id);

    /**
     * Keeps the resolved escalation of a task just inserted (PRE-178).
     *
     * @param tenant tenant
     * @param taskId the task
     * @param json the resolved levels and the claim rule
     * @param firstAt when the first level fires
     */
    default void scheduleEscalation(String tenant, String taskId, String json, Instant firstAt) {
        throw new UnsupportedOperationException("escalation is not supported by this store");
    }

    /**
     * The sweep's read (PRE-178): open or claimed tasks of every tenant whose next level has come due, oldest first.
     *
     * @param now the engine clock
     * @param limit the most rows; the sweep's batch size
     * @return task keys only: the sweep locks the instance and rereads each task before it changes anything
     */
    default List<EscalationRow> dueEscalations(Instant now, int limit) {
        return List.of();
    }

    /**
     * @param tenant tenant
     * @param taskId the task
     * @return the task's escalation, or empty when it declared none
     */
    default Optional<EscalationRow> escalation(String tenant, String taskId) {
        return Optional.empty();
    }

    /**
     * Applies one level: moves the task to {@code assignee}, releases the claim when asked, counts the level, sets the
     * time of the next one and moves the lock version. Only while the task is open or claimed, at the version read.
     *
     * @param assignee {@code user:}, {@code role:} or {@code group:}; null leaves the assignment as it is (a refused
     *     level, which is only counted)
     * @return rows updated: 1, or 0 when the task is no longer open or claimed or its lock version moved
     */
    default int escalateTask(String tenant, String id, long expectedLockVersion, String assignee, boolean releaseClaim,
            int level, Instant nextAt) {
        throw new UnsupportedOperationException("escalation is not supported by this store");
    }

    List<TaskRow> tasks(String tenant, String instanceId);

    void appendHistory(String tenant, String instanceId, String definitionId, List<HistoryEntry> entries);

    /**
     * Keeps a step's recorded result for the memory of later agent steps (#101): inserts it, or replaces the
     * instance's earlier result of the same step, so the latest one is read.
     *
     * @param row the result
     */
    void recordStepResult(StepResultRow row);

    /**
     * @param tenant the instance's tenant
     * @param instanceId the instance
     * @param nodeIds the steps
     * @return the recorded result of each of those steps in this instance that has one, by step id; never another
     *     instance's or another tenant's
     */
    Map<String, String> stepResults(String tenant, String instanceId, java.util.Collection<String> nodeIds);

    /**
     * Pushes an undo onto the stack of its scope token, with the next {@code seq} of that scope (#67). The caller holds
     * the instance row lock, so pushes of one instance never race.
     *
     * @param entry the entry; its {@code seq} and {@code instanceSeq} are ignored and assigned here
     */
    default void pushCompensation(CompensationEntry entry) {
        throw new UnsupportedOperationException("compensation is not supported by this store");
    }

    /**
     * @param tenant tenant
     * @param instanceId instance
     * @return every stack entry of the instance, by scope token and then newest first; bounded by the instance's
     *     completed steps with an undo
     */
    default List<CompensationEntry> compensationEntries(String tenant, String instanceId) {
        throw new UnsupportedOperationException("compensation is not supported by this store");
    }

    /**
     * @param entry the entry with its new status, job id and completion time
     * @return the rows changed: 1, or 0 when the tenant has no such entry
     */
    default int updateCompensation(CompensationEntry entry) {
        throw new UnsupportedOperationException("compensation is not supported by this store");
    }

    /** @param incident a new {@code OPEN} incident (#58) */
    void insertIncident(IncidentRow incident);

    /**
     * @param tenant tenant
     * @param instanceId instance
     * @return the instance's open incidents, oldest first
     */
    List<IncidentRow> openIncidents(String tenant, String instanceId);

    /**
     * Locks one incident ({@code FOR UPDATE}) in the caller's tenant (#68). The caller holds the instance row lock
     * first, the order every instance transition takes.
     *
     * @param tenant tenant
     * @param id incident id
     * @return the row, or empty when the tenant has no such incident
     */
    default java.util.Optional<IncidentRow> lockIncident(String tenant, String id) {
        throw new UnsupportedOperationException("incidents are not supported by this store");
    }

    /**
     * @param tenant tenant
     * @param id incident id
     * @return the row, unlocked, or empty when the tenant has no such incident
     */
    default java.util.Optional<IncidentRow> findIncident(String tenant, String id) {
        throw new UnsupportedOperationException("incidents are not supported by this store");
    }

    /**
     * Writes an incident row whose {@code lockVersion} is one higher than the stored one.
     *
     * @param updated the row with its new lock version
     * @return 1 when written, 0 when the stored lock version was not {@code updated.lockVersion() - 1}
     */
    default int updateIncident(IncidentRow updated) {
        throw new UnsupportedOperationException("incidents are not supported by this store");
    }

    /**
     * A page of the tenant's incidents of one status, newest first on {@code (created_at, id)} (PRE-080).
     *
     * @param tenant tenant
     * @param status the status
     * @param afterCreatedAt keyset: the {@code createdAt} of the last row of the previous page, or null
     * @param afterId keyset: its id, or null
     * @param limit most rows
     * @return the rows
     */
    default List<IncidentRow> incidents(String tenant, String status, Instant afterCreatedAt, String afterId,
            int limit) {
        throw new UnsupportedOperationException("incidents are not supported by this store");
    }

    /**
     * @param tenant tenant
     * @param instanceId instance
     * @param eventType a history event type, for example {@code ASYNC_RESULT_REFUSED}
     * @return the distinct actors of the instance's history entries of that type
     */
    java.util.Set<String> historyActors(String tenant, String instanceId, String eventType);

    /**
     * @param tenant tenant
     * @param instanceId instance
     * @param taskId a task of the instance
     * @return the async job whose refused result the task reviews ({@code STEP_ROUTED_TO_REVIEW}), or empty for an
     *     ordinary user task
     */
    Optional<String> reviewedJob(String tenant, String instanceId, String taskId);

    Optional<IdempotencyRecord> idempotency(String tenant, String operation, String key, Instant now);

    /**
     * Claims a key before the work runs: inserts a pending record, or takes over an expired one. A concurrent claim of
     * the same key blocks on the primary key until the first transaction ends.
     *
     * @return true when this transaction holds the claim; false when a live record exists
     */
    boolean claimIdempotency(String tenant, String operation, String key, String requestHash, Instant expiresAt,
            Instant now);

    /** Stores the answer of a claimed key in the same transaction as the work. */
    void completeIdempotency(String tenant, String operation, String key, String responseJson);

    /**
     * Creates the engine secret of this purpose unless one exists, and returns the stored one. Not tenant data: one
     * key per database serves every tenant (the cursor MAC covers the tenant). Concurrent first calls on two replicas
     * neither fail nor differ.
     *
     * @param name the purpose, for example {@code cursor-hmac}
     * @param candidate the key to store when none exists, at least 32 bytes
     * @param now creation time
     * @return the stored key
     */
    byte[] engineKey(String name, byte[] candidate, Instant now);
}
