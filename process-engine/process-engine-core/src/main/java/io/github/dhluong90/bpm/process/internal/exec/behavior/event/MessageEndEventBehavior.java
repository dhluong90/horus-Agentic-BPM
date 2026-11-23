package io.github.dhluong90.bpm.process.internal.exec.behavior.event;

import io.github.dhluong90.bpm.process.internal.exec.EventDefinitionKey;
import io.github.dhluong90.bpm.process.internal.exec.ExecutionContext;
import io.github.dhluong90.bpm.process.internal.exec.FlowNodeBehavior;
import io.github.dhluong90.bpm.process.internal.exec.Token;
import io.github.dhluong90.bpm.process.internal.model.bpmn.EndEvent;
import io.github.dhluong90.bpm.process.internal.model.bpmn.MessageEventDefinition;
import java.util.Map;

/** Message end event: sends its message, then ends the token like a none end event. */
final class MessageEndEventBehavior implements FlowNodeBehavior<EndEvent> {

    @Override
    public Class<EndEvent> nodeType() {
        return EndEvent.class;
    }

    @Override
    public EventDefinitionKey eventDefinition() {
        return EventDefinitionKey.of(MessageEventDefinition.class);
    }

    @Override
    public void enter(EndEvent node, Token token, ExecutionContext ctx) {
        MessageSupport.send(node, token, ctx);
        ctx.history("NODE_COMPLETED", token, Map.of());
        ctx.complete(token);
    }
}
