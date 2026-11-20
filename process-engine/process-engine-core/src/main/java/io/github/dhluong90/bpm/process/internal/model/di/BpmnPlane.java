package io.github.dhluong90.bpm.process.internal.model.di;

import java.util.List;

/**
 * BPMNDI {@code BPMNPlane}.
 *
 * @param id id
 * @param bpmnElement bpmnElement
 * @param elements elements
 */
public record BpmnPlane(
        String id,
        String bpmnElement,
        List<DiagramElement> elements) {

    public BpmnPlane {
        elements = elements == null ? List.of() : List.copyOf(elements);
    }
}
