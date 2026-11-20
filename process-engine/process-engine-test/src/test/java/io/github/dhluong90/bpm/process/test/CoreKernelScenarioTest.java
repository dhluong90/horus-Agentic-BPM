package io.github.dhluong90.bpm.process.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.expr.api.Money;
import io.github.dhluong90.bpm.process.api.ValidationFailedException;
import io.github.dhluong90.bpm.process.internal.exec.BehaviorCatalog;
import io.github.dhluong90.bpm.process.internal.exec.CompiledProcess;
import io.github.dhluong90.bpm.process.internal.exec.DefaultExecutionKernel;
import io.github.dhluong90.bpm.process.internal.exec.Effect;
import io.github.dhluong90.bpm.process.internal.exec.ExecutionException;
import io.github.dhluong90.bpm.process.internal.exec.InstanceStatus;
import io.github.dhluong90.bpm.process.internal.exec.ProcessCompiler;
import io.github.dhluong90.bpm.process.internal.exec.StepResult;
import io.github.dhluong90.bpm.process.internal.exec.TokenKind;
import io.github.dhluong90.bpm.process.internal.exec.TokenStatus;
import io.github.dhluong90.bpm.process.internal.expr.V1ExpressionEvaluator;
import io.github.dhluong90.bpm.process.internal.model.bpmn.Process;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The card-fee review process run through the kernel in memory, no database. */
class CoreKernelScenarioTest {

    private static final String INSTANCE = "01JD0000000000000000000000";

    static Process cardFeeReview() {
        return BpmnModelBuilder.process("card-fee-review")
                .startEvent("start")
                .serviceTask("load-profile", "cardProfiles.load", Map.of("customerId", "${instance.customerId}"), "profile")
                .businessRuleTask("price-card", "card-fee",
                        Map.of("tier", "${instance.profile.tier}", "txCount", "${instance.profile.txCount}"), "pricing")
                .exclusiveGateway("fee-check", "fee-check__review")
                .serviceTask("auto-approve", "cardApplications.decide", Map.of("outcome", "AUTO_APPROVED"), null)
                .userTask("officer-review", "role:CARD_OFFICER", "review")
                .endEvent("end")
                .chain("start", "load-profile", "price-card", "fee-check")
                .flow("fee-check__auto", "fee-check", "auto-approve", "pricing.fee == 0 VND")
                .flow("fee-check__review", "fee-check", "officer-review")
                .chain("auto-approve", "end")
                .chain("officer-review", "end")
                .build();
    }

    private final TestClock clock = TestClock.at("2027-01-04T09:00:00Z");

    private ScenarioRunner runner(Money fee) {
        CompiledProcess compiled = new ProcessCompiler(BehaviorCatalog.registry(), 500).compile("D1", cardFeeReview());
        DefaultExecutionKernel kernel = new DefaultExecutionKernel(compiled, BehaviorCatalog.registry(),
                new V1ExpressionEvaluator(100));
        return ScenarioRunner.of(kernel, INSTANCE, "bank-a", "D1", clock)
                .onInvoke("cardProfiles.load", in -> new StepResult.Completed(Map.of("tier", "GOLD", "txCount", 5L)))
                .onInvoke("card-fee", in -> new StepResult.Completed(Map.of("fee", fee)))
                .onInvoke("cardApplications.decide", in -> new StepResult.Completed(Map.of("outcome", in.get("outcome"))));
    }

    @Test
    void PRE_040_a_business_error_keeps_the_token_on_the_step_and_asks_for_an_incident() {
        // Given the card profile handler finds the customer's daily limit reached
        CompiledProcess compiled = new ProcessCompiler(BehaviorCatalog.registry(), 500).compile("D1", cardFeeReview());
        ScenarioRunner run = ScenarioRunner.of(new DefaultExecutionKernel(compiled, BehaviorCatalog.registry(),
                new V1ExpressionEvaluator(100)), INSTANCE, "bank-a", "D1", clock)
                .onInvoke("cardProfiles.load", in -> new StepResult.BusinessError("LIMIT_EXCEEDED",
                        "Daily limit reached"));

        // When an instance reaches the step
        run.start(Map.of("customerId", "C-040"));

        // Then the token waits on the step, the kernel asks for one incident with the business code only, and no
        // later step ran and no result was recorded
        run.assertTokenAt("load-profile", TokenStatus.WAITING_ASYNC);
        assertThat(run.state().status()).isEqualTo(InstanceStatus.RUNNING);
        assertThat(run.effects()).filteredOn(Effect.RaiseIncident.class::isInstance).singleElement()
                .isEqualTo(new Effect.RaiseIncident(run.state().tokens().stream()
                        .filter(t -> "load-profile".equals(t.nodeId())).findFirst().orElseThrow().id(),
                        "load-profile", "UNHANDLED_BUSINESS_ERROR", "LIMIT_EXCEEDED"));
        assertThat(run.effects()).filteredOn(Effect.RecordResult.class::isInstance).isEmpty();
        assertThat(run.effects()).filteredOn(Effect.InvokeService.class::isInstance).hasSize(1);
        assertThat(run.effects().toString()).doesNotContain("Daily limit reached");
    }

    @Test
    void gold_customer_with_zero_fee_is_approved_automatically() {
        ScenarioRunner run = runner(Money.ofMinor("VND", 0)).start(Map.of("customerId", "C-001"));

        run.assertCompleted();
        assertThat(run.state().variables()).containsEntry("outcome", "AUTO_APPROVED");
        assertThat(run.effects()).filteredOn(Effect.InvokeService.class::isInstance)
                .extracting(e -> ((Effect.InvokeService) e).target())
                .containsExactly("cardProfiles.load", "card-fee", "cardApplications.decide");
        assertThat(run.state().tokens()).filteredOn(t -> t.kind() == TokenKind.SCOPE).singleElement()
                .satisfies(root -> assertThat(root.status()).isEqualTo(TokenStatus.COMPLETED));
    }

