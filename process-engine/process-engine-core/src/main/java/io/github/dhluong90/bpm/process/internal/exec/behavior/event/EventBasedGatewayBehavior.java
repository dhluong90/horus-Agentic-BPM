package io.github.dhluong90.bpm.process.internal.exec.behavior.event;

import io.github.dhluong90.bpm.process.internal.exec.ExecutionContext;
import io.github.dhluong90.bpm.process.internal.exec.FlowNodeBehavior;
import io.github.dhluong90.bpm.process.internal.exec.Token;
import io.github.dhluong90.bpm.process.internal.exec.TokenKind;
import io.github.dhluong90.bpm.process.internal.exec.TokenStatus;
import io.github.dhluong90.bpm.process.internal.model.bpmn.EventBasedGateway;
import io.github.dhluong90.bpm.process.internal.model.bpmn.SequenceFlow;
import java.util.List;
import java.util.Map;

/**
 * Event-based gateway (#495). The arriving token ends and one token per outgoing flow enters the catch event or
 * receive task it leads to, so a message, signal or timer subscription or a condition wait exists for every
 * alternative in this transaction. The first of them to fire wins: the kernel cancels the others, with their
 * subscriptions and timers, in the transaction of the winner ({@code WorkingContext#takeOutgoing}). The shell
 * applies every command of an instance under its row lock, so two events that arrive together on two replicas run one
 * after the other and the second finds its token cancelled.
 */
final class EventBasedGatewayBehavior implements FlowNodeBehavior<EventBasedGateway> {

    @Override
    public Class<EventBasedGateway> nodeType() {
        return EventBasedGateway.class;
    }

    @Override
    public void enter(EventBasedGateway node, Token token, ExecutionContext ctx) {
        List<SequenceFlow> outgoing = ctx.process().outgoing(node.id());
        ctx.update(token.withStatus(TokenStatus.COMPLETED));
        ctx.history("EVENT_GATEWAY_WAITING", token, Map.of("catches", outgoing.stream().map(SequenceFlow::targetRef)
                .toList()));
        for (SequenceFlow flow : outgoing) {
            ctx.take(ctx.createToken(TokenKind.FLOW, node.id(), token.scopeTokenId()), flow.id());
        }
    }
}
