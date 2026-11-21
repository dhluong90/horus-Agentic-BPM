package io.github.dhluong90.bpm.process.api;

import java.time.Instant;
import java.util.Objects;

/**
 * A task as the task list shows it: no variables and no review context, so nothing masked is exposed. Read one task
 * with {@link TaskService#task(String)} for those.
 *
 * @param id task id
 * @param instanceId the instance
 * @param definitionKey the definition key of the instance
 * @param definitionVersion the pinned version of the instance
 * @param businessKey the instance business key, or null
 * @param nodeId the {@code userTask} id
 * @param name the step name, or null
 * @param assignee {@code user:<id>}, {@code role:<role>} or {@code group:<group>}
 * @param status {@code OPEN} or {@code CLAIMED}
 * @param lockVersion optimistic lock version
 * @param claimedBy the claimant while {@code CLAIMED}, or null
 * @param createdAt when the task was created
 * @param dueAt when the task is due, or null for a task without an SLA
 */
public record TaskSummary(String id, String instanceId, String definitionKey, int definitionVersion,
        String businessKey, String nodeId, String name, String assignee, String status, long lockVersion,
        String claimedBy, Instant createdAt, Instant dueAt) {

    public TaskSummary {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(status, "status must not be null");
    }
}
