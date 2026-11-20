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
     * Incident row ({@code pre_incident}, #58): a step whose job failed for good.
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
     * @param status {@code OPEN} or {@code RESOLVED}
     * @param lockVersion optimistic lock version
     * @param createdAt when it was opened
     * @param resolvedAt when it was resolved, or null
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
            Instant createdAt, Instant resolvedAt) {
    }

    /** User task row; {@code claimedBy} is set while it is {@code CLAIMED} and kept once completed or cancelled. */
    record TaskRow(String tenantId, String id, String instanceId, String tokenId, String definitionId, String nodeId,
            String name, String assignee, boolean fourEyes, String status, long lockVersion, String claimedBy,
            Instant claimedAt, String completedBy, Instant createdAt, Instant completedAt, String reviewJson) {

        /** A task without review context. */
        public TaskRow(String tenantId, String id, String instanceId, String tokenId, String definitionId,
                String nodeId, String name, String assignee, boolean fourEyes, String status, long lockVersion,
                String claimedBy, Instant claimedAt, String completedBy, Instant createdAt, Instant completedAt) {
            this(tenantId, id, instanceId, tokenId, definitionId, nodeId, name, assignee, fourEyes, status,
                    lockVersion, claimedBy, claimedAt, completedBy, createdAt, completedAt, null);
        }
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
     * One row per key, ordered by key, after {@code afterKey} (exclusive) when it is not null.
     *
     * @param now the time that decides the effective version
     */
    List<DefinitionSummary> definitionKeys(String tenant, String afterKey, int limit, Instant now);

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

    void cancelTask(String tenant, String id);

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

    /** @param incident a new {@code OPEN} incident (#58) */
    void insertIncident(IncidentRow incident);

    /**
     * @param tenant tenant
     * @param instanceId instance
     * @return the instance's open incidents, oldest first
     */
    List<IncidentRow> openIncidents(String tenant, String instanceId);

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
