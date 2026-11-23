package io.github.dhluong90.bpm.process.internal.exec.behavior.event;

import io.github.dhluong90.bpm.process.internal.exec.Command;
import io.github.dhluong90.bpm.process.internal.exec.TokenStatus;
import io.github.dhluong90.bpm.process.internal.exec.EventDefinitionKey;
import io.github.dhluong90.bpm.process.internal.exec.ExecutionContext;
import io.github.dhluong90.bpm.process.internal.exec.ExecutionException;
import io.github.dhluong90.bpm.process.internal.exec.FlowNodeBehavior;
import io.github.dhluong90.bpm.process.internal.exec.Token;
import io.github.dhluong90.bpm.process.internal.model.bpmn.ConditionalEventDefinition;
import io.github.dhluong90.bpm.process.internal.model.bpmn.StartEvent;
import java.util.Map;

/**
 * Conditional start event (#492): the instance starts only when the condition is true for the start variables. A start
 * whose condition is false is refused with {@code START_CONDITION_FALSE} and stores nothing. Inside an event
 * sub-process (#487) the event waits instead: the token holds no row, and the kernel asks the condition again in the
 * transaction of every change of the instance's variables. An interrupting one fires once. A non-interrupting one
 * fires when the condition becomes true and again only after it has been false, so a condition that stays true does not
 * start the handler on every change.
 */
final class ConditionalStartEventBehavior implements FlowNodeBehavior<StartEvent> {

    @Override
    public Class<StartEvent> nodeType() {
        return StartEvent.class;
    }

    @Override
    public EventDefinitionKey eventDefinition() {
        return EventDefinitionKey.of(ConditionalEventDefinition.class);
    }

    @Override
    public void enter(StartEvent node, Token token, ExecutionContext ctx) {
        if (EventSubProcessStarts.listening(node, token, ctx)) {
            ctx.update(new Token(token.id(), token.kind(), TokenStatus.WAITING_EVENT, token.scopeTokenId(),
                    token.nodeId(), ctx.nextId(), token.locals()));
            ctx.history("CONDITION_WAITING", token, Map.of());
            ctx.conditionArmed();
            return;
        }
        if (ctx.process().isEventSubProcess(ctx.process().containerOf(node.id()))) {
            EventSubProcessStarts.leave(node, token, ctx);
            return;
        }
        ConditionalEventDefinition definition = (ConditionalEventDefinition) node.eventDefinitions().get(0);
        if (!Conditions.holdsOnArrival(definition, token, ctx)) {
            throw new ExecutionException("START_CONDITION_FALSE", node.id(), "The start condition is not true for the"
                    + " variables of this start");
        }
        ctx.history("CONDITION_MET", token, Map.of("onArrival", true));
        ctx.history("NODE_COMPLETED", token, Map.of());
        ctx.takeOutgoing(token, node);
    }

    @Override
    public void trigger(StartEvent node, Token token, Command trigger, ExecutionContext ctx) {
        if (!(trigger instanceof Command.VariablesChanged)) {
            throw new ExecutionException("COMMAND_NOT_SUPPORTED", node.id(), "Expected a change of the variables");
        }
        ConditionalEventDefinition definition = (ConditionalEventDefinition) node.eventDefinitions().get(0);
        boolean met = Boolean.TRUE.equals(token.locals().get(EventSubProcessStarts.MET));
        if (!Conditions.holdsNow(definition, token, ctx)) {
            if (met) {
                ctx.update(new Token(token.id(), token.kind(), token.status(), token.scopeTokenId(), token.nodeId(),
                        token.activityInstanceId(), Map.of()));
            }
            return;
        }
        if (met) {
            return;
        }
        Token scope = EventSubProcessStarts.scopeOf(token, ctx);
        ctx.history("CONDITION_MET", token, Map.of("onArrival", false));
        if (node.isInterrupting()) {
            ctx.update(token.withStatus(TokenStatus.COMPLETED));
        } else {
            ctx.update(new Token(token.id(), token.kind(), token.status(), token.scopeTokenId(), token.nodeId(),
                    token.activityInstanceId(), Map.of(EventSubProcessStarts.MET, true)));
        }
        ctx.startEventSubProcess(scope, node, "CONDITIONAL", Map.of(), null);
    }
}
