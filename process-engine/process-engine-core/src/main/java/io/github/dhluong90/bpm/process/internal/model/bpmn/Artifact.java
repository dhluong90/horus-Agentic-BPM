package io.github.dhluong90.bpm.process.internal.model.bpmn;

/**
 * BPMN {@code tArtifact}.
 */
public sealed interface Artifact extends BaseElement
        permits TextAnnotation, Association, Group {
}
