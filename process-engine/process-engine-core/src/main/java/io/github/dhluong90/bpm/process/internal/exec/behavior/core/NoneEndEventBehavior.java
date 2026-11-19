package io.github.dhluong90.bpm.process.internal.exec.behavior.core;

import io.github.dhluong90.bpm.process.internal.exec.ExecutionContext;
import io.github.dhluong90.bpm.process.internal.exec.FlowNodeBehavior;
import io.github.dhluong90.bpm.process.internal.exec.NotExecutableException;
import io.github.dhluong90.bpm.process.internal.exec.Token;
import io.github.dhluong90.bpm.process.internal.model.bpmn.EndEvent;
import java.util.Map;

/** None end event: completes the token; the last live token of a scope completes the scope. */
final class NoneEndEventBehavior implements FlowNodeBehavior<EndEvent> {

    @Override
    public Class<EndEvent> nodeType() {
        return EndEvent.class;
    }

    @Override
    public void enter(EndEvent node, Token token, ExecutionContext ctx) {
        if (!node.eventDefinitions().isEmpty() || !node.eventDefinitionRefs().isEmpty()) {
            throw new NotExecutableException(node);
        }
        ctx.history("NODE_COMPLETED", token, Map.of());
        ctx.complete(token);
    }
}
