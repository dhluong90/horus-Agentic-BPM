package io.github.dhluong90.bpm.process.internal.model.di;

import java.util.List;

/**
 * BPMNDI {@code BPMNDiagram}. Stub of the contract; the DI stream completes it.
 *
 * @param id id
 * @param name name
 * @param plane plane
 * @param labelStyles labelStyles
 */
public record BpmnDiagram(
        String id,
        String name,
        BpmnPlane plane,
        List<BpmnLabelStyle> labelStyles) {

    public BpmnDiagram {
        labelStyles = labelStyles == null ? List.of() : List.copyOf(labelStyles);
    }
}
