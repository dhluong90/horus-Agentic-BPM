package io.github.dhluong90.bpm.process.internal.model.bpmn;

/**
 * BPMN {@code tThrowEvent}.
 */
public sealed interface ThrowEvent extends Event
        permits EndEvent, IntermediateThrowEvent {
}
