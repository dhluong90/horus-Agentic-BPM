package io.github.dhluong90.bpm.process.internal.exec.behavior.scope;

import io.github.dhluong90.bpm.process.internal.exec.EventDefinitionKey;
import io.github.dhluong90.bpm.process.internal.exec.ExecutionContext;
import io.github.dhluong90.bpm.process.internal.exec.FlowNodeBehavior;
import io.github.dhluong90.bpm.process.internal.exec.Token;
import io.github.dhluong90.bpm.process.internal.model.bpmn.CancelEventDefinition;
import io.github.dhluong90.bpm.process.internal.model.bpmn.CompensateEventDefinition;
import io.github.dhluong90.bpm.process.internal.model.bpmn.EndEvent;

/**
 * The two end events of #488. A compensation end event undoes the completed steps of its scope, whole or one activity,
 * and ends its token once the undo is done. A cancel end event, inside a transaction, compensates the transaction and
 * leaves it through its cancel boundary event.
 */
final class CompensationEndBehavior {

    private CompensationEndBehavior() {
    }

    /** End event with a compensate definition. */
    static final class Compensate implements FlowNodeBehavior<EndEvent> {
        @Override
        public Class<EndEvent> nodeType() {
            return EndEvent.class;
        }

        @Override
        public EventDefinitionKey eventDefinition() {
            return EventDefinitionKey.of(CompensateEventDefinition.class);
        }

        @Override
        public void enter(EndEvent node, Token token, ExecutionContext ctx) {
            CompensateEventDefinition definition = (CompensateEventDefinition) node.eventDefinitions().get(0);
            if (!definition.waitForCompletion()) {
                // PD-149: the token ends now; the undo goes on in the background and holds the scope open.
                ctx.compensateInBackground(token, definition.activityRef());
                ctx.history("NODE_COMPLETED", token, java.util.Map.of("background", true));
                ctx.complete(token);
                return;
            }
            ctx.compensateAndResume(token, definition.activityRef());
        }
    }

    /** End event with a cancel definition. */
    static final class Cancel implements FlowNodeBehavior<EndEvent> {
        @Override
        public Class<EndEvent> nodeType() {
            return EndEvent.class;
        }

        @Override
        public EventDefinitionKey eventDefinition() {
            return EventDefinitionKey.of(CancelEventDefinition.class);
        }

        @Override
        public void enter(EndEvent node, Token token, ExecutionContext ctx) {
            ctx.cancelTransaction(token);
        }
    }
}
