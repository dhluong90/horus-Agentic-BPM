package io.github.dhluong90.bpm.process.api;

import java.util.List;
import java.util.Objects;

/**
 * What changed between two stored versions of one definition key (PRE-025, PRE-176, DESIGN section 5.3): the steps
 * and the links added, removed and changed. Computed by the engine from the stored, hash-checked definitions, never
 * from a document a client sent. Layout is never part of it, and it carries no XML. Every list is sorted, so the same
 * two versions always give the same diff.
 *
 * @param definitionKey the definition key
 * @param base the version compared from
 * @param target the version compared to
 * @param nodesAdded steps only the target has, sorted by id
 * @param nodesRemoved steps only the base has, sorted by id
 * @param nodesChanged one entry per changed value of a step both versions have, sorted by id then path
 * @param edgesAdded links only the target has, sorted by from, to and kind
 * @param edgesRemoved links only the base has, sorted by from, to and kind
 * @param truncated true when more than {@link #MAX_CHANGES} changed values were found; the rest are not listed
 */
public record GraphDiff(String definitionKey, Side base, Side target, List<Node> nodesAdded, List<Node> nodesRemoved,
        List<NodeChange> nodesChanged, List<Edge> edgesAdded, List<Edge> edgesRemoved, boolean truncated) {

    /** Most changed values listed; a definition holds at most 500 steps and 1 MB, so this bounds the answer. */
    public static final int MAX_CHANGES = 1000;

    public GraphDiff {
        Objects.requireNonNull(definitionKey, "definitionKey");
        Objects.requireNonNull(base, "base");
        Objects.requireNonNull(target, "target");
        nodesAdded = List.copyOf(nodesAdded);
        nodesRemoved = List.copyOf(nodesRemoved);
        nodesChanged = List.copyOf(nodesChanged);
        edgesAdded = List.copyOf(edgesAdded);
        edgesRemoved = List.copyOf(edgesRemoved);
    }

    /**
     * The hash an approval of the target may name after a reviewer read this diff (PRE-177, PD-139): the target's
     * {@code definitionHash} when the change list is complete, and null when it is {@link #truncated()}, because a
     * change list cut off at its limit is not the content anybody reviewed.
     *
     * @return the target's definition hash, or null when truncated
     */
    public String approvableHash() {
        return truncated ? null : target.definitionHash();
    }

    /** @return true when the two versions have the same steps, values and links */
    public boolean identical() {
        return nodesAdded.isEmpty() && nodesRemoved.isEmpty() && nodesChanged.isEmpty() && edgesAdded.isEmpty()
                && edgesRemoved.isEmpty();
    }

    /**
     * One side of the comparison.
     *
     * @param version the version number
     * @param status its lifecycle status
     * @param definitionHash its definition hash
     */
    public record Side(int version, String status, String definitionHash) {
    }

    /**
     * A step.
     *
     * @param id the BPMN id of the step
     * @param name its readable name, or null when it has none
     * @param type its step type in the canonical form, for example {@code userTask}
     */
    public record Node(String id, String name, String type) {

        public Node {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(type, "type");
        }
    }

    /**
     * One changed value of a step both versions have.
     *
     * @param id the BPMN id of the step
     * @param name its readable name in the target
     * @param type its step type in the target
     * @param path the JSON Pointer of the value inside the step's canonical form, for example {@code /assignee/role}
     * @param from the value in the base, or null when the base has none
     * @param to the value in the target, or null when the target has none
     */
    public record NodeChange(String id, String name, String type, String path, Object from, Object to) {

        public NodeChange {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(path, "path");
        }
    }

    /**
     * A link between two steps, matched by {@code (from, to, kind)}.
     *
     * @param id the BPMN id of the sequence flow in its version
     * @param from the BPMN id of the source step
     * @param fromName the readable name of the source step, or null
     * @param to the BPMN id of the target step
     * @param toName the readable name of the target step, or null
     * @param kind {@code next}, {@code branch[i]} (the i-th conditional branch of an exclusive gateway),
     *     {@code default} or {@code fork}
     */
    public record Edge(String id, String from, String fromName, String to, String toName, String kind) {

        public Edge {
            Objects.requireNonNull(from, "from");
            Objects.requireNonNull(to, "to");
            Objects.requireNonNull(kind, "kind");
        }
    }
}
