package io.github.dhluong90.bpm.process.internal.exec.behavior.event;

import io.github.dhluong90.bpm.process.internal.exec.Command;
import io.github.dhluong90.bpm.process.internal.exec.Effect;
import io.github.dhluong90.bpm.process.internal.exec.EventDefinitionKey;
import io.github.dhluong90.bpm.process.internal.exec.ExecutionContext;
import io.github.dhluong90.bpm.process.internal.exec.ExecutionException;
import io.github.dhluong90.bpm.process.internal.exec.FlowNodeBehavior;
import io.github.dhluong90.bpm.process.internal.exec.Token;
import io.github.dhluong90.bpm.process.internal.exec.TokenStatus;
import io.github.dhluong90.bpm.process.internal.model.bpmn.BoundaryEvent;
import io.github.dhluong90.bpm.process.internal.model.bpmn.SignalEventDefinition;
import java.util.Map;

/**
 * Signal boundary event (#490): its own token waits in {@code WAITING_EVENT} on one signal subscription row written
 * in the transaction that enters the host, as a signal catch event does. A broadcast wakes it through a job. The
 * boundary interrupts the host or runs beside it; a non-interrupting one stays armed for the next broadcast.
 */
final class BoundarySignalBehavior implements FlowNodeBehavior<BoundaryEvent> {

    @Override
    public Class<BoundaryEvent> nodeType() {
        return BoundaryEvent.class;
    }

    @Override
    public EventDefinitionKey eventDefinition() {
        return EventDefinitionKey.of(SignalEventDefinition.class);
    }

    @Override
    public void enter(BoundaryEvent node, Token token, ExecutionContext ctx) {
        SignalEventDefinition signal = (SignalEventDefinition) node.eventDefinitions().get(0);
        String subscriptionId = ctx.nextId();
        ctx.update(new Token(token.id(), token.kind(), TokenStatus.WAITING_EVENT, token.scopeTokenId(), token.nodeId(),
                subscriptionId, token.locals()));
        ctx.effect(new Effect.CreateSubscription(subscriptionId, "SIGNAL", signal.signalRef(), null, token.id(),
                node.id()));
        ctx.history("SIGNAL_WAITING", token, Map.of("signal", signal.signalRef(), "subscriptionId", subscriptionId,
                "boundaryOf", String.valueOf(node.attachedToRef())));
    }

    @Override
    public void trigger(BoundaryEvent node, Token token, Command trigger, ExecutionContext ctx) {
        if (!(trigger instanceof Command.DeliverSignal delivered)) {
            throw new ExecutionException("COMMAND_NOT_SUPPORTED", node.id(), "Expected a signal");
        }
        ctx.history("SIGNAL_RECEIVED", token, Map.of("signal", delivered.signalName()));
        if (node.cancelActivity()) {
            ctx.effect(new Effect.DeleteSubscription(delivered.subscriptionId()));
        }
        ctx.fireBoundary(token, node, "SIGNAL", Map.of("signal", delivered.signalName()), true);
    }
}
