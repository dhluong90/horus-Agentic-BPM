package io.github.dhluong90.bpm.process.internal.exec.behavior.scope;

import io.github.dhluong90.bpm.process.internal.exec.EventDefinitionKey;
import io.github.dhluong90.bpm.process.internal.exec.ExecutionContext;
import io.github.dhluong90.bpm.process.internal.exec.ExecutionException;
import io.github.dhluong90.bpm.process.internal.exec.FlowNodeBehavior;
import io.github.dhluong90.bpm.process.internal.exec.Token;
import io.github.dhluong90.bpm.process.internal.model.bpmn.BoundaryEvent;
import io.github.dhluong90.bpm.process.internal.model.bpmn.CancelEventDefinition;
import io.github.dhluong90.bpm.process.internal.model.bpmn.CompensateEventDefinition;
import io.github.dhluong90.bpm.process.internal.model.bpmn.EventDefinition;

/**
 * The compensation boundary event and the cancel boundary event (#488). Neither holds a token. A compensation boundary
 * marks its activity as undone by the compensation activity its association points at; the engine reads that when the
 * activity completes. A cancel boundary, on a transaction, is where the flow leaves when a cancel end event inside
 * the transaction has compensated it. A token never enters either by a flow.
 */
final class BoundaryCompensationBehavior implements FlowNodeBehavior<BoundaryEvent> {

    private final Class<? extends EventDefinition> definition;

    private BoundaryCompensationBehavior(Class<? extends EventDefinition> definition) {
        this.definition = definition;
    }

    static BoundaryCompensationBehavior forCompensation() {
        return new BoundaryCompensationBehavior(CompensateEventDefinition.class);
    }

    static BoundaryCompensationBehavior forCancel() {
        return new BoundaryCompensationBehavior(CancelEventDefinition.class);
    }

    @Override
    public Class<BoundaryEvent> nodeType() {
        return BoundaryEvent.class;
    }

    @Override
    public EventDefinitionKey eventDefinition() {
        return EventDefinitionKey.of(definition);
    }

    @Override
    public void enter(BoundaryEvent node, Token token, ExecutionContext ctx) {
        throw new ExecutionException("INVALID_STATE", node.id(), "A token never enters a boundary event by a flow");
    }
}
