package io.github.dhluong90.bpm.process.api;

import java.time.Duration;
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
 * @param slaState {@code NONE} without a due time, {@code ON_TIME}, {@code DUE_SOON} within the configured window
 *     before the due time, or {@code BREACHED} once it has passed (PD-111)
 * @param overdueBy clock time since {@code dueAt} in whole minutes while {@code BREACHED}, else null
 */
public record TaskSummary(String id, String instanceId, String definitionKey, int definitionVersion,
        String businessKey, String nodeId, String name, String assignee, String status, long lockVersion,
        String claimedBy, Instant createdAt, Instant dueAt, String slaState, Duration overdueBy) {

    public TaskSummary {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(status, "status must not be null");
    }
}
