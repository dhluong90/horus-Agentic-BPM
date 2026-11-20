package io.github.dhluong90.bpm.process.api;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
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
 * @param review for the review task of a refused agent answer (#52): {@code code}, {@code reasons}, {@code ruleIds},
 *     {@code guardrailDecisionId}, the {@code inputs} the agent saw and its {@code output}, masked by the output
 *     schema's masking policy, with {@code outputFields}; null for every other task
 */
public record TaskView(String id, String instanceId, String definitionKey, int definitionVersion, String businessKey,
        String nodeId, String name, String assignee, boolean fourEyes, String status, long lockVersion,
        String claimedBy, Instant claimedAt, Instant createdAt, String completedBy, Instant completedAt,
        Set<String> allowedActions, Map<String, Object> review) {

    public TaskView {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(status, "status must not be null");
        allowedActions = allowedActions == null ? Set.of() : Set.copyOf(allowedActions);
        review = review == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(review));
    }

    /**
     * A task without review context, as before #52.
     *
     * @param id task id
     * @param instanceId the instance
     * @param definitionKey the definition key of the instance
     * @param definitionVersion the pinned version of the instance
     * @param businessKey the instance business key, or null
     * @param nodeId the {@code userTask} id
     * @param name the step name, or null
     * @param assignee the assignee
     * @param fourEyes whether four eyes apply
     * @param status the status
     * @param lockVersion optimistic lock version
     * @param claimedBy the claimant, or null
     * @param claimedAt when it was claimed, or null
     * @param createdAt when the task was created
     * @param completedBy who completed it, or null
     * @param completedAt when it was completed, or null
     * @param allowedActions what this caller may do now
     */
    public TaskView(String id, String instanceId, String definitionKey, int definitionVersion, String businessKey,
            String nodeId, String name, String assignee, boolean fourEyes, String status, long lockVersion,
            String claimedBy, Instant claimedAt, Instant createdAt, String completedBy, Instant completedAt,
            Set<String> allowedActions) {
        this(id, instanceId, definitionKey, definitionVersion, businessKey, nodeId, name, assignee, fourEyes, status,
                lockVersion, claimedBy, claimedAt, createdAt, completedBy, completedAt, allowedActions, null);
    }

    /**
     * @param actions the caller's actions
     * @return a copy with them
     */
    public TaskView withAllowedActions(Set<String> actions) {
        return new TaskView(id, instanceId, definitionKey, definitionVersion, businessKey, nodeId, name, assignee,
                fourEyes, status, lockVersion, claimedBy, claimedAt, createdAt, completedBy, completedAt, actions,
                review);
    }
}
