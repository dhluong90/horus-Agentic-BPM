package io.github.dhluong90.bpm.process.internal.exec.behavior.scope;

import io.github.dhluong90.bpm.process.internal.exec.Effect;
import io.github.dhluong90.bpm.process.internal.exec.ExecutionContext;
import io.github.dhluong90.bpm.process.internal.exec.Token;
import io.github.dhluong90.bpm.process.internal.exec.behavior.core.StepSupport;
import io.github.dhluong90.bpm.process.internal.model.bpmn.FlowNode;
import io.github.dhluong90.bpm.process.internal.model.horus.HandlerRef;
import java.util.Map;

/**
 * The undo of a sub-process or transaction that completed (#487, #488): a compensation boundary event on it, or a
 * compensation event sub-process in it, names one service task, whose handler is the undo. When the scope ends
 * normally one entry is pushed on the stack of the scope it stands in, so a later compensation of that scope runs it
 * with the steps around it, newest first. The inputs are those the task declares, read from the instance variables at
 * the moment the scope ended. The steps inside the sub-process keep their own entries and are still undone after it.
 */
final class ScopeUndo {

    private ScopeUndo() {
    }

    static void push(FlowNode node, Token scope, ExecutionContext ctx) {
        ctx.process().compensationActivityOf(node.id()).ifPresent(task -> task.extensions().first(HandlerRef.class)
                .map(HandlerRef::handler).ifPresent(handler -> {
                    Map<String, Object> snapshot = StepSupport.inputs(task, scope, ctx);
                    ctx.effect(new Effect.PushCompensation(scope.scopeTokenId(), node.id(), scope.id(), handler,
                            snapshot));
                }));
    }
}
