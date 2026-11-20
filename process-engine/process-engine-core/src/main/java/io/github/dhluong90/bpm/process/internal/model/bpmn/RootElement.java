package io.github.dhluong90.bpm.process.internal.model.bpmn;

/**
 * BPMN {@code tRootElement}: elements directly under {@code definitions}.
 */
public sealed interface RootElement extends BaseElement
        permits Process, Collaboration, Message, Signal, BpmnError, Escalation, ItemDefinition, DataStore, Category, EventDefinition {
}
