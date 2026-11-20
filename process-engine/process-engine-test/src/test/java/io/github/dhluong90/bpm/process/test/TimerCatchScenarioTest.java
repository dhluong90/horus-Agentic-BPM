package io.github.dhluong90.bpm.process.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.process.internal.exec.BehaviorCatalog;
import io.github.dhluong90.bpm.process.internal.exec.Command;
import io.github.dhluong90.bpm.process.internal.exec.CompiledProcess;
import io.github.dhluong90.bpm.process.internal.exec.DefaultExecutionKernel;
import io.github.dhluong90.bpm.process.internal.exec.Effect;
import io.github.dhluong90.bpm.process.internal.exec.ExecutionException;
import io.github.dhluong90.bpm.process.internal.exec.ProcessCompiler;
import io.github.dhluong90.bpm.process.internal.exec.TokenStatus;
import io.github.dhluong90.bpm.process.internal.expr.V1ExpressionEvaluator;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The intermediate timer catch event in the kernel, in memory: one job scheduled, fired by its job only. */
class TimerCatchScenarioTest {

    private final TestClock clock = TestClock.at("2027-03-01T02:00:00Z");

    private ScenarioRunner reminder() {
        CompiledProcess compiled = new ProcessCompiler(BehaviorCatalog.registry(), 500).compile("D1",
                BpmnModelBuilder.process("card-activation-reminder")
                        .startEvent("start")
                        .timerCatch("wait-activation", "P3D")
                        .userTask("call-customer", "role:CONTACT_CENTRE", "call")
                        .endEvent("end")
                        .chain("start", "wait-activation", "call-customer", "end")
                        .build());
        return ScenarioRunner.of(new DefaultExecutionKernel(compiled, BehaviorCatalog.registry(),
                new V1ExpressionEvaluator(100)), "01JD0000000000000000000000", "bank-a", "D1", clock);
    }

    private static Effect.ScheduleJob scheduled(ScenarioRunner run) {
        return run.effects().stream().filter(Effect.ScheduleJob.class::isInstance).map(Effect.ScheduleJob.class::cast)
                .findFirst().orElseThrow();
    }

    @Test
    void PRE_006_timer_catch_waits_and_schedules_one_job_three_days_ahead() {
        ScenarioRunner run = reminder().start(Map.of("cardId", "CARD-4111"));

        run.assertTokenAt("wait-activation", TokenStatus.WAITING_TIMER);
        Effect.ScheduleJob job = scheduled(run);
        assertThat(job.jobKind()).isEqualTo("TIMER");
        assertThat(job.nodeId()).isEqualTo("wait-activation");
        assertThat(job.dueAt()).isEqualTo(Instant.parse("2027-03-01T02:00:00Z").plus(Duration.ofDays(3)));
        assertThat(run.effects().stream().filter(Effect.ScheduleJob.class::isInstance)).hasSize(1);
        assertThat(run.openTasks()).isEmpty();
    }

    @Test
    void PRE_006_job_due_moves_the_token_on() {
        ScenarioRunner run = reminder().start(Map.of());
        Effect.ScheduleJob job = scheduled(run);
        clock.advance(Duration.ofDays(3));

        run.apply(new Command.JobDue(job.jobId(), job.tokenId(), "TIMER"));

        run.assertTokenAt("call-customer", TokenStatus.WAITING_USER);
        assertThat(run.history()).filteredOn(h -> h.type().equals("TIMER_FIRED")).hasSize(1);
    }

    @Test
    void PRE_006_a_job_that_is_not_the_tokens_current_timer_is_refused() {
        ScenarioRunner run = reminder().start(Map.of());
        Effect.ScheduleJob job = scheduled(run);

        assertThatThrownBy(() -> run.apply(new Command.JobDue("01JD9999999999999999999999", job.tokenId(), "TIMER")))
                .isInstanceOf(ExecutionException.class)
                .satisfies(e -> assertThat(((ExecutionException) e).code()).isEqualTo("STALE_ATTEMPT"));
        run.apply(new Command.JobDue(job.jobId(), job.tokenId(), "TIMER"));
        assertThatThrownBy(() -> run.apply(new Command.JobDue(job.jobId(), job.tokenId(), "TIMER")))
                .as("a second firing of the same job").isInstanceOf(ExecutionException.class)
                .satisfies(e -> assertThat(((ExecutionException) e).code()).isEqualTo("INVALID_STATE"));
    }

    @Test
    void PRE_006_cancelling_the_instance_cancels_its_pending_timer_at_once() {
        ScenarioRunner run = reminder().start(Map.of());
        Effect.ScheduleJob job = scheduled(run);

        run.apply(new Command.Cancel("Customer closed the card", "officer-lan"));

        assertThat(run.effects()).contains(new Effect.CancelJob(job.jobId()));
        clock.advance(Duration.ofDays(3));
        assertThatThrownBy(() -> run.apply(new Command.JobDue(job.jobId(), job.tokenId(), "TIMER")))
                .as("the cancelled timer never fires").isInstanceOf(ExecutionException.class);
        assertThat(run.history()).filteredOn(h -> h.type().equals("TIMER_FIRED")).isEmpty();
    }
}
