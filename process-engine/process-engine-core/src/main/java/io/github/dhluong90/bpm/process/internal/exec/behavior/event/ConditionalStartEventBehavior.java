package io.github.dhluong90.bpm.process.internal.exec.behavior.event;

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
 * whose condition is false is refused with {@code START_CONDITION_FALSE} and stores nothing. The wait for a change of
 * variables belongs to a conditional start inside an event sub-process, which comes with event sub-processes.
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
        ConditionalEventDefinition definition = (ConditionalEventDefinition) node.eventDefinitions().get(0);
        if (!Conditions.holdsOnArrival(definition, token, ctx)) {
            throw new ExecutionException("START_CONDITION_FALSE", node.id(), "The start condition is not true for the"
                    + " variables of this start");
        }
        ctx.history("CONDITION_MET", token, Map.of("onArrival", true));
        ctx.history("NODE_COMPLETED", token, Map.of());
        ctx.takeOutgoing(token, node);
    }
}
