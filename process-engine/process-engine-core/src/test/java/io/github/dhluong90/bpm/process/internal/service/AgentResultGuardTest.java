package io.github.dhluong90.bpm.process.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * The agent result check on its own, with a decision port that answers what each case needs (#363). The example is a
 * document assessment agent of a loan application: its answer must be {@code { riskScore: integer 0..100,
 * recommendation: APPROVE | REFER | DECLINE }}, and the bank's {@code agent-output-guardrails} rule set refuses a risk
 * score of 70 or more.
 */
class AgentResultGuardTest {

    private static final Instant T0 = Instant.parse("2027-03-01T02:00:00Z");
    private static final Duration BUDGET = RunnerSettings.DEFAULT_GUARD_BUDGET;
    private static final Map<String, Object> SCHEMA = Map.of("type", "object",
            "required", List.of("riskScore", "recommendation"), "additionalProperties", false,
            "properties", Map.of("riskScore", Map.of("type", "integer", "minimum", 0, "maximum", 100),
                    "recommendation", Map.of("type", "string", "enum", List.of("APPROVE", "REFER", "DECLINE"))));

    private final AtomicReference<Instant> now = new AtomicReference<>(T0);
    private final Clock clock = new Clock() {
        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    };

    private static Map<String, Object> guard(String onViolation, String reviewer) {
        Map<String, Object> guard = new java.util.LinkedHashMap<>();
        guard.put("outputSchema", SCHEMA);
        guard.put("decision", "agent-output-guardrails");
        if (onViolation != null) {
            guard.put("onViolation", onViolation);
        }
        if (reviewer != null) {
            guard.put("reviewer", reviewer);
        }
        return Map.of("target", "documents.assess", "guard", guard);
    }

    private static ResultGuard.Request request(Map<String, Object> payload, Map<String, Object> output) {
        return new ResultGuard.Request("bank-a", "01JINSTANCE0000000000000001", "assess-documents", "ASYNC_AGENT",
                payload, output, new DecisionCaller.Engine(ProcessServices.ASYNC_ACTOR));
    }

    private AgentResultGuard withPort(DecisionPort port) {
        return new AgentResultGuard(new JsonSchemaSubsetValidator(), Optional.of(port), clock);
    }

    private static final Map<String, Object> HIGH_RISK = Map.of("riskScore", 85, "recommendation", "APPROVE");

    @Test
    void PRE_059_allow_false_refuses_and_routes_to_the_reviewer_with_reasons_and_rules() {
        AgentResultGuard guard = withPort((tenant, caller, ref, inputs, correlation) ->
                new DecisionOutcome.Matched("01JDECISION000000000000001", Map.of("allow", false,
                        "reasons", List.of("HIGH_RISK_SCORE")), List.of("R2")));

        ResultGuard.Verdict verdict = guard.check(request(guard("route-to-human", "role:COMPLIANCE_REVIEWER"),
                HIGH_RISK), BUDGET);

        assertThat(verdict).isEqualTo(new ResultGuard.Verdict.Refused("GUARDRAIL_VIOLATION", List.of("HIGH_RISK_SCORE"),
                List.of("R2"), "01JDECISION000000000000001", ResultGuard.Route.REVIEW, "role:COMPLIANCE_REVIEWER",
                true));
    }

    @Test
    void PRE_059_the_guardrail_is_evaluated_in_the_instance_tenant_as_the_engine_on_the_output() {
        AtomicReference<String> seen = new AtomicReference<>();
        AgentResultGuard guard = withPort((tenant, caller, ref, inputs, correlation) -> {
            seen.set(tenant + " " + caller + " " + ref + " " + inputs.get("riskScore") + " " + correlation);
            return new DecisionOutcome.Matched("01JDECISION000000000000002", Map.of("allow", true), List.of("R3"));
        });

        ResultGuard.Verdict verdict = guard.check(request(guard(null, "role:COMPLIANCE_REVIEWER"),
                Map.of("riskScore", 20, "recommendation", "APPROVE")), BUDGET);

        assertThat(verdict).isEqualTo(new ResultGuard.Verdict.Allow("01JDECISION000000000000002", List.of("R3")));
        assertThat(seen.get()).isEqualTo("bank-a Engine[actor=system:async-step] agent-output-guardrails 20 "
                + "01JINSTANCE0000000000000001");
    }

    @Test
    void PRE_058_an_output_breaking_its_schema_is_an_invalid_attempt_and_the_guardrail_is_not_evaluated() {
        AtomicReference<Boolean> evaluated = new AtomicReference<>(false);
        AgentResultGuard guard = withPort((tenant, caller, ref, inputs, correlation) -> {
            evaluated.set(true);
            return new DecisionOutcome.Matched("x", Map.of("allow", true));
        });

        ResultGuard.Verdict verdict = guard.check(request(guard(null, "role:COMPLIANCE_REVIEWER"),
                Map.of("riskScore", "high", "recommendation", "APPROVE", "note", "free text")), BUDGET);

        assertThat(verdict).isInstanceOfSatisfying(ResultGuard.Verdict.Invalid.class, invalid -> {
            assertThat(invalid.code()).isEqualTo("AGENT_SCHEMA_INVALID");
            assertThat(invalid.violations()).containsExactlyInAnyOrder("$.note: not allowed",
                    "$.riskScore: expected integer");
        });
        assertThat(evaluated.get()).isFalse();
    }

    @Test
    void PRE_060_a_guardrail_that_cannot_decide_fails_closed() {
        Map<String, ResultGuard.Verdict> verdicts = new java.util.LinkedHashMap<>();
        Map<String, DecisionPort> ports = new java.util.LinkedHashMap<>();
        ports.put("not boolean", (t, c, r, i, k) -> new DecisionOutcome.Matched("d1", Map.of("allow", "yes"), List.of("R9")));
        ports.put("no allow", (t, c, r, i, k) -> new DecisionOutcome.Matched("d2", Map.of(), List.of()));
        ports.put("no match", (t, c, r, i, k) -> new DecisionOutcome.NoMatch("d3"));
        ports.put("failed", (t, c, r, i, k) -> new DecisionOutcome.Failed("RULE_SET_NOT_FOUND", "no such rule set"));
        ports.put("throws", (t, c, r, i, k) -> {
            throw new IllegalStateException("rule engine down");
        });
        ports.forEach((name, port) -> verdicts.put(name, withPort(port).check(
                request(guard("route-to-human", "role:COMPLIANCE_REVIEWER"), HIGH_RISK), BUDGET)));

        assertThat(verdicts.values()).allSatisfy(v -> assertThat(v).isInstanceOfSatisfying(
                ResultGuard.Verdict.Refused.class, r -> {
                    assertThat(r.code()).isEqualTo("GUARDRAIL_EVALUATION_ERROR");
                    assertThat(r.route()).isEqualTo(ResultGuard.Route.REVIEW);
                }));
        assertThat(verdicts).extractingByKeys("not boolean", "no allow", "no match", "failed", "throws")
                .map(v -> ((ResultGuard.Verdict.Refused) v).reasons())
                .containsExactly(List.of("GUARDRAIL_CONTRACT_INVALID"), List.of("GUARDRAIL_CONTRACT_INVALID"),
                        List.of("DECISION_NO_MATCH"), List.of("RULE_SET_NOT_FOUND"), List.of("IllegalStateException"));
    }

    @Test
    void PRE_060_without_a_rule_engine_the_result_is_refused() {
        AgentResultGuard guard = new AgentResultGuard(new JsonSchemaSubsetValidator(), Optional.empty(), clock);

        ResultGuard.Verdict verdict = guard.check(request(guard("fail", "role:COMPLIANCE_REVIEWER"), HIGH_RISK), BUDGET);

        assertThat(verdict).isEqualTo(new ResultGuard.Verdict.Refused("GUARDRAIL_EVALUATION_ERROR",
                List.of("RULE_ENGINE_MISSING"), List.of(), null, ResultGuard.Route.FAIL, null, true));
    }

    @Test
    void PRE_060_a_check_slower_than_the_budget_refuses_even_an_allowed_result() {
        AgentResultGuard guard = withPort((tenant, caller, ref, inputs, correlation) -> {
            now.set(now.get().plusMillis(501));
            return new DecisionOutcome.Matched("01JDECISION000000000000003", Map.of("allow", true), List.of("R3"));
        });

        ResultGuard.Verdict verdict = guard.check(request(guard(null, "role:COMPLIANCE_REVIEWER"),
                Map.of("riskScore", 20, "recommendation", "APPROVE")), BUDGET);

        assertThat(verdict).isEqualTo(new ResultGuard.Verdict.Refused("GUARDRAIL_EVALUATION_ERROR",
                List.of("GUARD_BUDGET_EXCEEDED"), List.of(), "01JDECISION000000000000003", ResultGuard.Route.REVIEW,
                "role:COMPLIANCE_REVIEWER", true));
    }

    @Test
    void PRE_060_a_missing_or_malformed_guard_spec_fails_the_job() {
        AgentResultGuard guard = withPort((t, c, r, i, k) -> new DecisionOutcome.Matched("d", Map.of("allow", true)));
        Map<String, Object> badReviewer = new java.util.LinkedHashMap<>(guard(null, null));
        badReviewer.put("guard", Map.of("outputSchema", SCHEMA, "decision", "agent-output-guardrails",
                "reviewer", "COMPLIANCE_REVIEWER"));

        ResultGuard.Verdict none = guard.check(request(Map.of("target", "documents.assess"), HIGH_RISK), BUDGET);
        ResultGuard.Verdict malformed = guard.check(request(badReviewer, HIGH_RISK), BUDGET);
        ResultGuard.Verdict noReviewer = guard.check(request(guard("route-to-human", null), HIGH_RISK), BUDGET);

        assertThat(none).isEqualTo(new ResultGuard.Verdict.Refused("GUARD_SPEC_INVALID", List.of("no guard spec"),
                List.of(), null, ResultGuard.Route.FAIL, null, true));
        assertThat(malformed).isInstanceOfSatisfying(ResultGuard.Verdict.Refused.class, r -> {
            assertThat(r.code()).isEqualTo("GUARD_SPEC_INVALID");
            assertThat(r.route()).isEqualTo(ResultGuard.Route.FAIL);
        });
        // A complete spec without a reviewer still checks the result; a refusal then fails the job.
        assertThat(noReviewer).isEqualTo(new ResultGuard.Verdict.Allow("d", List.of()));
    }
}