    @Test
    void charged_fee_waits_for_an_officer_then_completes() {
        ScenarioRunner run = runner(Money.ofMinor("VND", 50_000)).start(Map.of("customerId", "C-002"));

        run.assertTokenAt("officer-review", TokenStatus.WAITING_USER);
        assertThat(run.state().status()).isEqualTo(InstanceStatus.RUNNING);
        assertThat(run.openTasks()).singleElement().satisfies(t -> assertThat(t.assignee()).isEqualTo("role:CARD_OFFICER"));

        run.completeTask("officer-review", Map.of("approved", true), "officer-an");

        run.assertCompleted();
        assertThat(run.state().variables()).containsEntry("review", Map.of("approved", true));
        assertThat(run.history()).extracting(h -> h.type()).contains("TASK_COMPLETED", "INSTANCE_ENDED");
    }

    @Test
    void same_state_and_command_give_the_same_transition() {
        List<String> first = runner(Money.ofMinor("VND", 10_000)).start(Map.of("customerId", "C-003"))
                .state().tokens().stream().map(t -> t.id() + t.nodeId() + t.status()).toList();
        List<String> second = runner(Money.ofMinor("VND", 10_000)).start(Map.of("customerId", "C-003"))
                .state().tokens().stream().map(t -> t.id() + t.nodeId() + t.status()).toList();
        assertThat(first).isEqualTo(second);
    }

    @Test
    void money_of_another_currency_is_a_condition_error_not_a_silent_false() {
        assertThatThrownBy(() -> runner(Money.ofMinor("USD", 0)).start(Map.of("customerId", "C-004")))
                .isInstanceOf(ExecutionException.class)
                .satisfies(e -> assertThat(((ExecutionException) e).code()).isEqualTo("CONDITION_ERROR"));
    }

    @Test
    void PRE_004_exclusive_gateway_routes_single_matching_branch() {
        Process loan = BpmnModelBuilder.process("loan-origination")
                .startEvent("start")
                .exclusiveGateway("amount-check", "amount-check__auto")
                .userTask("senior-approval", "role:SENIOR_CREDIT_OFFICER", "seniorReview")
                .userTask("auto-approve", "role:CREDIT_OFFICER", "review")
                .endEvent("end")
                .chain("start", "amount-check")
                .flow("amount-check__senior", "amount-check", "senior-approval", "amount >= 100000000 VND")
                .flow("amount-check__auto", "amount-check", "auto-approve")
                .chain("senior-approval", "end")
                .chain("auto-approve", "end")
                .build();
        CompiledProcess compiled = new ProcessCompiler(BehaviorCatalog.registry(), 500).compile("D3", loan);
        ScenarioRunner run = ScenarioRunner.of(new DefaultExecutionKernel(compiled, BehaviorCatalog.registry(),
                new V1ExpressionEvaluator(100)), INSTANCE, "bank-a", "D3", clock)
                .start(Map.of("amount", Money.ofMinor("VND", 150_000_000)));

        run.assertTokenAt("senior-approval", TokenStatus.WAITING_USER);
        assertThat(run.state().tokens()).filteredOn(t -> t.kind() == TokenKind.FLOW).singleElement();
        assertThat(run.openTasks()).extracting(Effect.CreateUserTask::nodeId).containsExactly("senior-approval");
    }

    @Test
    void a_four_eyes_task_refuses_the_identity_that_started_the_instance() {
        Process approval = BpmnModelBuilder.process("limit-change")
                .startEvent("start")
                .node(new io.github.dhluong90.bpm.process.internal.model.bpmn.UserTask("approve", null, null, null, null,
                        null, new io.github.dhluong90.bpm.process.internal.model.bpmn.ElementInfo(java.util.List.of(),
                        new io.github.dhluong90.bpm.process.internal.model.bpmn.ExtensionBag(java.util.List.of(
                                new io.github.dhluong90.bpm.process.internal.model.horus.TaskAssignment("role:SUPERVISOR", true),
                                new io.github.dhluong90.bpm.process.internal.model.horus.ResultVariable("approval")),
                                java.util.List.of()), Map.of())))
                .endEvent("end")
                .chain("start", "approve", "end")
                .build();
        CompiledProcess compiled = new ProcessCompiler(BehaviorCatalog.registry(), 500).compile("D4", approval);
        ScenarioRunner run = ScenarioRunner.of(new DefaultExecutionKernel(compiled, BehaviorCatalog.registry(),
                new V1ExpressionEvaluator(100)), INSTANCE, "bank-a", "D4", clock).start(Map.of());

        // ScenarioRunner starts as "starter"
        assertThatThrownBy(() -> run.completeTask("approve", Map.of("ok", true), "starter"))
                .isInstanceOf(ExecutionException.class)
                .satisfies(e -> assertThat(((ExecutionException) e).code()).isEqualTo("SELF_APPROVAL_FORBIDDEN"));
    }

    @Test
    void a_gateway_without_default_and_with_a_condition_missing_is_refused_at_compile_time() {
        Process broken = BpmnModelBuilder.process("broken")
                .startEvent("start").exclusiveGateway("g", null).endEvent("a").endEvent("b")
                .chain("start", "g").flow("g__a", "g", "a", "x == 1").flow("g__b", "g", "b")
                .build();
        assertThatThrownBy(() -> new ProcessCompiler(BehaviorCatalog.registry(), 500).compile("D2", broken))
                .isInstanceOf(ValidationFailedException.class)
                .hasMessageContaining("g__b");
    }
}
