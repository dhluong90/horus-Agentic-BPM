package io.github.dhluong90.bpm.process.internal.model.bpmn;

/**
 * BPMN {@code tCatchEvent}.
 */
public sealed interface CatchEvent extends Event
        permits StartEvent, IntermediateCatchEvent, BoundaryEvent {

    /** @return whether all definitions must trigger (parallel multiple) */
    boolean parallelMultiple();
}
