package io.github.dhluong90.bpm.process.internal.exec.behavior.scope;

import io.github.dhluong90.bpm.process.internal.exec.EventDefinitionKey;
import io.github.dhluong90.bpm.process.internal.exec.ExecutionContext;
import io.github.dhluong90.bpm.process.internal.exec.FlowNodeBehavior;
import io.github.dhluong90.bpm.process.internal.exec.Token;
import io.github.dhluong90.bpm.process.internal.model.bpmn.CompensateEventDefinition;
import io.github.dhluong90.bpm.process.internal.model.bpmn.IntermediateThrowEvent;
import java.util.Map;

/**
 * {@code throwCompensation} (#67, PRE-076, #488): undoes the completed steps of the enclosing scope newest first, the
 * steps of sub-processes completed in it included. With no outgoing flow it is the end of the road as before: like a
 * step that fails with {@code onFailure: compensate}, the instance ends {@code FAILED} once the walk reaches the bottom
 * of the stack (DESIGN section 3.3). With an outgoing flow it is the BPMN throw: the flow goes on after the walk, and
 * an {@code activityRef} limits the undo to that activity.
 */
final class ThrowCompensationBehavior implements FlowNodeBehavior<IntermediateThrowEvent> {

    /** The cause a compensation started by this event records. */
    static final String CAUSE = "THROW_COMPENSATION";

    @Override
    public Class<IntermediateThrowEvent> nodeType() {
        return IntermediateThrowEvent.class;
    }

    @Override
    public EventDefinitionKey eventDefinition() {
        return EventDefinitionKey.of(CompensateEventDefinition.class);
    }

    @Override
    public void enter(IntermediateThrowEvent node, Token token, ExecutionContext ctx) {
        if (ctx.process().outgoing(node.id()).isEmpty()) {
            ctx.history("NODE_COMPLETED", token, Map.of());
            ctx.compensate(token, CAUSE);
            return;
        }
        ctx.compensateAndResume(token, ((CompensateEventDefinition) node.eventDefinitions().get(0)).activityRef());
    }
}
