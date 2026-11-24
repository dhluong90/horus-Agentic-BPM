package io.github.dhluong90.bpm.process.internal.store;

import io.github.dhluong90.bpm.process.api.InstanceActivities;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Read-only storage port of the query services. Every method takes the tenant and every query filters by it
 * first (TR-TEN-01). Methods join the caller's transaction. Implementations must be thread-safe.
 */
public interface ProcessQueryStore {

    /** Live tokens of one status at one node. */
    record LiveCount(String nodeId, String status, long count) {
    }

    /**
     * One range of the task query (PRE-030): tasks declared to a user, tasks a user claimed, open tasks of a role, of a
     * group, or every open or claimed task of the tenant. Each is served by its own index ordered by due time.
     */
    enum TaskRange {
        /** Tasks declared to the user, open or claimed. */
        MINE,
        /** Tasks the user claimed. */
        CLAIMED,
        /** Open tasks of the role. */
        ROLE,
        /** Open tasks of the group. */
        GROUP,
        /** Every open or claimed task; no key. */
        ANY
    }

    /** A task list item: the task with its instance's key, version and business key; no variables, no review. */
    record TaskSummaryRow(String tenantId, String id, String instanceId, String nodeId, String name, String assignee,
            String status, long lockVersion, String claimedBy, Instant createdAt, Instant dueAt, String definitionKey,
            int definitionVersion, String businessKey) {
    }

    /**
     * Tasks of one range with a due time, by {@code (dueAt, id)}, after the given key and due before {@code dueBefore}.
     *
     * @param key the user, role or group; null for {@link TaskRange#ANY}
     * @param statuses the statuses to keep
     * @param limit at most this many rows
     */
    List<TaskSummaryRow> datedTasks(String tenant, TaskRange range, String key, List<String> statuses,
            Instant afterDueAt, String afterId, Instant dueBefore, int limit);

    /**
     * Tasks of one range without a due time, by id, after {@code afterId}.
     *
     * @param key the user, role or group; null for {@link TaskRange#ANY}
     * @param statuses the statuses to keep
     * @param limit at most this many rows
     */
    List<TaskSummaryRow> undatedTasks(String tenant, TaskRange range, String key, List<String> statuses, String afterId,
            int limit);

    /** @return the id of the definition version, when it exists in the tenant */
    Optional<String> definitionId(String tenant, String key, int version);

    /** @return live token counts per node and status, read through the live token index only */
    List<LiveCount> liveTokenCounts(String tenant, String definitionId);

    /**
     * The history of one step of one instance, aggregated (PRE-027).
     *
     * @param nodeId the step
     * @param firstEnteredAt its first {@code NODE_ENTERED}, or null
     * @param lastEnteredAt its last {@code NODE_ENTERED}, or null
     * @param lastCompletedAt its last {@code NODE_COMPLETED} or {@code TOKEN_CANCELLED} (a step interrupted by a boundary event), or null
     * @param entries its {@code NODE_ENTERED} count
     * @param attemptIds its distinct attempt ids
     * @param lastActor the last non-null actor of its entries, or null
     */
    record NodeActivityRow(String nodeId, Instant firstEnteredAt, Instant lastEnteredAt, Instant lastCompletedAt,
            long entries, long attemptIds, String lastActor) {
    }

    /**
     * @return one row per step of the instance, from its history between {@code from} and {@code to} inclusive only
     */
    List<NodeActivityRow> nodeActivities(String tenant, String instanceId, Instant from, Instant to);

    /**
     * @return at most {@code limit} history entries of the instance between {@code from} and {@code to} inclusive,
     *     oldest first
     */
    List<InstanceActivities.Entry> historyEntries(String tenant, String instanceId, Instant from, Instant to, int limit);

    /**
     * @return at most {@code limit} history entries of the instance's undo ({@code COMPENSATION_*} and
     *     {@code COMPENSATED}) between {@code from} and {@code to} inclusive, oldest first
     */
    List<InstanceActivities.Entry> compensationEntries(String tenant, String instanceId, Instant from, Instant to,
            int limit);
}
