package io.github.dhluong90.bpm.process.internal.model.bpmn;

import java.util.List;
import java.util.Objects;

/**
 * BPMN {@code subProcess}.
 *
 * @param id id
 * @param name name
 * @param incoming incoming
 * @param outgoing outgoing
 * @param activity activity
 * @param triggeredByEvent triggeredByEvent
 * @param flowElements flowElements
 * @param artifacts artifacts
 * @param laneSets laneSets
 * @param info info
 */
public record SubProcess(
        String id,
        String name,
        List<String> incoming,
        List<String> outgoing,
        ActivityAttributes activity,
        boolean triggeredByEvent,
        List<FlowElement> flowElements,
        List<Artifact> artifacts,
        List<LaneSet> laneSets,
        ElementInfo info) implements Activity, FlowElementsContainer {

    public SubProcess {
        Objects.requireNonNull(id, "id must not be null");
        incoming = incoming == null ? List.of() : List.copyOf(incoming);
        outgoing = outgoing == null ? List.of() : List.copyOf(outgoing);
        activity = activity == null ? ActivityAttributes.defaults() : activity;
        flowElements = flowElements == null ? List.of() : List.copyOf(flowElements);
        artifacts = artifacts == null ? List.of() : List.copyOf(artifacts);
        laneSets = laneSets == null ? List.of() : List.copyOf(laneSets);
        info = info == null ? ElementInfo.empty() : info;
    }
}
