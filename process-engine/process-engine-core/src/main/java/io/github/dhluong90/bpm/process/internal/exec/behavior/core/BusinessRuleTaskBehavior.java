package io.github.dhluong90.bpm.process.internal.exec.behavior.core;

import io.github.dhluong90.bpm.process.internal.exec.Command;
import io.github.dhluong90.bpm.process.internal.exec.ExecutionContext;
import io.github.dhluong90.bpm.process.internal.exec.ExecutionMode;
import io.github.dhluong90.bpm.process.internal.exec.FlowNodeBehavior;
import io.github.dhluong90.bpm.process.internal.exec.NotExecutableException;
import io.github.dhluong90.bpm.process.internal.exec.ServiceKind;
import io.github.dhluong90.bpm.process.internal.exec.Token;
import io.github.dhluong90.bpm.process.internal.model.bpmn.BusinessRuleTask;
import io.github.dhluong90.bpm.process.internal.model.horus.DecisionRef;

/**
 * Business rule task: asks the shell to evaluate the rule set ({@code horus:ruleSet}) with the bounded inputs.
 * A decision is synchronous and logged in the caller's transaction (DESIGN section 4).
 */
final class BusinessRuleTaskBehavior implements FlowNodeBehavior<BusinessRuleTask> {

    @Override
    public Class<BusinessRuleTask> nodeType() {
        return BusinessRuleTask.class;
    }

    @Override
    public void enter(BusinessRuleTask node, Token token, ExecutionContext ctx) {
        DecisionRef decision = node.extensions().first(DecisionRef.class).orElseThrow(() -> new NotExecutableException(node));
        InvocationSupport.invoke(node, token, ctx, ServiceKind.DECISION, decision.ruleSet(), ExecutionMode.SYNC_LOCAL);
    }

    @Override
    public void trigger(BusinessRuleTask node, Token token, Command trigger, ExecutionContext ctx) {
        InvocationSupport.resume(node, token, trigger, ctx);
    }
}
