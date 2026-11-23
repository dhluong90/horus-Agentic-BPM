package io.github.dhluong90.bpm.process.internal.model.bpmn;

import java.util.List;

/** A process or subprocess: holds flow elements, artifacts and lanes. */
public interface FlowElementsContainer {

    /** @return flow nodes, sequence flows and data elements in document order */
    List<FlowElement> flowElements();

    /** @return text annotations, associations and groups */
    List<Artifact> artifacts();

    /** @return lane sets */
    List<LaneSet> laneSets();

    /**
     * Every flow element of this container and of the containers nested in it (a {@code saga} is a
     * {@code transaction}), depth first in document order: the container element comes before its children.
     *
     * @return all flow elements
     */
    default List<FlowElement> allFlowElements() {
        List<FlowElement> out = new java.util.ArrayList<>();
        for (FlowElement element : flowElements()) {
            out.add(element);
            if (element instanceof FlowElementsContainer nested) {
                out.addAll(nested.allFlowElements());
            }
        }
        return java.util.Collections.unmodifiableList(out);
    }

    /**
     * Every artifact of this container and of the containers nested in it, in document order (#488: the associations
     * that tie a compensation boundary event to its compensation activity).
     *
     * @return all artifacts
     */
    default List<Artifact> allArtifacts() {
        List<Artifact> out = new java.util.ArrayList<>(artifacts());
        for (FlowElement element : flowElements()) {
            if (element instanceof FlowElementsContainer nested) {
                out.addAll(nested.allArtifacts());
            }
        }
        return java.util.Collections.unmodifiableList(out);
    }
}
