package io.github.dhluong90.bpm.process.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.expr.api.Money;
import io.github.dhluong90.bpm.process.api.ProcessException;
import io.github.dhluong90.bpm.process.api.TenantNotAllowedException;
import io.github.dhluong90.bpm.process.internal.service.DecisionCaller;
import io.github.dhluong90.bpm.process.internal.service.DecisionOutcome;
import io.github.dhluong90.bpm.rules.api.DecisionRequest;
import io.github.dhluong90.bpm.rules.api.DecisionResult;
import io.github.dhluong90.bpm.rules.api.RuleSetNotFoundException;
import io.github.dhluong90.bpm.rules.embedded.TenantScopedRuleEngine;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * The process starter's adapter from the decision port to the Rule Engine (ADR-0027). The example is the card fee
 * review of bank-a: tuan completes the review, and the {@code price} step evaluates {@code card-fee}.
 */
@ExtendWith(OutputCaptureExtension.class)
class RuleEngineDecisionPortTest {

    private static final Instant STEP_CLOCK = Instant.parse("2027-03-01T02:00:00Z");
    private static final String KEY = "pe:01JD0000000000000000000000:price:01JD0000000000000000000001";

    /** The entry point, recording what the adapter hands it. */
    private static class RecordingEntryPoint implements TenantScopedRuleEngine {
        final List<Object[]> calls = new ArrayList<>();

        @Override
        public String engineId() {
            return DecisionCaller.ENGINE_IDENTITY;
        }

        @Override
        public DecisionResult evaluate(String tenantId, DecisionRequest request) {
            throw new AssertionError("a process step always passes onBehalfOf and the engine clock");
        }

        @Override
        public DecisionResult evaluate(String tenantId, String onBehalfOf, Instant evaluationTime,
                DecisionRequest request) {
            calls.add(new Object[] {tenantId, onBehalfOf, evaluationTime, request});
            if (!tenantId.equals("bank-a")) {
                throw new RuleSetNotFoundException(request.ruleSet());
            }
            return new DecisionResult("01JDDECISION00000000000000", request.ruleSet(), 1, evaluationTime,
                    new DecisionResult.Matched(List.of("R2"), Map.of("fee", Money.ofMinor("VND", 20_000))));
        }
    }

    @Test
    void PRE_047_decision_port_refuses_a_request_tenant_other_than_the_instance_tenant(CapturedOutput output) {
        // Given an instance of bank-a, and a request of bank-b that reached the step past the task tenant check
        RecordingEntryPoint entryPoint = new RecordingEntryPoint();
        RuleEngineDecisionPort port = new RuleEngineDecisionPort(Optional.of(entryPoint));

        // When the adapter is called for the step; Then it refuses with 403 TENANT_NOT_ALLOWED
        assertThatThrownBy(() -> port.evaluate("bank-a", new DecisionCaller.Request("bank-b", "tuan"), "card-fee",
                Map.of("tier", "GOLD"), STEP_CLOCK, KEY, "01JD0000000000000000000000"))
                .isInstanceOf(TenantNotAllowedException.class)
                .satisfies(e -> assertThat(((ProcessException) e).code()).isEqualTo("TENANT_NOT_ALLOWED"));

        // before any rule set is read, so nothing can be written in either tenant; and one security log entry
        assertThat(entryPoint.calls).isEmpty();
        assertThat(output.getAll().split("security.decision.tenant_refused", -1)).hasSize(2);
        assertThat(output.getAll()).contains("requestTenant=bank-b instanceTenant=bank-a decision=card-fee user=tuan")
                .doesNotContain("GOLD");
    }

    @Test
    void PRE_046_the_adapter_evaluates_as_the_engine_on_behalf_of_the_user_with_the_step_key_and_clock() {
        RecordingEntryPoint entryPoint = new RecordingEntryPoint();
        RuleEngineDecisionPort port = new RuleEngineDecisionPort(Optional.of(entryPoint));

        // (a) inside tuan's request; (b) after a timer, with no one signed in
        DecisionOutcome inRequest = port.evaluate("bank-a", new DecisionCaller.Request("bank-a", "tuan"), "card-fee",
                Map.of("tier", "CLASSIC"), STEP_CLOCK, KEY, "01JD0000000000000000000000");
        DecisionOutcome afterTimer = port.evaluate("bank-a", new DecisionCaller.Engine("system:timer"), "card-fee",
                Map.of("tier", "CLASSIC"), STEP_CLOCK, KEY, "01JD0000000000000000000000");

        assertThat(inRequest).isEqualTo(afterTimer).isInstanceOfSatisfying(DecisionOutcome.Matched.class, m -> {
            assertThat(m.decisionId()).isEqualTo("01JDDECISION00000000000000");
            assertThat(m.outputs()).containsEntry("fee", Money.ofMinor("VND", 20_000));
        });
        assertThat(entryPoint.calls).hasSize(2);
        assertThat(entryPoint.calls.get(0)[1]).isEqualTo("tuan");
        assertThat(entryPoint.calls.get(1)[1]).isNull();
        for (Object[] call : entryPoint.calls) {
            DecisionRequest request = (DecisionRequest) call[3];
            assertThat(call[0]).isEqualTo("bank-a");
            assertThat(call[2]).isEqualTo(STEP_CLOCK);
            assertThat(request.idempotencyKey()).isEqualTo(KEY);
            assertThat(request.correlationId()).isEqualTo("01JD0000000000000000000000");
            assertThat(request.clock()).as("the engine clock goes as the evaluation time, never as a chosen clock")
                    .isNull();
            assertThat(request.version()).isNull();
        }
    }

    @Test
    void a_refusal_of_the_rule_engine_fails_the_step_closed() {
        RuleEngineDecisionPort port = new RuleEngineDecisionPort(Optional.of(new RecordingEntryPoint()));

        assertThat(port.evaluate("bank-c", new DecisionCaller.Engine("system:timer"), "card-fee", Map.of(),
                STEP_CLOCK, KEY, null))
                .isInstanceOfSatisfying(DecisionOutcome.Failed.class,
                        f -> assertThat(f.code()).isEqualTo("RULE_SET_NOT_FOUND"));
    }

    @Test
    void without_the_entry_point_a_decision_step_fails_closed_with_TENANT_NOT_ALLOWED() {
        RuleEngineDecisionPort port = new RuleEngineDecisionPort(Optional.empty());

        assertThat(port.evaluate("bank-a", new DecisionCaller.Request("bank-a", "tuan"), "card-fee", Map.of(),
                STEP_CLOCK, KEY, null))
                .isInstanceOfSatisfying(DecisionOutcome.Failed.class,
                        f -> assertThat(f.code()).isEqualTo("TENANT_NOT_ALLOWED"));
    }

    @Test
    void an_entry_point_bound_to_another_identity_is_refused() {
        TenantScopedRuleEngine other = new RecordingEntryPoint() {
            @Override
            public String engineId() {
                return "system:timer";
            }
        };

        assertThatThrownBy(() -> new RuleEngineDecisionPort(Optional.of(other)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void a_host_without_a_rule_engine_bean_gets_no_decision_port() {
        assertThat(RuleEngineDecisions.from(new DefaultListableBeanFactory())).isEmpty();
    }
}
