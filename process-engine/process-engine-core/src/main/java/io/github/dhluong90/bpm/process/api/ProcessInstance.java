package io.github.dhluong90.bpm.process.api;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A process instance with its execution tree and open tasks, as read in one transaction.
 *
 * @param id instance id (ULID)
 * @param definitionKey the definition key
 * @param definitionVersion the pinned version number
 * @param businessKey the business key, or null
 * @param status {@code RUNNING}, {@code COMPLETED}, {@code CANCELLED}, {@code COMPENSATING} or {@code FAILED}
 * @param variables the instance variables, unmasked in this release (deliberate gap: masking of declared fields,
 *     DESIGN section 9, comes with the variable declarations)
 * @param tokens the execution tree: the root scope token and its flow tokens (ADR-0020)
 * @param tasks open and completed user tasks, oldest first
 * @param lockVersion optimistic lock version of the instance
 * @param startedBy the identity that started it
 * @param startedAt start time
 * @param endedAt end time, or null while running
 */
public record ProcessInstance(String id, String definitionKey, int definitionVersion, String businessKey,
        String status, Map<String, Object> variables, List<Token> tokens, List<Task> tasks, long lockVersion,
        String startedBy, Instant startedAt, Instant endedAt) {

    public ProcessInstance {
        Objects.requireNonNull(id, "id must not be null");
        variables = Collections.unmodifiableMap(new LinkedHashMap<>(variables));
        tokens = List.copyOf(tokens);
        tasks = List.copyOf(tasks);
    }

    /**
     * A position in the execution tree.
     *
     * @param id token id
     * @param kind {@code SCOPE} or {@code FLOW}
     * @param scopeTokenId enclosing scope token, null for the root
     * @param nodeId the step the token is on; the definition key for the root scope
     * @param status token status, for example {@code ACTIVE}, {@code WAITING_USER}, {@code COMPLETED}
     */
    public record Token(String id, String kind, String scopeTokenId, String nodeId, String status) {
    }

    /**
     * A user task.
     *
     * @param id task id
     * @param nodeId the {@code userTask} id
     * @param name the step name, or null
     * @param assignee {@code user:<id>}, {@code role:<role>} or {@code group:<group>}
     * @param status {@code OPEN}, {@code CLAIMED}, {@code COMPLETED} or {@code CANCELLED}
     * @param lockVersion optimistic lock version, sent back as {@code If-Match}
     * @param claimedBy the claimant while {@code CLAIMED}, or null
     * @param claimedAt when it was claimed, or null
     * @param completedBy who completed it, or null
     */
    public record Task(String id, String nodeId, String name, String assignee, String status, long lockVersion,
            String claimedBy, Instant claimedAt, String completedBy) {
    }

    /**
     * @return the tasks still to be done: open or claimed
     */
    public List<Task> openTasks() {
        return tasks.stream().filter(t -> "OPEN".equals(t.status()) || "CLAIMED".equals(t.status())).toList();
    }
}
