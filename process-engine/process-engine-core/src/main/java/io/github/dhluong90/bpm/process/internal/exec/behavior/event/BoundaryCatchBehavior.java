package io.github.dhluong90.bpm.process.internal.exec.behavior.event;

import io.github.dhluong90.bpm.process.internal.exec.EventDefinitionKey;
import io.github.dhluong90.bpm.process.internal.exec.ExecutionContext;
import io.github.dhluong90.bpm.process.internal.exec.ExecutionException;
import io.github.dhluong90.bpm.process.internal.exec.FlowNodeBehavior;
import io.github.dhluong90.bpm.process.internal.exec.Token;
import io.github.dhluong90.bpm.process.internal.model.bpmn.BoundaryEvent;
import io.github.dhluong90.bpm.process.internal.model.bpmn.ErrorEventDefinition;
import io.github.dhluong90.bpm.process.internal.model.bpmn.EscalationEventDefinition;
import io.github.dhluong90.bpm.process.internal.model.bpmn.EventDefinition;

/**
 * Error and escalation boundary events (#489, #493). They hold no token: nothing waits on them. They are found when an
 * error or an escalation is thrown, by {@link ExecutionContext#throwError} and {@link ExecutionContext#throwEscalation},
 * which cancel the host (interrupting) or leave it open (non-interrupting escalation) and run the boundary's flow. A
 * token never enters one, so entering is a fault.
 */
final class BoundaryCatchBehavior implements FlowNodeBehavior<BoundaryEvent> {

    private final Class<? extends EventDefinition> definition;

    private BoundaryCatchBehavior(Class<? extends EventDefinition> definition) {
        this.definition = definition;
    }

    static BoundaryCatchBehavior forError() {
        return new BoundaryCatchBehavior(ErrorEventDefinition.class);
    }

    static BoundaryCatchBehavior forEscalation() {
        return new BoundaryCatchBehavior(EscalationEventDefinition.class);
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
