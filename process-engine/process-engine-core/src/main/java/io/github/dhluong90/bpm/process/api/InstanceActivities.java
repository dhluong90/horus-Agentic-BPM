package io.github.dhluong90.bpm.process.api;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * What happened at each step of one instance (PRE-027), read only from the history partitions between the instance's
 * start and its end. Carries no variable or input value, so a masked input can never appear in it.
 *
 * @param instanceId the instance
 * @param startedAt when it started
 * @param endedAt when it ended, or null while it runs
 * @param nodes one entry per step the instance entered, in the order it first entered them
 * @param entries the history entries in time order, at most the requested limit
 * @param hasMore whether the instance has more entries than {@code entries} holds
 */
public record InstanceActivities(String instanceId, Instant startedAt, Instant endedAt, List<Node> nodes,
        List<Entry> entries, boolean hasMore) {

    public InstanceActivities {
        Objects.requireNonNull(instanceId, "instanceId");
        Objects.requireNonNull(startedAt, "startedAt");
        nodes = List.copyOf(nodes);
        entries = List.copyOf(entries);
    }

    /** Where a step stands: entered and not yet left, left, or holding an open incident. */
    public enum State {
        /** Entered and not left since. */
        ACTIVE,
        /** Left after its last entry. */
        COMPLETED,
        /** Holds an open incident. */
        INCIDENT
    }

    /**
     * The activity of one step.
     *
     * @param nodeId the step
     * @param state where it stands
     * @param enteredAt when it was first entered
     * @param completedAt when it was last left, or null
     * @param duration from the first entry to the last completion, or null while it has not completed
     * @param attempts how many times it ran: its entries, or its distinct attempts when a remote step retried
     * @param actor the last identity that acted on it, or null for engine steps
     * @param incidentCode the error code of its open incident, or null
     * @param incidentAt when that incident opened, or null
     */
    public record Node(String nodeId, State state, Instant enteredAt, Instant completedAt, Duration duration,
            int attempts, String actor, String incidentCode, Instant incidentAt) {

        public Node {
            Objects.requireNonNull(nodeId, "nodeId");
            Objects.requireNonNull(state, "state");
        }
    }

    /**
     * One history entry.
     *
     * @param id the entry id
     * @param type for example {@code NODE_ENTERED}, {@code NODE_COMPLETED}, {@code TASK_COMPLETED}
     * @param nodeId the step, or null for an instance entry
     * @param actor the identity that caused it, or null for engine steps
     * @param occurredAt when
     */
    public record Entry(String id, String type, String nodeId, String actor, Instant occurredAt) {

        public Entry {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(occurredAt, "occurredAt");
        }
    }
}
