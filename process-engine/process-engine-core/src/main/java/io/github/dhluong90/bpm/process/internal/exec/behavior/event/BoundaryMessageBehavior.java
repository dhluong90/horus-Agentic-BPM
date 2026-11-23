package io.github.dhluong90.bpm.process.internal.exec.behavior.event;

import io.github.dhluong90.bpm.process.internal.exec.Command;
import io.github.dhluong90.bpm.process.internal.exec.Effect;
import io.github.dhluong90.bpm.process.internal.exec.EventDefinitionKey;
import io.github.dhluong90.bpm.process.internal.exec.ExecutionContext;
import io.github.dhluong90.bpm.process.internal.exec.ExecutionException;
import io.github.dhluong90.bpm.process.internal.exec.FlowNodeBehavior;
import io.github.dhluong90.bpm.process.internal.exec.Token;
import io.github.dhluong90.bpm.process.internal.model.bpmn.BoundaryEvent;
import io.github.dhluong90.bpm.process.internal.model.bpmn.MessageEventDefinition;
import io.github.dhluong90.bpm.process.internal.model.horus.OutputMapping;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Message boundary event (#490): when its host activity is entered the boundary gets its own token, which waits in
 * {@code WAITING_EVENT} on one message subscription of the host's tenant, written in the same transaction, exactly as
 * an intermediate message catch does. The correlation key is read from the variable named by
 * {@code horus:correlationKey} when the host is entered. A matching message interrupts the host
 * ({@code cancelActivity} true, the default) or runs the boundary's flow beside it (false), and a non-interrupting
 * boundary stays armed for the next message until the host ends.
 */
final class BoundaryMessageBehavior implements FlowNodeBehavior<BoundaryEvent> {

    @Override
    public Class<BoundaryEvent> nodeType() {
        return BoundaryEvent.class;
    }

    @Override
    public EventDefinitionKey eventDefinition() {
        return EventDefinitionKey.of(MessageEventDefinition.class);
    }

    @Override
    public void enter(BoundaryEvent node, Token token, ExecutionContext ctx) {
        MessageSupport.waitFor(node, token, ctx);
    }

    @Override
    public void trigger(BoundaryEvent node, Token token, Command trigger, ExecutionContext ctx) {
        if (!(trigger instanceof Command.CorrelateMessage message)) {
            throw new ExecutionException("COMMAND_NOT_SUPPORTED", node.id(), "Expected a message");
        }
        Map<String, Object> brought = new LinkedHashMap<>();
        for (OutputMapping out : node.extensions().all(OutputMapping.class)) {
            if (!message.payload().containsKey(out.from())) {
                throw new ExecutionException("MESSAGE_FIELD_MISSING", node.id(), "The message has no field " + out.from());
            }
            brought.put(out.name(), message.payload().get(out.from()));
        }
        brought.forEach((k, v) -> ctx.variables().set(k, v));
        ctx.history("MESSAGE_RECEIVED", token, Map.of("message", message.messageName(), "subscriptionId",
                message.subscriptionId()));
        if (node.cancelActivity()) {
            ctx.effect(new Effect.DeleteSubscription(message.subscriptionId()));
        }
        ctx.fireBoundary(token, node, "MESSAGE", Map.of("message", message.messageName()), true);
    }
}
