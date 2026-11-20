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
        ElementInfo info) implements RootElement, FlowElementsContainer {

    public Process {
        Objects.requireNonNull(id, "id must not be null");
        flowElements = flowElements == null ? List.of() : List.copyOf(flowElements);
        artifacts = artifacts == null ? List.of() : List.copyOf(artifacts);
        laneSets = laneSets == null ? List.of() : List.copyOf(laneSets);
        info = info == null ? ElementInfo.empty() : info;
    }
}
