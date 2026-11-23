package io.github.dhluong90.bpm.process.internal.exec.behavior.core;

import io.github.dhluong90.bpm.process.internal.exec.ExecutionContext;
import io.github.dhluong90.bpm.process.internal.exec.ExecutionException;
import io.github.dhluong90.bpm.process.internal.exec.FlowNodeBehavior;
import io.github.dhluong90.bpm.process.internal.exec.NotExecutableException;
import io.github.dhluong90.bpm.process.internal.exec.Token;
import io.github.dhluong90.bpm.process.internal.expr.ConditionException;
import io.github.dhluong90.bpm.process.internal.expr.Script;
import io.github.dhluong90.bpm.process.internal.model.bpmn.ScriptTask;
import io.github.dhluong90.bpm.process.internal.model.horus.ResultVariable;
import java.util.Map;

/**
 * Script task: runs the script, an expression in {@code urn:horus-bpm:expr:v1} or FEEL, over the visible variables in the
 * caller's transaction and stores its value in {@code horus:resultVariable}. A script reads variables and sets that one
 * result; it cannot call host code, loop, or run past its step budget ({@link Script}).
 */
final class ScriptTaskBehavior implements FlowNodeBehavior<ScriptTask> {

    @Override
    public Class<ScriptTask> nodeType() {
        return ScriptTask.class;
    }

    @Override
    public void enter(ScriptTask node, Token token, ExecutionContext ctx) {
        if (node.script() == null) {
            throw new NotExecutableException(node);
        }
        String target = node.extensions().first(ResultVariable.class).map(ResultVariable::name)
                .orElseThrow(() -> new NotExecutableException(node));
        Object value;
        try {
            value = Script.parse(node.script(), node.scriptFormat()).run(ctx.variables().visible(token));
        } catch (ConditionException e) {
            throw new ExecutionException("SCRIPT_ERROR", node.id(), e.getMessage());
        }
        ctx.variables().set(target, value);
        ctx.history("NODE_COMPLETED", token, Map.of("by", "engine", "resultVariable", target));
        ctx.takeOutgoing(token, node);
    }
}
