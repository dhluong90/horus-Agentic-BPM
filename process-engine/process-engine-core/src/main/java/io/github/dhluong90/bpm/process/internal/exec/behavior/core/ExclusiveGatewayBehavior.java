package io.github.dhluong90.bpm.process.internal.exec.behavior.core;

import io.github.dhluong90.bpm.process.internal.exec.ExecutionContext;
import io.github.dhluong90.bpm.process.internal.exec.ExecutionException;
import io.github.dhluong90.bpm.process.internal.exec.FlowNodeBehavior;
import io.github.dhluong90.bpm.process.internal.exec.Token;
import io.github.dhluong90.bpm.process.internal.model.bpmn.ExclusiveGateway;
import io.github.dhluong90.bpm.process.internal.model.bpmn.SequenceFlow;
import java.util.Map;

/**
 * Exclusive gateway: outgoing conditions are tested in document order and the first true one is taken; the
 * default flow is taken when none holds; no true condition and no default is {@code NO_PATH} (PRE-004).
 */
final class ExclusiveGatewayBehavior implements FlowNodeBehavior<ExclusiveGateway> {

    @Override
    public Class<ExclusiveGateway> nodeType() {
        return ExclusiveGateway.class;
    }

    @Override
    public void enter(ExclusiveGateway node, Token token, ExecutionContext ctx) {
        String chosen = null;
        for (SequenceFlow flow : ctx.process().outgoing(node.id())) {
            if (flow.id().equals(node.defaultFlow())) {
                continue;
            }
            if (flow.conditionExpression() == null) {
                chosen = flow.id();
                break;
            }
            Object value = ctx.evaluate(flow.conditionExpression().text(), flow.conditionExpression().language(), token);
            if (!(value instanceof Boolean b)) {
                throw new ExecutionException("CONDITION_ERROR", node.id(), "Condition of " + flow.id()
                        + " did not produce a boolean");
            }
            if (b) {
                chosen = flow.id();
                break;
            }
        }
        if (chosen == null) {
            chosen = node.defaultFlow();
        }
        if (chosen == null) {
            throw new ExecutionException("NO_PATH", node.id(), "No condition of " + node.id()
                    + " is true and it has no default flow");
        }
        ctx.history("NODE_COMPLETED", token, Map.of("flow", chosen));
        ctx.take(token, chosen);
    }
}
