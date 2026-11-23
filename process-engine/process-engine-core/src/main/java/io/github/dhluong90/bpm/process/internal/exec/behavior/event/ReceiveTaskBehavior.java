package io.github.dhluong90.bpm.process.internal.exec.behavior.event;

import io.github.dhluong90.bpm.process.internal.exec.Command;
import io.github.dhluong90.bpm.process.internal.exec.ExecutionContext;
import io.github.dhluong90.bpm.process.internal.exec.FlowNodeBehavior;
import io.github.dhluong90.bpm.process.internal.exec.Token;
import io.github.dhluong90.bpm.process.internal.model.bpmn.ReceiveTask;

/** Receive task: waits for a message exactly as a message catch event does. */
final class ReceiveTaskBehavior implements FlowNodeBehavior<ReceiveTask> {

    @Override
    public Class<ReceiveTask> nodeType() {
        return ReceiveTask.class;
    }

    @Override
    public void enter(ReceiveTask node, Token token, ExecutionContext ctx) {
        MessageSupport.waitFor(node, token, ctx);
    }

    @Override
    public void trigger(ReceiveTask node, Token token, Command trigger, ExecutionContext ctx) {
        MessageSupport.deliver(node, token, trigger, ctx);
    }
}
