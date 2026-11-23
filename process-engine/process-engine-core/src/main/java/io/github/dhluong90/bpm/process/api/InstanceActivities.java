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
 * @param compensations one entry per saga scope whose undo started (#80, PRE-203), in the order they started, each
 *     with where its undo stands; empty when no compensation ran. Never cut by the entry limit
 */
public record InstanceActivities(String instanceId, Instant startedAt, Instant endedAt, List<Node> nodes,
        List<Entry> entries, boolean hasMore, List<Compensation> compensations) {

    public InstanceActivities {
        Objects.requireNonNull(instanceId, "instanceId");
        Objects.requireNonNull(startedAt, "startedAt");
        nodes = List.copyOf(nodes);
        entries = List.copyOf(entries);
        compensations = List.copyOf(compensations);
    }

    /**
     * An instance with no compensation.
     *
     * @param instanceId the instance
     * @param startedAt when it started
     * @param endedAt when it ended, or null
     * @param nodes one entry per step
     * @param entries the history entries
     * @param hasMore whether there are more entries
     */
    public InstanceActivities(String instanceId, Instant startedAt, Instant endedAt, List<Node> nodes,
            List<Entry> entries, boolean hasMore) {
        this(instanceId, startedAt, endedAt, nodes, entries, hasMore, List.of());
    }

    /** Where the undo of one saga scope stands. */
    public enum CompensationState {
        /** The undo steps are still running or waiting to run. */
        IN_PROGRESS,
        /** Every step was undone and the scope ended compensated. */
        UNDONE,
        /** An undo step failed for good or needs a person, and the walk is paused: the scope is not fully undone. */
        NOT_FULLY_UNDONE
    }

    /**
     * The undo of one saga scope.
     *
     * @param scopeTokenId the scope's token
     * @param scope the BPMN id of the scope step, for example {@code disburse}
     * @param state where the undo stands
     * @param startedAt when the undo started
     * @param endedAt when the scope ended compensated, or null
     * @param cause the error code of the forward step that failed, or null
     * @param failingStep the forward step whose failure started the undo, or null
     * @param stepsUndone how many undo steps completed
     * @param blockedStep the step whose undo failed or waits for a person, or null unless NOT_FULLY_UNDONE
     * @param incidentId the open incident of that step, or null
     * @param incidentCode its error code, or null
     */
    public record Compensation(String scopeTokenId, String scope, CompensationState state, Instant startedAt,
            Instant endedAt, String cause, String failingStep, int stepsUndone, String blockedStep, String incidentId,
            String incidentCode) {

        public Compensation {
            Objects.requireNonNull(scopeTokenId, "scopeTokenId");
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(startedAt, "startedAt");
        }
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

    /** What kind of entry this is: a forward step, or part of an undo. */
    public static final String KIND_STEP = "STEP";
    /** An entry of a compensation: its start, one undo step, or its end. */
    public static final String KIND_COMPENSATION = "COMPENSATION";

    /**
     * One history entry.
     *
     * @param id the entry id
     * @param type for example {@code NODE_ENTERED}, {@code NODE_COMPLETED}, {@code TASK_COMPLETED},
     *     {@code COMPENSATION_STEP_COMPLETED}
     * @param nodeId the step, or null for an instance entry
     * @param actor the identity that caused it, or null for engine steps
     * @param occurredAt when
     * @param kind {@link #KIND_STEP}, or {@link #KIND_COMPENSATION} for an entry of an undo (#80)
     * @param compensates for an undo step, the step it undoes (the same as {@code nodeId}); null otherwise
     * @param attemptId the attempt the entry belongs to, or null; for an undo step, the forward attempt it undoes
     * @param scopeTokenId for an entry of an undo, the token of the saga scope; null otherwise
     * @param scope for an entry of an undo, the BPMN id of the saga scope; null otherwise
     * @param attempt for an undo step that ran, the attempt number of its job; null otherwise
     * @param errorCode for an undo step that failed, its error code; null otherwise
     * @param handler for an undo step, the undo handler's name; null otherwise
     * @param cause for the start of an undo, the error code that caused it; null otherwise
     * @param message for a message step ({@code MESSAGE_WAITING}, {@code MESSAGE_RECEIVED}, {@code MESSAGE_SENT}), the
     *     keys {@code name}, {@code correlationKey} (as the host's masker made it) and {@code result}; null otherwise
     * @param instances for the entries of a loop or multi-instance step ({@code MI_*}, #496), the counters when the
     *     entry was written; null otherwise
     * @param routing for a gateway that chose its flows or a join that waits (#494), what it chose or waits for; null
     *     otherwise
     * @param event for {@code BOUNDARY_FIRED} the {@code trigger} (timer, message, signal, condition, error or
     *     escalation), the {@code name} of the message, signal or code, the condition text, and whether it interrupted;
     *     for {@code EVENT_GATEWAY_RESOLVED} the {@code gateway}, the {@code winner} and the {@code cancelled} catches
     *     (#490, #495); never a variable value; null otherwise
     */
    public record Entry(String id, String type, String nodeId, String actor, Instant occurredAt, String kind,
            String compensates, String attemptId, String scopeTokenId, String scope, Integer attempt, String errorCode,
            String handler, String cause, Integer waiters, java.util.Map<String, String> message, Instances instances,
            Routing routing, java.util.Map<String, Object> event) {

        public Entry {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(occurredAt, "occurredAt");
            Objects.requireNonNull(kind, "kind");
            message = message == null ? null : java.util.Collections.unmodifiableMap(
                    new java.util.LinkedHashMap<>(message));
            event = event == null ? null : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(event));
        }

        /** An entry with no event part. */
        public Entry(String id, String type, String nodeId, String actor, Instant occurredAt, String kind,
                String compensates, String attemptId, String scopeTokenId, String scope, Integer attempt,
                String errorCode, String handler, String cause, Integer waiters, java.util.Map<String, String> message) {
            this(id, type, nodeId, actor, occurredAt, kind, compensates, attemptId, scopeTokenId, scope, attempt,
                    errorCode, handler, cause, waiters, message, null, null, null);
        }

        /** An entry with no count of woken waiters, no message part and no loop counters and no routing. */
        public Entry(String id, String type, String nodeId, String actor, Instant occurredAt, String kind,
                String compensates, String attemptId, String scopeTokenId, String scope, Integer attempt,
                String errorCode, String handler, String cause) {
            this(id, type, nodeId, actor, occurredAt, kind, compensates, attemptId, scopeTokenId, scope, attempt,
                    errorCode, handler, cause, null, null, null, null, null);
        }

        /**
         * An entry that is not part of an undo.
         *
         * @param id the entry id
         * @param type the history type
         * @param nodeId the step, or null
         * @param actor the identity, or null
         * @param occurredAt when
         */
        public Entry(String id, String type, String nodeId, String actor, Instant occurredAt) {
            this(id, type, nodeId, actor, occurredAt, KIND_STEP, null, null, null, null, null, null, null, null, null, null, null, null,
                    null);
        }

        /**
         * The throw of a signal (#492).
         *
         * @param id the entry id
         * @param nodeId the throwing event
         * @param actor the identity
         * @param occurredAt when
         * @param waiters how many waiting subscriptions of the tenant the broadcast woke
         * @return the entry
         */
        public static Entry signalThrown(String id, String nodeId, String actor, Instant occurredAt, Integer waiters) {
            return new Entry(id, "SIGNAL_THROWN", nodeId, actor, occurredAt, KIND_STEP, null, null, null, null, null,
                    null, null, null, waiters, null, null, null, null);
        }

        /** @return the same entry with the routing of a gateway or join */
        public Entry withRouting(Routing newRouting) {
            return new Entry(id, type, nodeId, actor, occurredAt, kind, compensates, attemptId, scopeTokenId, scope,
                    attempt, errorCode, handler, cause, waiters, message, instances, newRouting, event);
        }

        /** @return the same entry in the scope named, for an entry of an undo */
        public Entry inScope(String scopeNodeId) {
            return new Entry(id, type, nodeId, actor, occurredAt, kind, compensates, attemptId, scopeTokenId,
                    scopeNodeId, attempt, errorCode, handler, cause, waiters, message, instances, routing, event);
        }

        /**
         * A message step's entry.
         *
         * @param message the {@code name}, {@code correlationKey} and {@code result}
         * @return the same entry with its message part
         */
        public Entry withMessage(java.util.Map<String, String> message) {
            return new Entry(id, type, nodeId, actor, occurredAt, kind, compensates, attemptId, scopeTokenId, scope,
                    attempt, errorCode, handler, cause, waiters, message, instances, routing, event);
        }

        /**
         * @param counters the counters of a loop or multi-instance step
         * @return the same entry carrying them
         */
        public Entry withInstances(Instances counters) {
            return new Entry(id, type, nodeId, actor, occurredAt, kind, compensates, attemptId, scopeTokenId, scope,
                    attempt, errorCode, handler, cause, waiters, message, counters, routing, event);
        }

        /**
         * A boundary event's or an event-based gateway's entry (#490, #495).
         *
         * @param newEvent the event part
         * @return the same entry with it
         */
        public Entry withEvent(java.util.Map<String, Object> newEvent) {
            return new Entry(id, type, nodeId, actor, occurredAt, kind, compensates, attemptId, scopeTokenId, scope,
                    attempt, errorCode, handler, cause, waiters, message, instances, routing, newEvent);
        }

        /** @return true for the history types the undo of a saga writes */
        public static boolean isCompensationType(String type) {
            return type.startsWith("COMPENSATION_") || "COMPENSATED".equals(type);
        }
    }

    /**
     * The counters of a loop or multi-instance step when an entry was written (#496), so a timeline reads "3 of 5
     * done" without computing it.
     *
     * @param loopCounter the repetition the entry is about, counted from 1; null for an entry about the whole step
     * @param nrOfInstances how many repetitions the step has; null for a standard loop, which has no fixed number
     * @param nrOfActiveInstances how many are running
     * @param nrOfCompletedInstances how many have ended
     */
    public record Instances(Integer loopCounter, Integer nrOfInstances, Integer nrOfActiveInstances,
            Integer nrOfCompletedInstances) {
    }

    /**
     * What a gateway chose or why a join waits (#494).
     *
     * @param flows the sequence flow ids a split took; empty for a join
     * @param reason {@code conditions} when every taken flow had a true condition, {@code default} when the default
     *     flow was taken because none had; null for a join
     * @param waitingFor the ids of the steps a waiting join still expects a token from; empty for a split
     */
    public record Routing(List<String> flows, String reason, List<String> waitingFor) {

        public Routing {
            flows = flows == null ? List.of() : List.copyOf(flows);
            waitingFor = waitingFor == null ? List.of() : List.copyOf(waitingFor);
        }
    }
}
