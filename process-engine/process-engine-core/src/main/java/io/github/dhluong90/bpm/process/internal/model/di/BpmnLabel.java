package io.github.dhluong90.bpm.process.internal.model.di;

/**
 * BPMNDI {@code BPMNLabel}.
 *
 * @param id id
 * @param bounds bounds
 * @param labelStyle labelStyle
 */
public record BpmnLabel(
        String id,
        Bounds bounds,
        String labelStyle) {
}
