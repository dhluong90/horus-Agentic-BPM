package io.github.dhluong90.bpm.process.internal.exec.behavior.gateway;

import io.github.dhluong90.bpm.process.internal.exec.ExecutionContext;
import io.github.dhluong90.bpm.process.internal.exec.ExecutionException;
import io.github.dhluong90.bpm.process.internal.exec.FlowNodeBehavior;
import io.github.dhluong90.bpm.process.internal.exec.Token;
import io.github.dhluong90.bpm.process.internal.exec.TokenKind;
import io.github.dhluong90.bpm.process.internal.exec.TokenStatus;
import io.github.dhluong90.bpm.process.internal.model.bpmn.InclusiveGateway;
import io.github.dhluong90.bpm.process.internal.model.bpmn.SequenceFlow;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Inclusive gateway (#494, PRE-300 to PRE-304). Split: every outgoing flow without a condition or with a true one is
 * taken, one token each; the default flow is taken when none is; none and no default is {@code NO_PATH}. Join, when
 * the gateway has several incoming flows: the arriving token becomes {@code JOINED} and waits. The
 * {@link InclusiveJoinSettleStep} fires the join once no live token of the scope can still reach it, so the number of
 * arrivals is not fixed in advance: it is whatever the matching split, and the branches since, produced.
 */
final class InclusiveGatewayBehavior implements FlowNodeBehavior<InclusiveGateway> {

    @Override
    public Class<InclusiveGateway> nodeType() {
        return InclusiveGateway.class;
    }

    @Override
    public void enter(InclusiveGateway node, Token token, ExecutionContext ctx) {
        InclusiveJoinCompileStep.JoinSources joins = ctx.facet(InclusiveJoinCompileStep.JOIN_SOURCES);
        if (!joins.isJoin(node.id())) {
            split(node, token, ctx);
            return;
        }
        ctx.update(token.withStatus(TokenStatus.JOINED));
        long arrived = ctx.tree().children(token.scopeTokenId()).stream()
                .filter(t -> t.kind() == TokenKind.FLOW && t.status() == TokenStatus.JOINED
                        && t.nodeId().equals(node.id()))
                .count();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("arrived", arrived);
        details.put("waitingFor", InclusiveJoinSettleStep.blockers(node.id(), token.scopeTokenId(), ctx));
        ctx.history("TOKEN_JOINED", token.withStatus(TokenStatus.JOINED), details);
    }

    /** Takes every true flow, or the default; the first on the arriving token, a new token of its scope for the rest. */
    static void split(InclusiveGateway node, Token token, ExecutionContext ctx) {
        List<SequenceFlow> taken = new ArrayList<>();
        for (SequenceFlow flow : ctx.process().outgoing(node.id())) {
            if (flow.id().equals(node.defaultFlow())) {
                continue;
            }
            if (flow.conditionExpression() == null || flow.conditionExpression().text() == null
                    || flow.conditionExpression().text().isBlank()) {
                taken.add(flow);
                continue;
            }
            Object value = ctx.evaluate(flow.conditionExpression().text(), flow.conditionExpression().language(), token);
            if (!(value instanceof Boolean b)) {
                throw new ExecutionException("CONDITION_ERROR", node.id(), "Condition of " + flow.id()
                        + " did not produce a boolean");
            }
            if (b) {
                taken.add(flow);
            }
        }
        boolean byDefault = taken.isEmpty() && node.defaultFlow() != null;
        if (byDefault) {
            taken.add(ctx.process().flow(node.defaultFlow()).orElseThrow());
        }
        if (taken.isEmpty()) {
            throw new ExecutionException("NO_PATH", node.id(), "No condition of " + node.id()
                    + " is true and it has no default flow");
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("flows", taken.stream().map(SequenceFlow::id).toList());
        details.put("reason", byDefault ? "default" : "conditions");
        ctx.history("NODE_COMPLETED", token, details);
        ctx.take(token, taken.get(0).id());
        for (SequenceFlow flow : taken.subList(1, taken.size())) {
            ctx.take(ctx.createToken(TokenKind.FLOW, node.id(), token.scopeTokenId()), flow.id());
        }
    }

    /** Ids of nodes, sorted, for a history detail. */
    static List<String> sorted(Iterable<String> ids) {
        TreeSet<String> out = new TreeSet<>();
        ids.forEach(out::add);
        return List.copyOf(out);
    }
}
