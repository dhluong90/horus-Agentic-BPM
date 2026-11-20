package io.github.dhluong90.bpm.process.internal.model.bpmn;

import java.util.List;

/**
 * BPMN {@code lane}.
 *
 * @param id id
 * @param name name
 * @param partitionElementRef partitionElementRef
 * @param flowNodeRefs flowNodeRefs
 * @param childLaneSet childLaneSet
 * @param info info
 */
public record Lane(
        String id,
        String name,
        String partitionElementRef,
        List<String> flowNodeRefs,
        LaneSet childLaneSet,
        ElementInfo info) implements BaseElement {

    public Lane {
        flowNodeRefs = flowNodeRefs == null ? List.of() : List.copyOf(flowNodeRefs);
        info = info == null ? ElementInfo.empty() : info;
    }
}
