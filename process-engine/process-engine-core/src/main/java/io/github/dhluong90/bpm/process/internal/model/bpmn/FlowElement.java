package io.github.dhluong90.bpm.process.internal.model.bpmn;

/**
 * BPMN {@code tFlowElement}.
 */
public sealed interface FlowElement extends BaseElement
        permits FlowNode, SequenceFlow, DataObject, DataObjectReference, DataStoreReference {

    /** @return the name, or null */
    String name();
}
