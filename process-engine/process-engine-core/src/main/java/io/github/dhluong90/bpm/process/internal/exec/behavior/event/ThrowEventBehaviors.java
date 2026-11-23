package io.github.dhluong90.bpm.process.internal.exec.behavior.event;

import io.github.dhluong90.bpm.process.internal.exec.CompiledProcess;
import io.github.dhluong90.bpm.process.internal.exec.Effect;
import io.github.dhluong90.bpm.process.internal.exec.EventDefinitionKey;
import io.github.dhluong90.bpm.process.internal.exec.ExecutionContext;
import io.github.dhluong90.bpm.process.internal.exec.ExecutionException;
import io.github.dhluong90.bpm.process.internal.exec.FlowNodeBehavior;
import io.github.dhluong90.bpm.process.internal.exec.Token;
import io.github.dhluong90.bpm.process.internal.exec.TokenStatus;
import io.github.dhluong90.bpm.process.internal.model.bpmn.EndEvent;
import io.github.dhluong90.bpm.process.internal.model.bpmn.ErrorEventDefinition;
import io.github.dhluong90.bpm.process.internal.model.bpmn.EscalationEventDefinition;
import io.github.dhluong90.bpm.process.internal.model.bpmn.FlowNode;
import io.github.dhluong90.bpm.process.internal.model.bpmn.IntermediateCatchEvent;
import io.github.dhluong90.bpm.process.internal.model.bpmn.IntermediateThrowEvent;
import io.github.dhluong90.bpm.process.internal.model.bpmn.LinkEventDefinition;
import io.github.dhluong90.bpm.process.internal.model.bpmn.TerminateEventDefinition;
import java.util.Map;

/**
 * Throw events of #493: error end, escalation end and intermediate, terminate end, and the link throw and catch pair.
 * One small behaviour each; the work of finding a catcher, cancelling and ending a scope is in the execution context.
 */
final class ThrowEventBehaviors {

    /** Same code as a step's business error that nothing catches, so one incident kind covers both. */
    static final String UNHANDLED = "UNHANDLED_BUSINESS_ERROR";

    private ThrowEventBehaviors() {
    }

    /** Error end event: a catching boundary takes over, and an error nothing catches opens an incident. */
    static final class ErrorEnd implements FlowNodeBehavior<EndEvent> {
        @Override
        public Class<EndEvent> nodeType() {
            return EndEvent.class;
        }

        @Override
        public EventDefinitionKey eventDefinition() {
            return EventDefinitionKey.of(ErrorEventDefinition.class);
        }

        @Override
        public void enter(EndEvent node, Token token, ExecutionContext ctx) {
            String code = ((ErrorEventDefinition) node.eventDefinitions().get(0)).errorCode();
            if (ctx.throwError(token, code, false)) {
                return;
            }
            // The token stays on the end event so an operator sees where it stopped; the incident carries the code.
            String attemptId = ctx.nextId();
            ctx.update(new Token(token.id(), token.kind(), TokenStatus.WAITING_ASYNC, token.scopeTokenId(),
                    token.nodeId(), attemptId, token.locals()));
            ctx.effect(new Effect.RaiseIncident(token.id(), node.id(), UNHANDLED, code));
        }
    }

    /** Escalation end event: caught or not, the token ends; an escalation nothing catches is only recorded. */
    static final class EscalationEnd implements FlowNodeBehavior<EndEvent> {
        @Override
        public Class<EndEvent> nodeType() {
            return EndEvent.class;
        }

        @Override
        public EventDefinitionKey eventDefinition() {
            return EventDefinitionKey.of(EscalationEventDefinition.class);
        }

        @Override
        public void enter(EndEvent node, Token token, ExecutionContext ctx) {
            String code = ((EscalationEventDefinition) node.eventDefinitions().get(0)).escalationCode();
            if (ctx.throwEscalation(token, code)) {
                return;
            }
            ctx.history("NODE_COMPLETED", token, Map.of());
            ctx.complete(token);
        }
    }

    /** Escalation intermediate throw: unless an interrupting boundary took the host, the flow goes on. */
    static final class EscalationThrow implements FlowNodeBehavior<IntermediateThrowEvent> {
        @Override
        public Class<IntermediateThrowEvent> nodeType() {
            return IntermediateThrowEvent.class;
        }

        @Override
        public EventDefinitionKey eventDefinition() {
            return EventDefinitionKey.of(EscalationEventDefinition.class);
        }

        @Override
        public void enter(IntermediateThrowEvent node, Token token, ExecutionContext ctx) {
            String code = ((EscalationEventDefinition) node.eventDefinitions().get(0)).escalationCode();
            if (ctx.throwEscalation(token, code)) {
                return;
            }
            ctx.history("NODE_COMPLETED", token, Map.of());
            ctx.takeOutgoing(token, node);
        }
    }

    /** Terminate end event: every other token of the scope is cancelled and the scope ends. */
    static final class TerminateEnd implements FlowNodeBehavior<EndEvent> {
        @Override
        public Class<EndEvent> nodeType() {
            return EndEvent.class;
        }

        @Override
        public EventDefinitionKey eventDefinition() {
            return EventDefinitionKey.of(TerminateEventDefinition.class);
        }

        @Override
        public void enter(EndEvent node, Token token, ExecutionContext ctx) {
            ctx.history("NODE_COMPLETED", token, Map.of());
            ctx.terminate(token);
        }
    }

    /** Link throw: the token jumps to the link catch of the same name in the same scope. */
    static final class LinkThrow implements FlowNodeBehavior<IntermediateThrowEvent> {
        @Override
        public Class<IntermediateThrowEvent> nodeType() {
            return IntermediateThrowEvent.class;
        }

        @Override
        public EventDefinitionKey eventDefinition() {
            return EventDefinitionKey.of(LinkEventDefinition.class);
        }

        @Override
        public void enter(IntermediateThrowEvent node, Token token, ExecutionContext ctx) {
            String name = ((LinkEventDefinition) node.eventDefinitions().get(0)).name();
            CompiledProcess process = ctx.process();
            FlowNode target = process.linkCatch(name, process.containerOf(node.id())).orElseThrow(() ->
                    new ExecutionException("LINK_CATCH_MISSING", node.id(), "No link catch named " + name));
            ctx.history("NODE_COMPLETED", token, Map.of());
            ctx.history("LINK_JUMPED", token, Map.of("name", name, "to", target.id()));
            ctx.jump(token, target.id());
        }
    }

    /** Link catch: the other half of the pair; it only passes the token on. */
    static final class LinkCatch implements FlowNodeBehavior<IntermediateCatchEvent> {
        @Override
        public Class<IntermediateCatchEvent> nodeType() {
            return IntermediateCatchEvent.class;
        }

        @Override
        public EventDefinitionKey eventDefinition() {
            return EventDefinitionKey.of(LinkEventDefinition.class);
        }

        @Override
        public void enter(IntermediateCatchEvent node, Token token, ExecutionContext ctx) {
            ctx.history("NODE_COMPLETED", token, Map.of());
            ctx.takeOutgoing(token, node);
        }
    }
}
