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
import io.github.dhluong90.bpm.process.internal.model.bpmn.Process;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Signal and conditional events in the kernel (#492), in memory: what a throw asks the shell to do, how a waiter is
 * delivered, and when a condition fires. The tenant isolation of the broadcast and its idempotency are the database
 * integration test's.
 */
class SignalAndConditionalEventsTest {

    private final TestClock clock = TestClock.at("2027-03-01T02:00:00Z");

    private ScenarioRunner runner(Process process) {
        CompiledProcess compiled = new ProcessCompiler(BehaviorCatalog.registry(), 500).compile("D1", process);
        return ScenarioRunner.of(new DefaultExecutionKernel(compiled, BehaviorCatalog.registry(),
                new V1ExpressionEvaluator(100)), "01JD0000000000000000000000", "bank-a", "D1", clock);
    }

    private ScenarioRunner rateWatcher() {
        return runner(BpmnModelBuilder.process("rate-watcher")
                .startEvent("start")
                .signalCatch("wait-rate-card", "rate-card-updated")
                .userTask("reprice", "role:PRICING_OFFICER", "repriced")
                .endEvent("end")
                .chain("start", "wait-rate-card", "reprice", "end")
                .build());
    }

    @Test
    void PRE_271_a_signal_catch_waits_on_one_subscription_and_a_delivery_moves_it_on() {
        ScenarioRunner run = rateWatcher().start(Map.of());

        run.assertTokenAt("wait-rate-card", TokenStatus.WAITING_EVENT);
        Effect.CreateSubscription subscription = run.effects().stream().filter(Effect.CreateSubscription.class::isInstance)
                .map(Effect.CreateSubscription.class::cast).findFirst().orElseThrow();
        assertThat(subscription.kind()).isEqualTo("SIGNAL");
        assertThat(subscription.name()).isEqualTo("rate-card-updated");

        String tokenId = subscription.tokenId();
        run.apply(new Command.DeliverSignal(tokenId, subscription.subscriptionId(), "rate-card-updated"));

        run.assertTokenAt("reprice", TokenStatus.WAITING_USER);
        assertThat(run.effects()).contains(new Effect.DeleteSubscription(subscription.subscriptionId()));
    }

    @Test
    void PRE_271_a_delivery_for_another_subscription_or_a_second_delivery_is_refused() {
        ScenarioRunner run = rateWatcher().start(Map.of());
        Effect.CreateSubscription subscription = run.effects().stream().filter(Effect.CreateSubscription.class::isInstance)
                .map(Effect.CreateSubscription.class::cast).findFirst().orElseThrow();

        assertThatThrownBy(() -> run.apply(new Command.DeliverSignal(subscription.tokenId(),
                "01JD9999999999999999999999", "rate-card-updated"))).isInstanceOf(ExecutionException.class)
                .satisfies(e -> assertThat(((ExecutionException) e).code()).isEqualTo("STALE_ATTEMPT"));
        run.apply(new Command.DeliverSignal(subscription.tokenId(), subscription.subscriptionId(), "rate-card-updated"));
        assertThatThrownBy(() -> run.apply(new Command.DeliverSignal(subscription.tokenId(),
                subscription.subscriptionId(), "rate-card-updated"))).isInstanceOf(ExecutionException.class)
                .satisfies(e -> assertThat(((ExecutionException) e).code()).isEqualTo("INVALID_STATE"));
    }

    @Test
    void PRE_272_a_signal_throw_broadcasts_once_and_the_thrower_goes_on() {
        ScenarioRunner run = runner(BpmnModelBuilder.process("rate-publisher")
                .startEvent("start")
                .signalThrow("announce", "rate-card-updated")
                .userTask("archive", "role:PRICING_OFFICER", "archived")
                .endEvent("end")
                .chain("start", "announce", "archive", "end")
                .build()).start(Map.of());

        List<Effect.BroadcastSignal> broadcasts = run.effects().stream().filter(Effect.BroadcastSignal.class::isInstance)
                .map(Effect.BroadcastSignal.class::cast).toList();
        assertThat(broadcasts).hasSize(1);
        assertThat(broadcasts.get(0).name()).isEqualTo("rate-card-updated");
        assertThat(broadcasts.get(0).nodeId()).isEqualTo("announce");
        run.assertTokenAt("archive", TokenStatus.WAITING_USER);
    }

    @Test
    void PRE_272_a_signal_end_broadcasts_and_ends_the_instance() {
        ScenarioRunner run = runner(BpmnModelBuilder.process("rate-closer")
                .startEvent("start")
                .signalEnd("end", "rate-card-closed")
                .chain("start", "end")
                .build()).start(Map.of());

        assertThat(run.effects()).filteredOn(Effect.BroadcastSignal.class::isInstance).hasSize(1);
        run.assertCompleted();
    }

    @Test
    void PRE_274_a_conditional_catch_whose_condition_is_true_on_arrival_passes_at_once() {
        ScenarioRunner run = runner(BpmnModelBuilder.process("limit-watcher")
                .startEvent("start")
                .conditionalCatch("wait-limit", "limit > 0")
                .userTask("review", "role:CREDIT_OFFICER", "reviewed")
                .endEvent("end")
                .chain("start", "wait-limit", "review", "end")
                .build()).start(Map.of("limit", 5000));

        run.assertTokenAt("review", TokenStatus.WAITING_USER);
        assertThat(run.history()).filteredOn(h -> h.type().equals("CONDITION_MET")).hasSize(1);
    }

    @Test
    void PRE_274_a_conditional_catch_waits_until_a_completed_task_makes_its_condition_true() {
        ScenarioRunner run = runner(BpmnModelBuilder.process("limit-approval")
                .startEvent("start")
                .userTask("set-limit", "role:CREDIT_OFFICER", "decision")
                .conditionalCatch("wait-approved", "decision.approved == true")
                .userTask("issue-card", "role:CARD_OFFICER", "issued")
                .endEvent("end")
                .chain("start", "set-limit", "wait-approved", "issue-card", "end")
                .build()).start(Map.of());
        // A parallel path would hold the wait; here the condition is asked again by the completion of set-limit.
        run.completeTask("set-limit", Map.of("approved", true), "lan");

        run.assertTokenAt("issue-card", TokenStatus.WAITING_USER);
    }

    @Test
    void PRE_274_a_conditional_catch_stays_while_the_condition_is_false_and_never_fails_the_step_that_changes_variables() {
        ScenarioRunner run = runner(BpmnModelBuilder.process("limit-approval")
                .startEvent("start")
                .userTask("set-limit", "role:CREDIT_OFFICER", "decision")
                .conditionalCatch("wait-approved", "decision.approved == true")
                .endEvent("end")
                .chain("start", "set-limit", "wait-approved", "end")
                .build()).start(Map.of());

        run.completeTask("set-limit", Map.of("approved", false), "lan");

        run.assertTokenAt("wait-approved", TokenStatus.WAITING_EVENT);
    }

    @Test
    void PRE_274_a_conditional_start_whose_condition_is_false_is_refused() {
        ScenarioRunner run = runner(BpmnModelBuilder.process("high-value-review")
                .conditionalStart("start", "amount >= 1000000")
                .userTask("review", "role:CREDIT_OFFICER", "reviewed")
                .endEvent("end")
                .chain("start", "review", "end")
                .build());

        assertThatThrownBy(() -> run.start(Map.of("amount", 500000))).isInstanceOf(ExecutionException.class)
                .satisfies(e -> assertThat(((ExecutionException) e).code()).isEqualTo("START_CONDITION_FALSE"));
    }
}
