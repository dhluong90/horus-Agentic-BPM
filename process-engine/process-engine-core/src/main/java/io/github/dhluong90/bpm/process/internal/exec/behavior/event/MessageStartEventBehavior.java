package io.github.dhluong90.bpm.process.internal.exec.behavior.event;

import io.github.dhluong90.bpm.process.internal.exec.Command;
import io.github.dhluong90.bpm.process.internal.exec.EventDefinitionKey;
import io.github.dhluong90.bpm.process.internal.exec.ExecutionContext;
import io.github.dhluong90.bpm.process.internal.exec.FlowNodeBehavior;
import io.github.dhluong90.bpm.process.internal.exec.Token;
import io.github.dhluong90.bpm.process.internal.exec.TokenStatus;
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
        if (EventSubProcessStarts.listening(node, token, ctx)) {
            // #487: the start event of an event sub-process waits like a message catch, for the instance's own key.
            MessageSupport.waitFor(node, token, ctx);
            return;
        }
        EventSubProcessStarts.leave(node, token, ctx);
    }

    @Override
    public void trigger(StartEvent node, Token token, Command trigger, ExecutionContext ctx) {
        Token scope = EventSubProcessStarts.scopeOf(token, ctx);
        MessageSupport.receive(node, token, trigger, ctx);
        ctx.update(token.withStatus(TokenStatus.COMPLETED));
        ctx.startEventSubProcess(scope, node, "MESSAGE", Map.of(), null);
        if (!node.isInterrupting()) {
            // A non-interrupting one starts again for the next message of its key.
            MessageSupport.waitFor(node, EventSubProcessStarts.again(node, token, ctx), ctx);
        }
    }
}
