package io.github.dhluong90.bpm.process.internal.exec.behavior.event;

import io.github.dhluong90.bpm.process.internal.exec.Effect;
import io.github.dhluong90.bpm.process.internal.exec.EventDefinitionKey;
import io.github.dhluong90.bpm.process.internal.exec.ExecutionContext;
import io.github.dhluong90.bpm.process.internal.exec.FlowNodeBehavior;
import io.github.dhluong90.bpm.process.internal.exec.Token;
import io.github.dhluong90.bpm.process.internal.model.bpmn.IntermediateThrowEvent;
import io.github.dhluong90.bpm.process.internal.model.bpmn.SignalEventDefinition;
import java.util.Map;

/**
 * Intermediate signal throw event (#492): broadcasts the signal in the tenant and goes on at once. The broadcast is a
 * write in this transaction; the waiters are woken by jobs afterwards, so the thrower never holds their locks.
 */
final class SignalThrowEventBehavior implements FlowNodeBehavior<IntermediateThrowEvent> {

    @Override
    public Class<IntermediateThrowEvent> nodeType() {
        return IntermediateThrowEvent.class;
    }

    @Override
    public EventDefinitionKey eventDefinition() {
        return EventDefinitionKey.of(SignalEventDefinition.class);
    }

    @Override
    public void enter(IntermediateThrowEvent node, Token token, ExecutionContext ctx) {
        SignalEventDefinition signal = (SignalEventDefinition) node.eventDefinitions().get(0);
        ctx.effect(new Effect.BroadcastSignal(ctx.nextId(), signal.signalRef(), token.id(), node.id()));
        ctx.history("NODE_COMPLETED", token, Map.of());
        ctx.takeOutgoing(token, node);
    }
}
