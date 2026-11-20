package io.github.dhluong90.bpm.process.internal.model.bpmn;

import java.util.List;

/**
 * BPMN {@code tFlowNode}.
 */
public sealed interface FlowNode extends FlowElement
        permits Activity, Gateway, Event {

    /** @return ids of incoming sequence flows as written */
    List<String> incoming();

    /** @return ids of outgoing sequence flows as written */
    List<String> outgoing();
}
