package io.github.dhluong90.bpm.process.internal.exec.behavior.event;

import io.github.dhluong90.bpm.process.internal.exec.EventDefinitionKey;
import io.github.dhluong90.bpm.process.internal.exec.ExecutionContext;
import io.github.dhluong90.bpm.process.internal.exec.FlowNodeBehavior;
import io.github.dhluong90.bpm.process.internal.exec.Token;
import io.github.dhluong90.bpm.process.internal.model.bpmn.MessageEventDefinition;
import io.github.dhluong90.bpm.process.internal.model.bpmn.StartEvent;
import java.util.Map;

/**
 * Message start event: the instance was started by a message, whose payload is the instance's first variables, so the
 * event only leaves by its outgoing flows.
 */
final class MessageStartEventBehavior implements FlowNodeBehavior<StartEvent> {

    @Override
    public Class<StartEvent> nodeType() {
        return StartEvent.class;
    }

    @Override
    public EventDefinitionKey eventDefinition() {
        return EventDefinitionKey.of(MessageEventDefinition.class);
    }

    @Override
    public void enter(StartEvent node, Token token, ExecutionContext ctx) {
        ctx.history("NODE_COMPLETED", token, Map.of());
        ctx.takeOutgoing(token, node);
    }
}
