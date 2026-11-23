package io.github.dhluong90.bpm.process.internal.exec.behavior.event;

import io.github.dhluong90.bpm.process.internal.exec.Effect;
import io.github.dhluong90.bpm.process.internal.exec.EventDefinitionKey;
import io.github.dhluong90.bpm.process.internal.exec.ExecutionContext;
import io.github.dhluong90.bpm.process.internal.exec.FlowNodeBehavior;
import io.github.dhluong90.bpm.process.internal.exec.Token;
import io.github.dhluong90.bpm.process.internal.model.bpmn.CompensateEventDefinition;
import io.github.dhluong90.bpm.process.internal.model.bpmn.EndEvent;
import io.github.dhluong90.bpm.process.internal.model.bpmn.EscalationEventDefinition;
import io.github.dhluong90.bpm.process.internal.model.bpmn.EventDefinition;
import io.github.dhluong90.bpm.process.internal.model.bpmn.FlowNode;
import io.github.dhluong90.bpm.process.internal.model.bpmn.IntermediateThrowEvent;
import io.github.dhluong90.bpm.process.internal.model.bpmn.MessageEventDefinition;
import io.github.dhluong90.bpm.process.internal.model.bpmn.SignalEventDefinition;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Multiple throw and end events (PD-149): an event with several definitions throws every one of them, then the flow
 * goes on (an intermediate throw) or the token ends (an end event). Messages, signals and background compensations go
 * in document order; escalations go last, because an escalation that an interrupting handler catches takes the token
 * over and the rest would never be thrown. The compiler admits only definitions that leave the flow where it is: an
 * error, terminate, cancel, link or waiting compensation is refused ({@code MULTIPLE_THROW_DEFINITION_INVALID}).
 */
final class MultipleThrows {

    private MultipleThrows() {
    }

    /** @return false when an escalation was caught and the token left through its handler */
    private static boolean throwAll(FlowNode node, List<EventDefinition> definitions, Token token, ExecutionContext ctx,
            Function<EventDefinition, FlowNode> projectOne) {
        List<EventDefinition> ordered = new ArrayList<>();
        definitions.stream().filter(d -> !(d instanceof EscalationEventDefinition)).forEach(ordered::add);
        definitions.stream().filter(EscalationEventDefinition.class::isInstance).forEach(ordered::add);
        for (EventDefinition d : ordered) {
            switch (d) {
                case MessageEventDefinition m -> MessageSupport.send(projectOne.apply(m), token, ctx);
                case SignalEventDefinition s -> ctx.effect(new Effect.BroadcastSignal(ctx.nextId(), s.signalRef(),
                        token.id(), node.id()));
                case CompensateEventDefinition c -> ctx.compensateInBackground(token, c.activityRef());
                case EscalationEventDefinition e -> {
                    if (ctx.throwEscalation(token, e.escalationCode())) {
                        return false;
                    }
                }
                default -> throw new IllegalStateException("Multiple throw " + node.id() + " holds "
                        + d.getClass().getSimpleName() + ", which the compiler refuses");
            }
        }
        return true;
    }

    /** Intermediate throw event with several definitions. */
    static final class Intermediate implements FlowNodeBehavior<IntermediateThrowEvent> {
        @Override
        public Class<IntermediateThrowEvent> nodeType() {
            return IntermediateThrowEvent.class;
        }

        @Override
        public EventDefinitionKey eventDefinition() {
            return EventDefinitionKey.MULTIPLE;
        }

        @Override
        public void enter(IntermediateThrowEvent n, Token token, ExecutionContext ctx) {
            if (throwAll(n, n.eventDefinitions(), token, ctx, d -> new IntermediateThrowEvent(n.id(), n.name(),
                    n.incoming(), n.outgoing(), List.of(d), List.of(), n.data(), n.info()))) {
                ctx.history("NODE_COMPLETED", token, Map.of("multiple", true));
                ctx.takeOutgoing(token, n);
            }
        }
    }

    /** End event with several definitions. */
    static final class End implements FlowNodeBehavior<EndEvent> {
        @Override
        public Class<EndEvent> nodeType() {
            return EndEvent.class;
        }

        @Override
        public EventDefinitionKey eventDefinition() {
            return EventDefinitionKey.MULTIPLE;
        }

        @Override
        public void enter(EndEvent n, Token token, ExecutionContext ctx) {
            if (throwAll(n, n.eventDefinitions(), token, ctx, d -> new EndEvent(n.id(), n.name(), n.incoming(),
                    n.outgoing(), List.of(d), List.of(), n.data(), n.info()))) {
                ctx.history("NODE_COMPLETED", token, Map.of("multiple", true));
                ctx.complete(token);
            }
        }
    }
}
