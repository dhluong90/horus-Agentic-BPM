package io.github.dhluong90.bpm.process.internal.exec.behavior.core;

import io.github.dhluong90.bpm.process.internal.exec.ExecutionContext;
import io.github.dhluong90.bpm.process.internal.exec.FlowNodeBehavior;
import io.github.dhluong90.bpm.process.internal.exec.Token;
import io.github.dhluong90.bpm.process.internal.exec.TokenKind;
import io.github.dhluong90.bpm.process.internal.exec.TokenStatus;
import io.github.dhluong90.bpm.process.internal.model.bpmn.ParallelGateway;
import io.github.dhluong90.bpm.process.internal.model.bpmn.SequenceFlow;
import java.util.List;
import java.util.Map;

/**
 * Parallel gateway (PRE-005, PRE-044). With one incoming flow it forks: the token leaves on every outgoing flow,
 * one token per flow, all under the scope token of the arriving token. With several incoming flows it joins: the
 * arriving token becomes {@code JOINED}, and the arrival that brings the {@code JOINED} tokens on this gateway under
 * the same scope token to the number of incoming flows marks them {@code COMPLETED} and creates one new token that
 * leaves the gateway, forking again when the gateway has several outgoing flows. Tokens of other scope tokens are
 * never counted.
 *
 * <p>The kernel is a pure function of the instance state; the shell applies every command of an instance under its
 * {@code pre_instance} row lock and reads the tokens after taking it. Two branches finishing at the same moment on
 * two replicas therefore apply one after the other, and only the second sees the set complete: one post-join token,
 * never zero and never two. Outgoing conditions are refused at activation ({@code CONDITION_ON_PARALLEL_FLOW}).
 */
final class ParallelGatewayBehavior implements FlowNodeBehavior<ParallelGateway> {

    @Override
    public Class<ParallelGateway> nodeType() {
        return ParallelGateway.class;
    }

    @Override
    public void enter(ParallelGateway node, Token token, ExecutionContext ctx) {
        int expected = ctx.facet(ParallelJoinCompileStep.JOIN_ARITY).of(node.id());
        if (expected <= 1) {
            leave(node, token, ctx);
            return;
        }
        Token joined = token.withStatus(TokenStatus.JOINED);
        ctx.update(joined);
        List<Token> waiting = ctx.tree().children(token.scopeTokenId()).stream()
                .filter(t -> t.kind() == TokenKind.FLOW && t.status() == TokenStatus.JOINED
                        && t.nodeId().equals(node.id()))
                .toList();
        ctx.history("TOKEN_JOINED", joined, Map.of("arrived", waiting.size(), "expected", expected));
        if (waiting.size() < expected) {
            return;
        }
        // The first tokens in creation order complete the set; a further arrival over a loop waits for the next one.
        for (Token arrived : waiting.subList(0, expected)) {
            ctx.update(arrived.withStatus(TokenStatus.COMPLETED));
        }
        leave(node, ctx.createToken(TokenKind.FLOW, node.id(), token.scopeTokenId()), ctx);
    }

    /** Moves the token onto the first outgoing flow and a new token of the same scope onto each other one. */
    private static void leave(ParallelGateway node, Token token, ExecutionContext ctx) {
        List<SequenceFlow> outgoing = ctx.process().outgoing(node.id());
        ctx.history("NODE_COMPLETED", token, Map.of("flows", outgoing.stream().map(SequenceFlow::id).toList()));
        ctx.take(token, outgoing.get(0).id());
        for (SequenceFlow flow : outgoing.subList(1, outgoing.size())) {
            ctx.take(ctx.createToken(TokenKind.FLOW, node.id(), token.scopeTokenId()), flow.id());
        }
    }
}
