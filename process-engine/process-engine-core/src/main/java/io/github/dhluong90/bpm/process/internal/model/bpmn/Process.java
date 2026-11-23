package io.github.dhluong90.bpm.process.internal.model.bpmn;

import java.util.List;
import java.util.Objects;

/**
 * BPMN {@code process}.
 *
 * @param id id
 * @param name name
 * @param processType processType
 * @param isExecutable isExecutable
 * @param isClosed isClosed
 * @param flowElements flowElements
 * @param artifacts artifacts
 * @param laneSets laneSets
 * @param info info
 * @param collaboration the collaboration this process is a pool of (#497), or null for a process alone in its file
 */
public record Process(
        String id,
        String name,
        ProcessType processType,
        boolean isExecutable,
        boolean isClosed,
        List<FlowElement> flowElements,
        List<Artifact> artifacts,
        List<LaneSet> laneSets,
        ElementInfo info,
        Collaboration collaboration) implements RootElement, FlowElementsContainer {

    /** A process that stands alone in its file. */
    public Process(String id, String name, ProcessType processType, boolean isExecutable, boolean isClosed,
            List<FlowElement> flowElements, List<Artifact> artifacts, List<LaneSet> laneSets, ElementInfo info) {
        this(id, name, processType, isExecutable, isClosed, flowElements, artifacts, laneSets, info, null);
    }

    /** @param c the collaboration of the file @return a copy that is a pool of it */
    public Process withCollaboration(Collaboration c) {
        return new Process(id, name, processType, isExecutable, isClosed, flowElements, artifacts, laneSets, info, c);
    }

    /** @param lanes the lane sets of the process @return a copy that carries them */
    public Process withLaneSets(List<LaneSet> lanes) {
        return new Process(id, name, processType, isExecutable, isClosed, flowElements, artifacts, lanes, info,
                collaboration);
    }

    public Process {
        Objects.requireNonNull(id, "id must not be null");
        flowElements = flowElements == null ? List.of() : List.copyOf(flowElements);
        artifacts = artifacts == null ? List.of() : List.copyOf(artifacts);
        laneSets = laneSets == null ? List.of() : List.copyOf(laneSets);
        info = info == null ? ElementInfo.empty() : info;
    }
}
