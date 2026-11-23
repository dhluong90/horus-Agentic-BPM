package io.github.dhluong90.bpm.process.internal.exec.behavior.event;

import io.github.dhluong90.bpm.process.internal.exec.Command;
import io.github.dhluong90.bpm.process.internal.exec.Effect;
import io.github.dhluong90.bpm.process.internal.exec.EventDefinitionKey;
import io.github.dhluong90.bpm.process.internal.exec.ExecutionContext;
import io.github.dhluong90.bpm.process.internal.exec.ExecutionException;
import io.github.dhluong90.bpm.process.internal.exec.FlowNodeBehavior;
import io.github.dhluong90.bpm.process.internal.exec.Token;
import io.github.dhluong90.bpm.process.internal.exec.TokenStatus;
import io.github.dhluong90.bpm.process.internal.model.bpmn.BoundaryEvent;
import io.github.dhluong90.bpm.process.internal.model.bpmn.TimerEventDefinition;
import java.time.Instant;
import java.util.Map;

/**
 * Timer boundary event (#489): when its host activity is entered, the kernel gives the boundary its own token, which
 * waits in {@code WAITING_TIMER} on one {@code TIMER} job, the same job kind and lease as an intermediate timer. The job
 * id is the token's activity instance id, so a job of any other visit is refused as stale. Firing interrupts the host
 * ({@code cancelActivity} true, the default) or leaves it open (false), and the token leaves by the boundary's flow.
 */
final class BoundaryTimerBehavior implements FlowNodeBehavior<BoundaryEvent> {

    @Override
    public Class<BoundaryEvent> nodeType() {
        return BoundaryEvent.class;
    }

    @Override
    public EventDefinitionKey eventDefinition() {
        return EventDefinitionKey.of(TimerEventDefinition.class);
    }

    @Override
    public void enter(BoundaryEvent node, Token token, ExecutionContext ctx) {
        TimerEventDefinition timer = (TimerEventDefinition) node.eventDefinitions().get(0);
        Instant dueAt;
        try {
            dueAt = TimerSchedule.dueAt(timer, ctx.now());
        } catch (IllegalArgumentException e) {
            throw new ExecutionException("TIMER_INVALID", node.id(), e.getMessage());
        }
        String jobId = ctx.nextId();
        ctx.update(new Token(token.id(), token.kind(), TokenStatus.WAITING_TIMER, token.scopeTokenId(), token.nodeId(),
                jobId, token.locals()));
        ctx.effect(new Effect.ScheduleJob(jobId, TimerCatchEventBehavior.JOB_KIND, token.id(), node.id(), dueAt,
                Map.of()));
        ctx.history("TIMER_SCHEDULED", token, Map.of("jobId", jobId, "dueAt", dueAt.toString(), "boundaryOf",
                String.valueOf(node.attachedToRef())));
    }

    @Override
    public void trigger(BoundaryEvent node, Token token, Command trigger, ExecutionContext ctx) {
        if (!(trigger instanceof Command.JobDue due) || !TimerCatchEventBehavior.JOB_KIND.equals(due.jobKind())) {
            throw new ExecutionException("COMMAND_NOT_SUPPORTED", node.id(), "Expected a timer job");
        }
        ctx.history("TIMER_FIRED", token, Map.of("jobId", due.jobId()));
        ctx.fireBoundary(token, node);
    }
}
