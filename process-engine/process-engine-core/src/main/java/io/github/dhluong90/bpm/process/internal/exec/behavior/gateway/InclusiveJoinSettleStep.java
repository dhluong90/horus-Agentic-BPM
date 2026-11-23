package io.github.dhluong90.bpm.process.internal.exec.behavior.gateway;

import io.github.dhluong90.bpm.process.internal.exec.ExecutionContext;
import io.github.dhluong90.bpm.process.internal.exec.ExecutionException;
import io.github.dhluong90.bpm.process.internal.exec.SettleStep;
import io.github.dhluong90.bpm.process.internal.exec.Token;
import io.github.dhluong90.bpm.process.internal.exec.TokenKind;
import io.github.dhluong90.bpm.process.internal.exec.TokenStatus;
import io.github.dhluong90.bpm.process.internal.model.bpmn.InclusiveGateway;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Fires the inclusive joins that are ready (#494). It runs when every token of the apply has moved as far as it
 * goes, under the instance lock the shell holds for the whole apply, so two branches ending at the same moment are
 * applied one after the other and only the later apply finds the join ready: one outgoing token, never zero or two.
 *
 * <p>A join of one scope is ready when no other token of that scope can still reach it: no live token, and no token
 * waiting at another join, on a node that reaches the join without passing through it. A subprocess token stands in for
 * everything inside the subprocess. Because the step runs after every apply and not only on arrival, a token that
 * ends on a branch that never reaches the join releases a join that is already waiting.
 */
final class InclusiveJoinSettleStep implements SettleStep {

    @Override
    public boolean settle(ExecutionContext ctx) {
        InclusiveJoinCompileStep.JoinSources joins = ctx.facet(InclusiveJoinCompileStep.JOIN_SOURCES);
        for (Token waiting : ctx.tree().all()) {
            if (waiting.status() != TokenStatus.JOINED || waiting.kind() != TokenKind.FLOW
                    || !(ctx.process().node(waiting.nodeId()).orElse(null) instanceof InclusiveGateway gateway)
                    || !joins.isJoin(gateway.id()) || !scopeIsOpen(waiting.scopeTokenId(), ctx)) {
                continue;
            }
            if (blockers(gateway.id(), waiting.scopeTokenId(), ctx).isEmpty()) {
                fire(gateway, waiting.scopeTokenId(), ctx);
                return true;
            }
        }
        failWhenStuck(ctx, joins);
        return false;
    }

    /**
     * @param joinId an inclusive join
     * @param scopeTokenId the scope the arrivals are under
     * @param ctx the context
     * @return sorted ids of the nodes where a token of the scope can still arrive from; empty when the join is ready
     */
    static List<String> blockers(String joinId, String scopeTokenId, ExecutionContext ctx) {
        Set<String> reaching = ctx.facet(InclusiveJoinCompileStep.JOIN_SOURCES).reaching(joinId);
        List<String> out = new ArrayList<>();
        for (Token other : ctx.tree().children(scopeTokenId)) {
            boolean counts = other.status().live() || other.status() == TokenStatus.JOINED;
            if (counts && !other.nodeId().equals(joinId) && reaching.contains(other.nodeId())) {
                out.add(other.nodeId());
            }
        }
        return InclusiveGatewayBehavior.sorted(out);
    }

    private static boolean scopeIsOpen(String scopeTokenId, ExecutionContext ctx) {
        return scopeTokenId != null && ctx.tree().get(scopeTokenId).map(t -> t.status().live()).orElse(false);
    }

    /** The arrivals complete, and one new token leaves the join through its split. */
    private static void fire(InclusiveGateway join, String scopeTokenId, ExecutionContext ctx) {
        int arrivals = 0;
        for (Token arrived : ctx.tree().children(scopeTokenId)) {
            if (arrived.status() == TokenStatus.JOINED && arrived.nodeId().equals(join.id())) {
                ctx.update(arrived.withStatus(TokenStatus.COMPLETED));
                arrivals++;
            }
        }
        Token out = ctx.createToken(TokenKind.FLOW, join.id(), scopeTokenId);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("arrived", arrivals);
        ctx.history("JOIN_FIRED", out, details);
        InclusiveGatewayBehavior.split(join, out, ctx);
    }

    /**
     * Two joins that each wait for a token that sits at the other can never fire; failing the command shows the
     * definition fault instead of leaving the instance parked for ever.
     */
    private static void failWhenStuck(ExecutionContext ctx, InclusiveJoinCompileStep.JoinSources joins) {
        for (Token waiting : ctx.tree().all()) {
            if (waiting.status() == TokenStatus.JOINED && waiting.kind() == TokenKind.FLOW
                    && ctx.process().node(waiting.nodeId()).orElse(null) instanceof InclusiveGateway g
                    && joins.isJoin(g.id()) && scopeIsOpen(waiting.scopeTokenId(), ctx)
                    && ctx.tree().live(waiting.scopeTokenId()).isEmpty()) {
                throw new ExecutionException("JOIN_DEADLOCK", g.id(), "Inclusive join " + g.id()
                        + " waits for a token that sits at another join, and nothing else can move");
            }
        }
    }
}
