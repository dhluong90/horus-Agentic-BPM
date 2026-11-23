package io.github.dhluong90.bpm.process.internal.exec.behavior.gateway;

import io.github.dhluong90.bpm.process.internal.exec.CompileStep;
import io.github.dhluong90.bpm.process.internal.exec.CompiledProcess;
import io.github.dhluong90.bpm.process.internal.exec.ExecutionContext;
import io.github.dhluong90.bpm.process.internal.exec.ExecutionException;
import io.github.dhluong90.bpm.process.internal.exec.FacetKey;
import io.github.dhluong90.bpm.process.internal.exec.FlowNodeBehavior;
import io.github.dhluong90.bpm.process.internal.exec.Token;
import io.github.dhluong90.bpm.process.internal.exec.TokenKind;
import io.github.dhluong90.bpm.process.internal.exec.TokenStatus;
import io.github.dhluong90.bpm.process.internal.model.bpmn.ComplexGateway;
import io.github.dhluong90.bpm.process.internal.model.bpmn.FlowElement;
import io.github.dhluong90.bpm.process.internal.model.bpmn.Process;
import io.github.dhluong90.bpm.process.internal.model.bpmn.SequenceFlow;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Complex gateway (#500, BPMN 2.0.2 section 13.3.4). With one incoming flow it only splits. With several it joins:
 * every arriving token is held {@code JOINED} and counted, and the number held is {@code _activationCount} for the
 * {@code activationCondition}. The arrival that makes the condition true fires the gateway once: one token leaves
 * through the outgoing flows (those whose condition holds, else the default flow, as at any split). A gateway without
 * an activation condition fires when every incoming flow has delivered, like a parallel join. Once fired, the tokens
 * that arrive later are absorbed, and when every incoming flow has delivered its token the gateway resets for the next
 * round; the absorbed token ends through the context, so a scope whose other branches already ended completes then. All of it runs under the instance row lock, so two branches arriving together are applied one after the other.
 */
final class ComplexGatewayBehavior implements FlowNodeBehavior<ComplexGateway> {

    /** The name an activation condition reads the number of tokens that have arrived in the round by. */
    static final String ACTIVATION_COUNT = "_activationCount";
    private static final String FIRED = "$complexFired";

    /** Incoming-flow count per complex gateway id. */
    record Arity(Map<String, Integer> incoming) {
        Arity {
            incoming = Map.copyOf(incoming);
        }

        int of(String gatewayId) {
            return incoming.getOrDefault(gatewayId, 0);
        }
    }

    static final FacetKey<Arity> ARITY = new FacetKey<>("gateway.complex-arity", Arity.class);

    /** Counts the incoming flows of every complex gateway once per definition version. */
    static final class ArityStep implements CompileStep {
        @Override
        public void compile(Process process, CompiledProcess.Builder builder) {
            Map<String, Integer> gateways = new HashMap<>();
            for (FlowElement e : process.allFlowElements()) {
                if (e instanceof ComplexGateway g) {
                    gateways.put(g.id(), 0);
                }
            }
            for (FlowElement e : process.allFlowElements()) {
                if (e instanceof SequenceFlow f && gateways.containsKey(f.targetRef())) {
                    gateways.merge(f.targetRef(), 1, Integer::sum);
                }
            }
            builder.facet(ARITY, new Arity(gateways));
        }
    }

    @Override
    public Class<ComplexGateway> nodeType() {
        return ComplexGateway.class;
    }

    @Override
    public void enter(ComplexGateway node, Token token, ExecutionContext ctx) {
        int expected = ctx.facet(ARITY).of(node.id());
        if (expected <= 1) {
            ctx.history("NODE_COMPLETED", token, Map.of());
            ctx.takeOutgoing(token, node);
            return;
        }
        List<Token> held = ctx.tree().children(token.scopeTokenId()).stream()
                .filter(t -> t.kind() == TokenKind.FLOW && t.status() == TokenStatus.JOINED
                        && t.nodeId().equals(node.id()) && !t.id().equals(token.id()))
                .toList();
        int arrived = held.size() + 1;
        boolean alreadyFired = held.stream().anyMatch(t -> Boolean.TRUE.equals(t.locals().get(FIRED)));
        Map<String, Object> locals = new LinkedHashMap<>(token.locals());
        locals.put(ACTIVATION_COUNT, arrived);
        Token joined = new Token(token.id(), token.kind(), TokenStatus.JOINED, token.scopeTokenId(), token.nodeId(),
                null, locals);
        ctx.update(joined);
        if (alreadyFired) {
            ctx.history("TOKEN_ABSORBED", joined, Map.of("arrived", arrived, "expected", expected));
            reset(ctx, held, joined, arrived, expected, true);
            return;
        }
        ctx.history("TOKEN_JOINED", joined, Map.of("arrived", arrived, "expected", expected));
        boolean met;
        if (node.activationCondition() == null || node.activationCondition().text() == null) {
            met = arrived >= expected;
        } else {
            Object value = ctx.evaluate(node.activationCondition().text(), node.activationCondition().language(),
                    joined);
            if (!(value instanceof Boolean b)) {
                throw new ExecutionException("CONDITION_ERROR", node.id(), "The activation condition of " + node.id()
                        + " did not produce a boolean");
            }
            met = b;
        }
        if (!met) {
            if (arrived >= expected) {
                throw new ExecutionException("ACTIVATION_CONDITION_FALSE", node.id(), "Every incoming flow of "
                        + node.id() + " delivered a token and the activation condition is still false");
            }
            return;
        }
        Map<String, Object> marked = new LinkedHashMap<>(locals);
        marked.put(FIRED, true);
        Token fired = new Token(joined.id(), joined.kind(), TokenStatus.JOINED, joined.scopeTokenId(), joined.nodeId(),
                null, marked);
        ctx.update(fired);
        ctx.history("COMPLEX_FIRED", fired, Map.of("arrived", arrived, "expected", expected));
        reset(ctx, held, fired, arrived, expected, false);
        Token out = ctx.createToken(TokenKind.FLOW, node.id(), token.scopeTokenId());
        ctx.history("NODE_COMPLETED", out, Map.of());
        ctx.takeOutgoing(out, node);
    }

    /** When every incoming flow has delivered, the round is over: the held tokens end and the gateway starts again. */
    private static void reset(ExecutionContext ctx, List<Token> held, Token last, int arrived, int expected,
            boolean settleScope) {
        if (arrived < expected) {
            return;
        }
        for (Token t : held) {
            ctx.update(t.withStatus(TokenStatus.COMPLETED));
        }
        if (settleScope) {
            // The absorbed token is the last live one when the branch that fired has already ended: complete it
            // through the context so the enclosing scope is asked whether it has any work left.
            ctx.complete(last);
        } else {
            ctx.update(last.withStatus(TokenStatus.COMPLETED));
        }
    }
}
