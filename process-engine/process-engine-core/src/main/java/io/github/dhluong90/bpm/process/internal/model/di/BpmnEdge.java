package io.github.dhluong90.bpm.process.internal.model.di;

import java.util.List;

/**
 * BPMNDI {@code BPMNEdge}.
 *
 * @param id id
 * @param bpmnElement bpmnElement
 * @param waypoints waypoints
 * @param sourceElement sourceElement
 * @param targetElement targetElement
 * @param messageVisibleKind messageVisibleKind
 * @param label label
 */
public record BpmnEdge(
        String id,
        String bpmnElement,
        List<Point> waypoints,
        String sourceElement,
        String targetElement,
        String messageVisibleKind,
        BpmnLabel label) implements DiagramElement {

    public BpmnEdge {
        waypoints = waypoints == null ? List.of() : List.copyOf(waypoints);
    }
}
