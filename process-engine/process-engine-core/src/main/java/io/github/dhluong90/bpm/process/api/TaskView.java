package io.github.dhluong90.bpm.process.api;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;

/**
 * A user task with its context, as one caller sees it.
 *
 * @param id task id
 * @param instanceId the instance
 * @param definitionKey the definition key of the instance
 * @param definitionVersion the pinned version of the instance
 * @param businessKey the instance business key, or null
 * @param nodeId the {@code userTask} id
 * @param name the step name, or null
 * @param assignee {@code user:<id>}, {@code role:<role>} or {@code group:<group>}
 * @param fourEyes whether the starter and earlier completers of the instance are refused
 * @param status {@code OPEN}, {@code CLAIMED}, {@code COMPLETED} or {@code CANCELLED}
 * @param lockVersion optimistic lock version, sent back as {@code If-Match}
 * @param claimedBy the claimant while {@code CLAIMED}, or null
 * @param claimedAt when it was claimed, or null
 * @param createdAt when the task was created
 * @param completedBy who completed it, or null
 * @param completedAt when it was completed, or null
 * @param allowedActions what this caller may do now: {@code claim}, {@code unclaim}, {@code complete}; computed per
 *     caller at read time and never stored with an idempotent answer
 */
public record TaskView(String id, String instanceId, String definitionKey, int definitionVersion, String businessKey,
        String nodeId, String name, String assignee, boolean fourEyes, String status, long lockVersion,
        String claimedBy, Instant claimedAt, Instant createdAt, String completedBy, Instant completedAt,
        Set<String> allowedActions) {

    public TaskView {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(status, "status must not be null");
        allowedActions = allowedActions == null ? Set.of() : Set.copyOf(allowedActions);
    }

    /**
     * @param actions the caller's actions
     * @return a copy with them
     */
    public TaskView withAllowedActions(Set<String> actions) {
        return new TaskView(id, instanceId, definitionKey, definitionVersion, businessKey, nodeId, name, assignee,
                fourEyes, status, lockVersion, claimedBy, claimedAt, createdAt, completedBy, completedAt, actions);
    }
}
