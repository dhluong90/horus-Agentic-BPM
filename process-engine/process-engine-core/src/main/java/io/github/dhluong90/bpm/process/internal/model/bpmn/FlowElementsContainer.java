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
}
