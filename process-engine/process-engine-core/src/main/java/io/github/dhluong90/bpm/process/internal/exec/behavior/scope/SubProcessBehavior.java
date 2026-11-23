package io.github.dhluong90.bpm.process.internal.exec.behavior.scope;

import io.github.dhluong90.bpm.process.internal.exec.ExecutionContext;
import io.github.dhluong90.bpm.process.internal.exec.FlowNodeBehavior;
import io.github.dhluong90.bpm.process.internal.exec.Token;
import io.github.dhluong90.bpm.process.internal.model.bpmn.SubProcess;

/**
 * An embedded {@code subProcess} (#486, ADR-0020): entering it opens a {@code SCOPE} token under which its steps run
 * from its own none start event to its own end event. Joins count only the tokens of that scope, and an undo started
 * by a step inside it walks only the stack of that scope. The scope ends when its last live flow token ends, and the
 * token then leaves through the sub-process's outgoing flow in the enclosing scope. It reads and writes the instance
 * variables like its parent (the BPMN default). {@link ExecutionContext#enterScope} and
 * {@link ExecutionContext#complete} do the work, so the same mechanism serves a saga block.
 */
final class SubProcessBehavior implements FlowNodeBehavior<SubProcess> {

    @Override
    public Class<SubProcess> nodeType() {
        return SubProcess.class;
    }

    @Override
    public void enter(SubProcess node, Token token, ExecutionContext ctx) {
        ctx.enterScope(token, node);
    }

    @Override
    public void scopeCompleted(SubProcess node, Token scope, ExecutionContext ctx) {
        ScopeUndo.push(node, scope, ctx);
    }
}
