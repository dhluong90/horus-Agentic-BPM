package io.github.dhluong90.bpm.process.internal.exec.behavior.event;

import io.github.dhluong90.bpm.process.internal.exec.Effect;
import io.github.dhluong90.bpm.process.internal.exec.EventDefinitionKey;
import io.github.dhluong90.bpm.process.internal.exec.ExecutionContext;
import io.github.dhluong90.bpm.process.internal.exec.FlowNodeBehavior;
import io.github.dhluong90.bpm.process.internal.exec.Token;
import io.github.dhluong90.bpm.process.internal.model.bpmn.EndEvent;
import io.github.dhluong90.bpm.process.internal.model.bpmn.SignalEventDefinition;
import java.util.Map;

/** Signal end event (#492): broadcasts the signal in the tenant, then ends the token as a none end event does. */
final class SignalEndEventBehavior implements FlowNodeBehavior<EndEvent> {

    @Override
    public Class<EndEvent> nodeType() {
        return EndEvent.class;
    }

    @Override
    public EventDefinitionKey eventDefinition() {
        return EventDefinitionKey.of(SignalEventDefinition.class);
    }

    @Override
    public void enter(EndEvent node, Token token, ExecutionContext ctx) {
        SignalEventDefinition signal = (SignalEventDefinition) node.eventDefinitions().get(0);
        ctx.effect(new Effect.BroadcastSignal(ctx.nextId(), signal.signalRef(), token.id(), node.id()));
        ctx.history("NODE_COMPLETED", token, Map.of());
        ctx.complete(token);
    }
}
