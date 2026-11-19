package io.github.dhluong90.bpm.process.internal.model.bpmn;

/**
 * BPMN {@code import}.
 *
 * @param namespace namespace
 * @param location location
 * @param importType importType
 */
public record Import(
        String namespace,
        String location,
        String importType) {
}
