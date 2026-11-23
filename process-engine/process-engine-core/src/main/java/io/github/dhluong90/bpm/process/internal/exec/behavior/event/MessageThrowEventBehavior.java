package io.github.dhluong90.bpm.process.internal.exec.behavior.event;

import io.github.dhluong90.bpm.process.internal.exec.EventDefinitionKey;
import io.github.dhluong90.bpm.process.internal.exec.ExecutionContext;
import io.github.dhluong90.bpm.process.internal.exec.FlowNodeBehavior;
import io.github.dhluong90.bpm.process.internal.exec.Token;
import io.github.dhluong90.bpm.process.internal.model.bpmn.IntermediateThrowEvent;
import io.github.dhluong90.bpm.process.internal.model.bpmn.MessageEventDefinition;
import java.util.Map;

/** Intermediate message throw event: sends like a send task, then the token moves on. */
final class MessageThrowEventBehavior implements FlowNodeBehavior<IntermediateThrowEvent> {

    @Override
    public Class<IntermediateThrowEvent> nodeType() {
        return IntermediateThrowEvent.class;
    }

    @Override
    public EventDefinitionKey eventDefinition() {
        return EventDefinitionKey.of(MessageEventDefinition.class);
    }

    @Override
    public void enter(IntermediateThrowEvent node, Token token, ExecutionContext ctx) {
        MessageSupport.send(node, token, ctx);
        ctx.history("NODE_COMPLETED", token, Map.of());
        ctx.takeOutgoing(token, node);
    }
}
