package io.github.dhluong90.bpm.process.internal.exec.behavior.event;

import io.github.dhluong90.bpm.process.internal.exec.EventDefinitionKey;
import io.github.dhluong90.bpm.process.internal.exec.ExecutionContext;
import io.github.dhluong90.bpm.process.internal.exec.FlowNodeBehavior;
import io.github.dhluong90.bpm.process.internal.exec.Token;
import io.github.dhluong90.bpm.process.internal.model.bpmn.SignalEventDefinition;
import io.github.dhluong90.bpm.process.internal.model.bpmn.StartEvent;
import java.util.Map;

/**
 * Signal start event (#492): a broadcast of its name in the tenant starts one instance, and a start request starts it
 * too. Once the instance exists the event just leaves by its flow.
 */
final class SignalStartEventBehavior implements FlowNodeBehavior<StartEvent> {

    @Override
    public Class<StartEvent> nodeType() {
        return StartEvent.class;
    }

    @Override
    public EventDefinitionKey eventDefinition() {
        return EventDefinitionKey.of(SignalEventDefinition.class);
    }

    @Override
    public void enter(StartEvent node, Token token, ExecutionContext ctx) {
        ctx.history("NODE_COMPLETED", token, Map.of());
        ctx.takeOutgoing(token, node);
    }
}
