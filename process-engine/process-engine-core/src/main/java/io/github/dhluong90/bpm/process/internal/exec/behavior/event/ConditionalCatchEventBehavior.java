package io.github.dhluong90.bpm.process.internal.exec.behavior.event;

import io.github.dhluong90.bpm.process.internal.exec.Command;
import io.github.dhluong90.bpm.process.internal.exec.EventDefinitionKey;
import io.github.dhluong90.bpm.process.internal.exec.ExecutionContext;
import io.github.dhluong90.bpm.process.internal.exec.ExecutionException;
import io.github.dhluong90.bpm.process.internal.exec.FlowNodeBehavior;
import io.github.dhluong90.bpm.process.internal.exec.Token;
import io.github.dhluong90.bpm.process.internal.exec.TokenStatus;
import io.github.dhluong90.bpm.process.internal.model.bpmn.ConditionalEventDefinition;
import io.github.dhluong90.bpm.process.internal.model.bpmn.IntermediateCatchEvent;
import java.util.Map;

/**
 * Intermediate conditional catch event (#492): the token passes at once when the condition is true on arrival,
 * otherwise it waits in {@code WAITING_EVENT} and the kernel asks again in the transaction that changes the instance's
 * variables. The wait stores nothing: the token is the subscription, so no row outlives the instance.
 */
final class ConditionalCatchEventBehavior implements FlowNodeBehavior<IntermediateCatchEvent> {

    @Override
    public Class<IntermediateCatchEvent> nodeType() {
        return IntermediateCatchEvent.class;
    }

    @Override
    public EventDefinitionKey eventDefinition() {
        return EventDefinitionKey.of(ConditionalEventDefinition.class);
    }

    @Override
    public void enter(IntermediateCatchEvent node, Token token, ExecutionContext ctx) {
        ConditionalEventDefinition definition = (ConditionalEventDefinition) node.eventDefinitions().get(0);
        if (Conditions.holdsOnArrival(definition, token, ctx)) {
            ctx.history("CONDITION_MET", token, Map.of("onArrival", true));
            ctx.history("NODE_COMPLETED", token, Map.of());
            ctx.takeOutgoing(token, node);
            return;
        }
        ctx.update(new Token(token.id(), token.kind(), TokenStatus.WAITING_EVENT, token.scopeTokenId(), token.nodeId(),
                ctx.nextId(), token.locals()));
        ctx.history("CONDITION_WAITING", token, Map.of());
    }

    @Override
    public void trigger(IntermediateCatchEvent node, Token token, Command trigger, ExecutionContext ctx) {
        if (!(trigger instanceof Command.VariablesChanged)) {
            throw new ExecutionException("COMMAND_NOT_SUPPORTED", node.id(), "Expected a change of the variables");
        }
        ConditionalEventDefinition definition = (ConditionalEventDefinition) node.eventDefinitions().get(0);
        if (!Conditions.holdsNow(definition, token, ctx)) {
            return;
        }
        Token active = token.withStatus(TokenStatus.ACTIVE);
        ctx.update(active);
        ctx.history("CONDITION_MET", token, Map.of("onArrival", false));
        ctx.history("NODE_COMPLETED", token, Map.of());
        ctx.takeOutgoing(active, node);
    }
}
