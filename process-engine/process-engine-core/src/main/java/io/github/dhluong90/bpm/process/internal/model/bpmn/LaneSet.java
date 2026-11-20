package io.github.dhluong90.bpm.process.internal.model.bpmn;

import java.util.List;

/**
 * BPMN {@code laneSet}.
 *
 * @param id id
 * @param name name
 * @param lanes lanes
 * @param info info
 */
public record LaneSet(
        String id,
        String name,
        List<Lane> lanes,
        ElementInfo info) implements BaseElement {

    public LaneSet {
        lanes = lanes == null ? List.of() : List.copyOf(lanes);
        info = info == null ? ElementInfo.empty() : info;
    }
}
