package io.github.dhluong90.bpm.process.internal.model.bpmn;

import java.util.List;

/**
 * BPMN {@code tEvent}.
 */
public sealed interface Event extends FlowNode
        permits CatchEvent, ThrowEvent {

    /** @return inline event definitions; empty for a none event */
    List<EventDefinition> eventDefinitions();

    /** @return ids of shared event definitions */
    List<String> eventDefinitionRefs();

    /** @return data inputs, outputs and associations */
    EventData data();
}
