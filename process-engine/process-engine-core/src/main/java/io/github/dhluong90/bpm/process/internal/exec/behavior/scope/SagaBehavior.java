package io.github.dhluong90.bpm.process.internal.exec.behavior.scope;

import io.github.dhluong90.bpm.process.internal.exec.ExecutionContext;
import io.github.dhluong90.bpm.process.internal.exec.FlowNodeBehavior;
import io.github.dhluong90.bpm.process.internal.exec.Token;
import io.github.dhluong90.bpm.process.internal.model.bpmn.Transaction;

/**
 * A {@code saga} block (#67, ADR-0007, ADR-0020): entering it opens a {@code SCOPE} token under which its steps run
 * and push their undo entries, so a compensation undoes only the steps of this block (PRE-044). The block is left
 * when its last flow token ends; {@link ExecutionContext#enterScope} and {@link ExecutionContext#complete} do both.
 */
final class SagaBehavior implements FlowNodeBehavior<Transaction> {

    @Override
    public Class<Transaction> nodeType() {
        return Transaction.class;
    }

    @Override
    public void enter(Transaction node, Token token, ExecutionContext ctx) {
        ctx.enterScope(token, node);
    }
}
