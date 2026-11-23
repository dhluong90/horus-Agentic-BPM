package io.github.dhluong90.bpm.process.internal.exec.behavior.event;

import io.github.dhluong90.bpm.process.internal.exec.ExecutionContext;
import io.github.dhluong90.bpm.process.internal.exec.FlowNodeBehavior;
import io.github.dhluong90.bpm.process.internal.exec.Token;
import io.github.dhluong90.bpm.process.internal.model.bpmn.SendTask;
import java.util.Map;

/**
 * Send task: the message goes to the host's adapter in the step transaction, which writes it to the host outbox, and
 * the token moves on at once. The message id is derived from the instance and its sequence, so a retried step hands
 * over the same id and the host keeps one message.
 */
final class SendTaskBehavior implements FlowNodeBehavior<SendTask> {

    @Override
    public Class<SendTask> nodeType() {
        return SendTask.class;
    }

    @Override
    public void enter(SendTask node, Token token, ExecutionContext ctx) {
        MessageSupport.send(node, token, ctx);
        ctx.history("NODE_COMPLETED", token, Map.of());
        ctx.takeOutgoing(token, node);
    }
}
