package io.github.dhluong90.bpm.process.internal.exec.behavior.event;

import io.github.dhluong90.bpm.process.internal.exec.Command;
import io.github.dhluong90.bpm.process.internal.exec.EventDefinitionKey;
import io.github.dhluong90.bpm.process.internal.exec.ExecutionContext;
import io.github.dhluong90.bpm.process.internal.exec.FlowNodeBehavior;
import io.github.dhluong90.bpm.process.internal.exec.Token;
import io.github.dhluong90.bpm.process.internal.model.bpmn.IntermediateCatchEvent;
import io.github.dhluong90.bpm.process.internal.model.bpmn.MessageEventDefinition;

/** Intermediate message catch event: the token waits until the host correlates a message of its name and key. */
final class MessageCatchEventBehavior implements FlowNodeBehavior<IntermediateCatchEvent> {

    @Override
    public Class<IntermediateCatchEvent> nodeType() {
        return IntermediateCatchEvent.class;
    }

    @Override
    public EventDefinitionKey eventDefinition() {
        return EventDefinitionKey.of(MessageEventDefinition.class);
    }

    @Override
    public void enter(IntermediateCatchEvent node, Token token, ExecutionContext ctx) {
        MessageSupport.waitFor(node, token, ctx);
    }

    @Override
    public void trigger(IntermediateCatchEvent node, Token token, Command trigger, ExecutionContext ctx) {
        MessageSupport.deliver(node, token, trigger, ctx);
    }
}
