package io.github.dhluong90.bpm.process.internal.model.di;

/**
 * BPMNDI {@code BPMNShape}.
 *
 * @param id id
 * @param bpmnElement bpmnElement
 * @param bounds bounds
 * @param isHorizontal isHorizontal
 * @param isExpanded isExpanded
 * @param isMarkerVisible isMarkerVisible
 * @param isMessageVisible isMessageVisible
 * @param participantBandKind participantBandKind
 * @param label label
 */
public record BpmnShape(
        String id,
        String bpmnElement,
        Bounds bounds,
        Boolean isHorizontal,
        Boolean isExpanded,
        Boolean isMarkerVisible,
        Boolean isMessageVisible,
        String participantBandKind,
        BpmnLabel label) implements DiagramElement {
}
