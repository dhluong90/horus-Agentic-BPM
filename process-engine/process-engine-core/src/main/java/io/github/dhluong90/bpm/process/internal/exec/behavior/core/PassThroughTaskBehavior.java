package io.github.dhluong90.bpm.process.internal.exec.behavior.core;

import io.github.dhluong90.bpm.process.internal.exec.ExecutionContext;
import io.github.dhluong90.bpm.process.internal.exec.FlowNodeBehavior;
import io.github.dhluong90.bpm.process.internal.exec.Token;
import io.github.dhluong90.bpm.process.internal.model.bpmn.FlowNode;
import java.util.Map;

/**
 * Plain task and manual task: the engine has nothing to run, so the token moves on at once and the history records the
 * step as done by the engine (DESIGN section 4).
 *
 * @param <T> the node type
 */
final class PassThroughTaskBehavior<T extends FlowNode> implements FlowNodeBehavior<T> {

    private final Class<T> type;

    PassThroughTaskBehavior(Class<T> type) {
        this.type = type;
    }

    @Override
    public Class<T> nodeType() {
        return type;
    }

    @Override
    public void enter(T node, Token token, ExecutionContext ctx) {
        ctx.history("NODE_COMPLETED", token, Map.of("by", "engine"));
        ctx.takeOutgoing(token, node);
    }
}
