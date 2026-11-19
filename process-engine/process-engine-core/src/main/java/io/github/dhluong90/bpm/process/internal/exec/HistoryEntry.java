package io.github.dhluong90.bpm.process.internal.exec;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * One audit record of a transition, written by the shell to {@code pre_history} in the same transaction.
 *
 * @param type for example {@code INSTANCE_STARTED}, {@code NODE_ENTERED}, {@code NODE_COMPLETED},
 *     {@code TASK_COMPLETED}, {@code INSTANCE_ENDED}
 * @param nodeId the flow node, or null
 * @param tokenId the token, or null
 * @param attemptId the activity execution id, or null
 * @param actor the identity that caused it, or null for engine steps
 * @param occurredAt the kernel clock time
 * @param details masked details
 */
public record HistoryEntry(String type, String nodeId, String tokenId, String attemptId, String actor,
        Instant occurredAt, Map<String, Object> details) {

    public HistoryEntry {
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        details = details == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(details));
    }
}
