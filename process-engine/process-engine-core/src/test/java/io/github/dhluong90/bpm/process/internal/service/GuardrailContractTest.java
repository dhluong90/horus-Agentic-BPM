package io.github.dhluong90.bpm.process.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The guardrail contract (PRE-016) as a pure check, on the loan document assessor's output schema
 * {@code { riskScore: integer 0..100, recommendation: APPROVE | REFER | DECLINE, summary?: string (masked) }}.
 */
class GuardrailContractTest {

    private static final String DECISION = "agent-output-guardrails";

    private static Map<String, Object> schema(boolean additionalProperties) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("riskScore", Map.of("type", "integer", "minimum", 0, "maximum", 100));
        properties.put("recommendation", Map.of("type", "string", "enum", List.of("APPROVE", "REFER", "DECLINE")));
        properties.put("summary", Map.of("type", "string", "maxLength", 500, "mask", true));
        properties.put("documents", Map.of("type", "array", "items", Map.of("type", "string")));
        properties.put("confidence", Map.of("type", List.of("number", "null")));
        return Map.of("type", "object", "required", List.of("riskScore", "recommendation"),
                "additionalProperties", additionalProperties, "properties", properties);
    }

    private static DecisionSignature.Found signature(String hitPolicy, Map<String, String> inputs,
            Map<String, String> outputs) {
        return new DecisionSignature.Found(DECISION, 1, hitPolicy, inputs, outputs, false);
    }

    private static List<String> check(DecisionSignature signature) {
        return GuardrailContract.check(DECISION, signature, schema(false));
    }

    @Test
    void PRE_016_a_first_or_unique_rule_set_with_allow_and_string_reasons_meets_the_contract() {
        Map<String, String> inputs = Map.of("riskScore", "integer", "recommendation", "string");

        assertThat(check(signature("FIRST", inputs, Map.of("allow", "boolean", "reasons", "string")))).isEmpty();
        assertThat(check(signature("UNIQUE", inputs, Map.of("allow", "boolean")))).isEmpty();
        // Other outputs are allowed and never read
        assertThat(check(signature("FIRST", inputs, Map.of("allow", "boolean", "note", "string")))).isEmpty();
        // reasons as list<string> is accepted by the contract
        assertThat(check(signature("FIRST", inputs, Map.of("allow", "boolean", "reasons", "list<string>"))))
                .isEmpty();
    }

    @Test
    void PRE_016_outputs_decision_string_without_allow_is_refused_naming_the_outputs() {
        assertThat(check(signature("FIRST", Map.of("riskScore", "integer"), Map.of("decision", "string"))))
                .containsExactly("guardrail rule set agent-output-guardrails v1 declares no output allow: boolean "
                        + "(outputs decision: string)");
    }

    @Test
    void PRE_016_hit_policy_collect_is_refused() {
        assertThat(check(signature("COLLECT", Map.of(), Map.of("allow", "list<boolean>"))))
                .containsExactly("guardrail rule set agent-output-guardrails v1 has hit policy collect; a guardrail "
                                + "needs first or unique",
                        "guardrail rule set agent-output-guardrails v1 declares allow as list<boolean>; it must be "
                                + "boolean");
    }

    @Test
    void PRE_016_a_missing_rule_set_or_version_is_refused() {
        assertThat(check(new DecisionSignature.Unavailable("RULE_SET_NOT_FOUND", "x")))
                .containsExactly("guardrail rule set agent-output-guardrails does not exist in this tenant");
        assertThat(check(new DecisionSignature.Unavailable("NO_EFFECTIVE_VERSION", "x")))
                .containsExactly("guardrail rule set agent-output-guardrails has no published version in effect");
        assertThat(check(new DecisionSignature.Unavailable("SIGNATURE_UNAVAILABLE", "x")))
                .containsExactly("guardrail rule set agent-output-guardrails cannot be checked (SIGNATURE_UNAVAILABLE)");
    }

    @Test
    void PRE_016_reasons_of_another_type_is_refused() {
        assertThat(check(signature("FIRST", Map.of(), Map.of("allow", "boolean", "reasons", "integer"))))
                .containsExactly("guardrail rule set agent-output-guardrails v1 declares reasons as integer; it must "
                        + "be string or list<string>");
    }

    @Test
    void PRE_016_an_input_the_answer_cannot_carry_is_refused() {
        Map<String, String> outputs = Map.of("allow", "boolean");

        assertThat(check(signature("FIRST", Map.of("monthlyIncome", "decimal"), outputs)))
                .containsExactly("guardrail rule set agent-output-guardrails v1 reads input monthlyIncome, which "
                        + "horus:outputSchema does not declare");
        assertThat(check(signature("FIRST", Map.of("riskScore", "string"), outputs)))
                .containsExactly("guardrail rule set agent-output-guardrails v1 reads input riskScore as string, "
                        + "which horus:outputSchema declares as integer");
        assertThat(check(signature("FIRST", Map.of("recommendation", "list<string>"), outputs))).hasSize(1);
        assertThat(check(signature("FIRST", Map.of("documents", "list<integer>"), outputs))).hasSize(1);
        assertThat(check(signature("FIRST", Map.of("confidence", "integer"), outputs))).hasSize(1);
    }

    @Test
    void PRE_016_compatible_input_types_pass() {
        Map<String, String> inputs = Map.of("riskScore", "decimal", "recommendation", "string", "summary",
                "string", "documents", "list<string>", "confidence", "decimal");

        assertThat(check(signature("FIRST", inputs, Map.of("allow", "boolean")))).isEmpty();
    }

    @Test
    void PRE_016_a_rule_engine_that_refuses_undeclared_inputs_needs_every_field_declared_and_a_closed_schema() {
        DecisionSignature.Found strict = new DecisionSignature.Found(DECISION, 2, "FIRST",
                Map.of("riskScore", "integer"), Map.of("allow", "boolean"), true);

        assertThat(GuardrailContract.check(DECISION, strict, schema(true)))
                .contains("guardrail rule set agent-output-guardrails v2 does not declare input recommendation, and "
                        + "this Rule Engine refuses undeclared inputs")
                .anyMatch(failure -> failure.contains("additionalProperties"));
        DecisionSignature.Found all = new DecisionSignature.Found(DECISION, 2, "FIRST", Map.of("riskScore", "integer",
                "recommendation", "string", "summary", "string", "documents", "list<string>", "confidence",
                "decimal"), Map.of("allow", "boolean"), true);
        assertThat(GuardrailContract.check(DECISION, all, schema(false))).isEmpty();
    }

    @Test
    void PRE_016_a_failure_never_echoes_schema_values() {
        List<String> failures = check(signature("COLLECT", Map.of("riskScore", "string", "income", "decimal"),
                Map.of("decision", "string")));

        assertThat(String.join(" ", failures)).doesNotContain("APPROVE").doesNotContain("500")
                .doesNotContain("maxLength");
    }
}
