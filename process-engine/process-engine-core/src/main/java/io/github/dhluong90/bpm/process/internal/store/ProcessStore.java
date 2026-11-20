package io.github.dhluong90.bpm.process.internal.store;

import io.github.dhluong90.bpm.process.internal.exec.HistoryEntry;
import io.github.dhluong90.bpm.process.internal.exec.Token;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Storage port of the shell. Every method takes the tenant and every query filters by it (TR-TEN-01). Methods
 * join the caller's transaction; none opens its own. Implementations must be thread-safe.
 */
public interface ProcessStore {

    /** Definition version as stored. */
    record StoredDefinition(String tenantId, String id, String key, int version, String status, String name,
            String definitionJson, String definitionHash, String sourceFormat, String sourceDocument, String sourceHash,
            int nodeCount, String source, Instant effectiveFrom, String createdBy, Instant createdAt, String approvedBy,
            Instant approvedAt) {
    }

    /** Instance row; {@code sequence} is the kernel's id counter. */
    record InstanceRow(String tenantId, String id, String definitionId, String definitionKey, int definitionVersion,
            String businessKey, String status, String variablesJson, long sequence, long lockVersion, String startedBy,
            Instant startedAt, Instant endedAt) {
    }

    /** User task row. */
    record TaskRow(String tenantId, String id, String instanceId, String tokenId, String definitionId, String nodeId,
            String name, String assignee, boolean fourEyes, String status, long lockVersion, String completedBy,
            Instant createdAt, Instant completedAt) {
    }

    /** Recorded answer to an idempotent write; {@code responseJson} is null while the claim is pending. */
    record IdempotencyRecord(String tenantId, String operation, String key, String requestHash, String responseJson,
            Instant expiresAt) {
    }

    Optional<StoredDefinition> latestDefinition(String tenant, String key);

    Optional<StoredDefinition> effectiveDefinition(String tenant, String key, Instant now);

    Optional<StoredDefinition> definition(String tenant, String id);

    /** The version {@code version} of {@code key} in the tenant. */
    Optional<StoredDefinition> definitionVersion(String tenant, String key, int version);

    void insertDefinition(StoredDefinition definition);

    /**
     * @return false when an instance with the same business key exists (a concurrent start committed first)
     */
    boolean insertInstance(InstanceRow row);

    /** Reads and locks the instance row ({@code FOR UPDATE}); all transitions of one instance serialise on it. */
    Optional<InstanceRow> lockInstance(String tenant, String id);

    Optional<InstanceRow> findInstance(String tenant, String id);

    Optional<InstanceRow> findInstanceByBusinessKey(String tenant, String key, String businessKey);

    /** Updates status, variables, sequence and end time, and increments the lock version. */
    void updateInstance(InstanceRow row);

    List<Token> tokens(String tenant, String instanceId);

    /** Inserts new tokens and updates changed ones. */
    void saveTokens(String tenant, String instanceId, String definitionId, List<Token> tokens, Instant now);

    void insertTask(TaskRow task);

    Optional<TaskRow> findTask(String tenant, String id);

    /** @return rows updated: 1, or 0 when the lock version is stale or the task is not open */
    int completeTask(String tenant, String id, long expectedLockVersion, String completedBy, Instant at);

    void cancelTask(String tenant, String id);

    List<TaskRow> tasks(String tenant, String instanceId);

    void appendHistory(String tenant, String instanceId, String definitionId, List<HistoryEntry> entries);

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
}
