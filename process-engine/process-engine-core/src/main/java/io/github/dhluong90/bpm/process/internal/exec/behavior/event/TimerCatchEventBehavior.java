package io.github.dhluong90.bpm.process.internal.exec.behavior.event;

import io.github.dhluong90.bpm.process.internal.exec.Command;
import io.github.dhluong90.bpm.process.internal.exec.Effect;
import io.github.dhluong90.bpm.process.internal.exec.EventDefinitionKey;
import io.github.dhluong90.bpm.process.internal.exec.ExecutionContext;
import io.github.dhluong90.bpm.process.internal.exec.ExecutionException;
import io.github.dhluong90.bpm.process.internal.exec.FlowNodeBehavior;
import io.github.dhluong90.bpm.process.internal.exec.Token;
import io.github.dhluong90.bpm.process.internal.exec.TokenStatus;
import io.github.dhluong90.bpm.process.internal.model.bpmn.IntermediateCatchEvent;
import io.github.dhluong90.bpm.process.internal.model.bpmn.TimerEventDefinition;
import java.time.Instant;
import java.util.Map;

/**
 * Intermediate timer catch event: the token waits in {@code WAITING_TIMER} and the kernel asks the shell for one
 * {@code TIMER} job. The job id is the token's activity instance id, so a {@link Command.JobDue} of any other job
 * (an earlier visit, a cancelled timer) is refused as stale. Firing takes the outgoing flow.
 */
final class TimerCatchEventBehavior implements FlowNodeBehavior<IntermediateCatchEvent> {

    static final String JOB_KIND = "TIMER";

    @Override
    public Class<IntermediateCatchEvent> nodeType() {
        return IntermediateCatchEvent.class;
    }

    @Override
    public EventDefinitionKey eventDefinition() {
        return EventDefinitionKey.of(TimerEventDefinition.class);
    }

    @Override
    public void enter(IntermediateCatchEvent node, Token token, ExecutionContext ctx) {
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
        ctx.effect(new Effect.ScheduleJob(jobId, JOB_KIND, token.id(), node.id(), dueAt, Map.of()));
        ctx.history("TIMER_SCHEDULED", token, Map.of("jobId", jobId, "dueAt", dueAt.toString()));
    }

    @Override
    public void trigger(IntermediateCatchEvent node, Token token, Command trigger, ExecutionContext ctx) {
        if (!(trigger instanceof Command.JobDue due) || !JOB_KIND.equals(due.jobKind())) {
            throw new ExecutionException("COMMAND_NOT_SUPPORTED", node.id(), "Expected a timer job");
        }
        Token active = token.withStatus(TokenStatus.ACTIVE);
        ctx.update(active);
        ctx.history("TIMER_FIRED", token, Map.of("jobId", due.jobId()));
        ctx.history("NODE_COMPLETED", token, Map.of());
        ctx.takeOutgoing(active, node);
    }
}
