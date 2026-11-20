package io.github.dhluong90.bpm.process.internal.model.di;

/**
 * A shape or an edge of a plane.
 */
public sealed interface DiagramElement
        permits BpmnShape, BpmnEdge {
}
