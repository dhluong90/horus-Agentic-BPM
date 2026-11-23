package io.github.dhluong90.bpm.process.internal.exec.behavior.event;

import io.github.dhluong90.bpm.process.internal.exec.Command;
import io.github.dhluong90.bpm.process.internal.exec.EventDefinitionKey;
import io.github.dhluong90.bpm.process.internal.exec.ExecutionContext;
import io.github.dhluong90.bpm.process.internal.exec.ExecutionException;
import io.github.dhluong90.bpm.process.internal.exec.FlowNodeBehavior;
import io.github.dhluong90.bpm.process.internal.exec.Token;
import io.github.dhluong90.bpm.process.internal.exec.TokenStatus;
import io.github.dhluong90.bpm.process.internal.model.bpmn.BoundaryEvent;
import io.github.dhluong90.bpm.process.internal.model.bpmn.ConditionalEventDefinition;
import java.util.Map;

/**
 * Conditional boundary event (#490): its own token waits in {@code WAITING_EVENT} and stores nothing. The kernel asks
 * its condition again in the transaction that changes the instance's variables, under the instance lock, never by
 * polling, and the boundary fires once when the condition is true: it interrupts the host or runs beside it. A
 * condition already true when the host is entered fires on the next change of a variable, not on arrival.
 */
final class BoundaryConditionalBehavior implements FlowNodeBehavior<BoundaryEvent> {

    @Override
    public Class<BoundaryEvent> nodeType() {
        return BoundaryEvent.class;
    }

    @Override
    public EventDefinitionKey eventDefinition() {
        return EventDefinitionKey.of(ConditionalEventDefinition.class);
    }

    @Override
    public void enter(BoundaryEvent node, Token token, ExecutionContext ctx) {
        ctx.update(new Token(token.id(), token.kind(), TokenStatus.WAITING_EVENT, token.scopeTokenId(), token.nodeId(),
                ctx.nextId(), token.locals()));
        ctx.history("CONDITION_WAITING", token, Map.of("boundaryOf", String.valueOf(node.attachedToRef())));
    }

    @Override
    public void trigger(BoundaryEvent node, Token token, Command trigger, ExecutionContext ctx) {
        if (!(trigger instanceof Command.VariablesChanged)) {
            throw new ExecutionException("COMMAND_NOT_SUPPORTED", node.id(), "Expected a change of the variables");
        }
        ConditionalEventDefinition definition = (ConditionalEventDefinition) node.eventDefinitions().get(0);
        if (!Conditions.holdsNow(definition, token, ctx)) {
            return;
        }
        ctx.history("CONDITION_MET", token, Map.of("onArrival", false));
        ctx.fireBoundary(token, node, "CONDITION", Map.of("condition", definition.condition().text().strip()), false);
    }
}
