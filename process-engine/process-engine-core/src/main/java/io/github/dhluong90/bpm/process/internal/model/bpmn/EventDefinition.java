package io.github.dhluong90.bpm.process.internal.model.bpmn;

/**
 * BPMN {@code tEventDefinition}; a root element so definitions can be shared by reference.
 */
public sealed interface EventDefinition extends RootElement
        permits TimerEventDefinition, MessageEventDefinition, SignalEventDefinition, ErrorEventDefinition, EscalationEventDefinition, CompensateEventDefinition, ConditionalEventDefinition, LinkEventDefinition, CancelEventDefinition, TerminateEventDefinition {
}
