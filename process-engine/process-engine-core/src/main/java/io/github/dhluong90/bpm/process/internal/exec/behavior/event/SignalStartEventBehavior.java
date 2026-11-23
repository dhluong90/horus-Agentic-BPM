package io.github.dhluong90.bpm.process.internal.exec.behavior.event;

import io.github.dhluong90.bpm.process.internal.exec.Command;
import io.github.dhluong90.bpm.process.internal.exec.Effect;
import io.github.dhluong90.bpm.process.internal.exec.ExecutionException;
import io.github.dhluong90.bpm.process.internal.exec.TokenStatus;
import io.github.dhluong90.bpm.process.internal.exec.EventDefinitionKey;
import io.github.dhluong90.bpm.process.internal.exec.ExecutionContext;
import io.github.dhluong90.bpm.process.internal.exec.FlowNodeBehavior;
import io.github.dhluong90.bpm.process.internal.exec.Token;
import io.github.dhluong90.bpm.process.internal.model.bpmn.SignalEventDefinition;
import io.github.dhluong90.bpm.process.internal.model.bpmn.StartEvent;
import java.util.Map;

/**
 * Signal start event (#492): a broadcast of its name in the tenant starts one instance, and a start request starts it
 * too. Once the instance exists the event just leaves by its flow.
 */
final class SignalStartEventBehavior implements FlowNodeBehavior<StartEvent> {

    @Override
    public Class<StartEvent> nodeType() {
        return StartEvent.class;
    }

    @Override
    public EventDefinitionKey eventDefinition() {
        return EventDefinitionKey.of(SignalEventDefinition.class);
    }

    @Override
    public void enter(StartEvent node, Token token, ExecutionContext ctx) {
        if (!EventSubProcessStarts.listening(node, token, ctx)) {
            EventSubProcessStarts.leave(node, token, ctx);
            return;
        }
        // #487: the start event of an event sub-process waits like a signal catch, on one subscription of the tenant.
        SignalEventDefinition signal = (SignalEventDefinition) node.eventDefinitions().get(0);
        String subscriptionId = ctx.nextId();
        ctx.update(new Token(token.id(), token.kind(), TokenStatus.WAITING_EVENT, token.scopeTokenId(), token.nodeId(),
                subscriptionId, token.locals()));
        ctx.effect(new Effect.CreateSubscription(subscriptionId, "SIGNAL", signal.signalRef(), null, token.id(),
                node.id()));
        ctx.history("SIGNAL_WAITING", token, Map.of("signal", signal.signalRef(), "subscriptionId", subscriptionId));
    }

    @Override
    public void trigger(StartEvent node, Token token, Command trigger, ExecutionContext ctx) {
        if (trigger instanceof Command.VariablesChanged) {
            return;
        }
        if (!(trigger instanceof Command.DeliverSignal delivered)) {
            throw new ExecutionException("COMMAND_NOT_SUPPORTED", node.id(), "Expected a signal");
        }
        Token scope = EventSubProcessStarts.scopeOf(token, ctx);
        ctx.update(token.withStatus(TokenStatus.COMPLETED));
        ctx.effect(new Effect.DeleteSubscription(delivered.subscriptionId()));
        ctx.history("SIGNAL_RECEIVED", token, Map.of("signal", delivered.signalName()));
        ctx.startEventSubProcess(scope, node, "SIGNAL", Map.of(), null);
        if (!node.isInterrupting()) {
            // A non-interrupting one starts again for the next broadcast of its name.
            enter(node, EventSubProcessStarts.again(node, token, ctx), ctx);
        }
    }
}
