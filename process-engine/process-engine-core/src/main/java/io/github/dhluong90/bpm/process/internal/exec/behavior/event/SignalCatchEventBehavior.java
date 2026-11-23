package io.github.dhluong90.bpm.process.internal.exec.behavior.event;

import io.github.dhluong90.bpm.process.internal.exec.Command;
import io.github.dhluong90.bpm.process.internal.exec.Effect;
import io.github.dhluong90.bpm.process.internal.exec.EventDefinitionKey;
import io.github.dhluong90.bpm.process.internal.exec.ExecutionContext;
import io.github.dhluong90.bpm.process.internal.exec.ExecutionException;
import io.github.dhluong90.bpm.process.internal.exec.FlowNodeBehavior;
import io.github.dhluong90.bpm.process.internal.exec.Token;
import io.github.dhluong90.bpm.process.internal.exec.TokenStatus;
import io.github.dhluong90.bpm.process.internal.model.bpmn.IntermediateCatchEvent;
import io.github.dhluong90.bpm.process.internal.model.bpmn.SignalEventDefinition;
import java.util.Map;

/**
 * Intermediate signal catch event (#492): the token waits in {@code WAITING_EVENT} on one subscription row of its
 * tenant, written in the same transaction. A broadcast wakes it through a job, never inline; the subscription id is the
 * token's activity instance id, so a delivery for an earlier visit is refused as stale.
 */
final class SignalCatchEventBehavior implements FlowNodeBehavior<IntermediateCatchEvent> {

    @Override
    public Class<IntermediateCatchEvent> nodeType() {
        return IntermediateCatchEvent.class;
    }

    @Override
    public EventDefinitionKey eventDefinition() {
        return EventDefinitionKey.of(SignalEventDefinition.class);
    }

    @Override
    public void enter(IntermediateCatchEvent node, Token token, ExecutionContext ctx) {
        SignalEventDefinition signal = (SignalEventDefinition) node.eventDefinitions().get(0);
        String subscriptionId = ctx.nextId();
        ctx.update(new Token(token.id(), token.kind(), TokenStatus.WAITING_EVENT, token.scopeTokenId(), token.nodeId(),
                subscriptionId, token.locals()));
        ctx.effect(new Effect.CreateSubscription(subscriptionId, "SIGNAL", signal.signalRef(), null, token.id(),
                node.id()));
        ctx.history("SIGNAL_WAITING", token, Map.of("signal", signal.signalRef(), "subscriptionId", subscriptionId));
    }

    @Override
    public void trigger(IntermediateCatchEvent node, Token token, Command trigger, ExecutionContext ctx) {
        if (trigger instanceof Command.VariablesChanged) {
            return;
        }
        if (!(trigger instanceof Command.DeliverSignal delivered)) {
            throw new ExecutionException("COMMAND_NOT_SUPPORTED", node.id(), "Expected a signal");
        }
        Token active = token.withStatus(TokenStatus.ACTIVE);
        ctx.update(active);
        ctx.effect(new Effect.DeleteSubscription(delivered.subscriptionId()));
        ctx.history("SIGNAL_RECEIVED", token, Map.of("signal", delivered.signalName()));
        ctx.history("NODE_COMPLETED", token, Map.of());
        ctx.takeOutgoing(active, node);
    }
}
