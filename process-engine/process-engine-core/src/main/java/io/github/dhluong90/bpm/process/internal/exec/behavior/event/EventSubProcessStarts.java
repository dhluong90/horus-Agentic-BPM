package io.github.dhluong90.bpm.process.internal.exec.behavior.event;

import io.github.dhluong90.bpm.process.internal.exec.Command;
import io.github.dhluong90.bpm.process.internal.exec.Effect;
import io.github.dhluong90.bpm.process.internal.exec.EventDefinitionKey;
import io.github.dhluong90.bpm.process.internal.exec.ExecutionContext;
import io.github.dhluong90.bpm.process.internal.exec.ExecutionException;
import io.github.dhluong90.bpm.process.internal.exec.FlowNodeBehavior;
import io.github.dhluong90.bpm.process.internal.exec.Token;
import io.github.dhluong90.bpm.process.internal.exec.TokenKind;
import io.github.dhluong90.bpm.process.internal.exec.TokenStatus;
import io.github.dhluong90.bpm.process.internal.model.bpmn.CompensateEventDefinition;
import io.github.dhluong90.bpm.process.internal.model.bpmn.ErrorEventDefinition;
import io.github.dhluong90.bpm.process.internal.model.bpmn.EscalationEventDefinition;
import io.github.dhluong90.bpm.process.internal.model.bpmn.EventDefinition;
import io.github.dhluong90.bpm.process.internal.model.bpmn.StartEvent;
import io.github.dhluong90.bpm.process.internal.model.bpmn.TimerEventDefinition;
import java.time.Instant;
import java.util.Map;

/**
 * What the start events of event sub-processes share (#487). A start event of an event sub-process is entered in two
 * ways. When its scope opens, the kernel arms the ones that wait on a timer, a message, a signal or a condition: a
 * token waits on the start event of the scope it stands in, the same kind of wait the matching intermediate catch has,
 * and the scope does not count it as work. When the trigger arrives, the waiting token starts the event sub-process
 * ({@link ExecutionContext#startEventSubProcess}), and the new token that enters the start event simply leaves it.
 * Error, escalation and compensation start events wait for nothing: a throw finds them.
 */
final class EventSubProcessStarts {

    /** The local that marks a conditional listener whose condition has held since it last fired. */
    static final String MET = "$met";

    private EventSubProcessStarts() {
    }

    /** @return whether the token waits for the trigger of the event sub-process that owns the start event */
    static boolean listening(StartEvent node, Token token, ExecutionContext ctx) {
        String owner = ctx.process().containerOf(node.id());
        if (!ctx.process().isEventSubProcess(owner) || token.scopeTokenId() == null) {
            return false;
        }
        return ctx.tree().get(token.scopeTokenId()).map(scope -> !scope.nodeId().equals(owner)).orElse(false);
    }

    /** @return the scope an armed start event stands in, the one whose end drops the wait */
    static Token scopeOf(Token listener, ExecutionContext ctx) {
        return ctx.tree().get(listener.scopeTokenId()).orElseThrow(() -> new ExecutionException("TOKEN_NOT_FOUND",
                listener.nodeId(), "No scope token " + listener.scopeTokenId()));
    }

    /** The new token enters the start event of a started event sub-process: it leaves at once. */
    static void leave(StartEvent node, Token token, ExecutionContext ctx) {
        ctx.history("NODE_COMPLETED", token, Map.of());
        ctx.takeOutgoing(token, node);
    }

    /** Timer start event: one {@code TIMER} job while armed, the same job kind and lease as an intermediate timer. */
    static final class Timer implements FlowNodeBehavior<StartEvent> {

        @Override
        public Class<StartEvent> nodeType() {
            return StartEvent.class;
        }

        @Override
        public EventDefinitionKey eventDefinition() {
            return EventDefinitionKey.of(TimerEventDefinition.class);
        }

        @Override
        public void enter(StartEvent node, Token token, ExecutionContext ctx) {
            if (!listening(node, token, ctx)) {
                leave(node, token, ctx);
                return;
            }
            TimerEventDefinition timer = (TimerEventDefinition) node.eventDefinitions().get(0);
            Instant dueAt;
            try {
                dueAt = TimerSchedule.dueAt(timer, ctx.now());
            } catch (IllegalArgumentException e) {
                throw new ExecutionException("TIMER_INVALID", node.id(), e.getMessage());
            }
            String jobId = ctx.nextId();
            ctx.update(new Token(token.id(), token.kind(), TokenStatus.WAITING_TIMER, token.scopeTokenId(),
                    token.nodeId(), jobId, token.locals()));
            ctx.effect(new Effect.ScheduleJob(jobId, TimerCatchEventBehavior.JOB_KIND, token.id(), node.id(), dueAt,
                    Map.of()));
            ctx.history("TIMER_SCHEDULED", token, Map.of("jobId", jobId, "dueAt", dueAt.toString(),
                    "eventSubProcess", ctx.process().containerOf(node.id())));
        }

        @Override
        public void trigger(StartEvent node, Token token, Command trigger, ExecutionContext ctx) {
            if (!(trigger instanceof Command.JobDue due) || !TimerCatchEventBehavior.JOB_KIND.equals(due.jobKind())) {
                throw new ExecutionException("COMMAND_NOT_SUPPORTED", node.id(), "Expected a timer job");
            }
            ctx.history("TIMER_FIRED", token, Map.of("jobId", due.jobId()));
            ctx.update(token.withStatus(TokenStatus.COMPLETED));
            ctx.startEventSubProcess(scopeOf(token, ctx), node, "TIMER", Map.of(), null);
        }
    }

    /** Error, escalation and compensation start events: nothing waits on them, and a started one just leaves. */
    static final class Passive implements FlowNodeBehavior<StartEvent> {

        private final Class<? extends EventDefinition> definition;

        private Passive(Class<? extends EventDefinition> definition) {
            this.definition = definition;
        }

        static Passive error() {
            return new Passive(ErrorEventDefinition.class);
        }

        static Passive escalation() {
            return new Passive(EscalationEventDefinition.class);
        }

        static Passive compensation() {
            return new Passive(CompensateEventDefinition.class);
        }

        @Override
        public Class<StartEvent> nodeType() {
            return StartEvent.class;
        }

        @Override
        public EventDefinitionKey eventDefinition() {
            return EventDefinitionKey.of(definition);
        }

        @Override
        public void enter(StartEvent node, Token token, ExecutionContext ctx) {
            leave(node, token, ctx);
        }
    }

    /** A new listener on the same start event, for a non-interrupting trigger that may come again. */
    static Token again(StartEvent node, Token consumed, ExecutionContext ctx) {
        return ctx.createToken(TokenKind.FLOW, node.id(), consumed.scopeTokenId());
    }
}
