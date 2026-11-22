package io.github.dhluong90.bpm.process.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dhluong90.bpm.process.api.FormValidationException;
import io.github.dhluong90.bpm.process.spi.FormRegistry;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Task forms (PRE-031, PRE-216, PRE-217) without a database: the loan review form a credit officer fills (decision,
 * reason required on REJECT, a masked national id number, an approved limit as money and a decision date), checked
 * the way the service does it at completion, and the link and schema rules the import applies.
 */
class TaskFormsTest {

    private final TaskForms forms = new TaskForms(FormRegistry.NONE, new JsonSchemaSubsetValidator(),
            List.of("https://loans.example.com"));

    private static Map<String, Object> reviewForm() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("decision", Map.of("enum", List.of("APPROVE", "REJECT")));
        properties.put("reason", Map.of("type", "string", "maxLength", 500));
        properties.put("idNumber", Map.of("type", "string", "mask", true));
        properties.put("approvedLimit", Map.of("type", "object", "required", List.of("currency", "amount"),
                "properties", Map.of("currency", Map.of("type", "string"), "amount", Map.of("type", "integer"))));
        properties.put("decidedOn", Map.of("type", "string", "format", "date"));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("required", List.of("decision"));
        schema.put("properties", properties);
        schema.put("if", Map.of("required", List.of("decision"), "properties",
                Map.of("decision", Map.of("const", "REJECT"))));
        schema.put("then", Map.of("required", List.of("reason")));
        return schema;
    }

    @Test
    void PRE_031_a_rejection_needs_a_reason_and_an_approval_does_not() {
        assertThat(forms.validate(reviewForm(), Map.of("decision", "REJECT"))).containsExactly("$.reason: required");
        assertThat(forms.validate(reviewForm(), Map.of("decision", "REJECT", "reason", "income not verified")))
                .isEmpty();
        assertThat(forms.validate(reviewForm(), Map.of("decision", "APPROVE"))).isEmpty();
    }

    @Test
    void PRE_031_a_missing_decision_and_a_value_outside_the_enum_are_named_by_field_never_by_value() {
        assertThat(forms.validate(reviewForm(), Map.of())).containsExactly("$.decision: required");
        List<String> violations = forms.validate(reviewForm(), Map.of("decision", "MAYBE-4412"));
        assertThat(violations).containsExactly("$.decision: not one of the allowed values");
        assertThat(String.join(" ", violations)).doesNotContain("MAYBE-4412");
    }

    @Test
    void PRE_031_violations_become_json_pointers() {
        FormValidationException e = new FormValidationException("x", List.of("$.reason: required",
                "$.approvedLimit.amount: expected integer", "$.items[0].qty: below the minimum", "$: expected object"));

        assertThat(e.pointers()).containsExactly("/reason", "/approvedLimit/amount", "/items/0/qty", "");
    }

    @Test
    void PRE_031_a_date_and_the_money_object_are_checked() {
        Map<String, Object> ok = Map.of("decision", "APPROVE", "decidedOn", "2026-11-20",
                "approvedLimit", Map.of("currency", "VND", "amount", 250_000_000));
        assertThat(forms.validate(reviewForm(), ok)).isEmpty();

        List<String> violations = forms.validate(reviewForm(), Map.of("decision", "APPROVE", "decidedOn", "20/11/2026",
                "approvedLimit", Map.of("currency", "VND", "amount", "250000000")));

        assertThat(violations).containsExactlyInAnyOrder("$.decidedOn: not a valid date",
                "$.approvedLimit.amount: expected integer");
    }

    @Test
    void PRE_217_a_masked_field_is_a_placeholder_in_the_prefilled_data_and_never_its_value() {
        Map<String, Object> variables = Map.of("idNumber", "079012345678", "decision", "APPROVE", "unrelated", "x");

        Map<String, Object> data = TaskForms.prefill(reviewForm(), variables);

        assertThat(data).containsEntry("idNumber", "***").containsEntry("decision", "APPROVE")
                .doesNotContainKey("unrelated");
        assertThat(data.toString()).doesNotContain("079012345678");
    }

    @Test
    void PRE_217_a_link_has_its_placeholders_replaced() {
        assertThat(TaskForms.link("/loans/review/{taskId}?case={instanceId}", "T6", "I1"))
                .isEqualTo("/loans/review/T6?case=I1");
    }

    @Test
    void PRE_216_a_link_is_relative_or_on_a_listed_origin_and_nothing_else() {
        assertThat(forms.linkAllowed("/loans/review/{taskId}")).isTrue();
        assertThat(forms.linkAllowed("https://loans.example.com/review/{taskId}")).isTrue();
        assertThat(forms.linkAllowed("https://LOANS.example.com/x")).isTrue();
        assertThat(forms.linkAllowed("https://loans.example.com:8443/x")).isFalse();
        assertThat(forms.linkAllowed("https://evil.example/t/{taskId}")).isFalse();
        assertThat(forms.linkAllowed("//evil.example/t")).isFalse();
        assertThat(forms.linkAllowed("javascript:alert(1)")).isFalse();
        assertThat(forms.linkAllowed("https://loans.example.com@evil.example/x")).isFalse();
        assertThat(forms.linkAllowed("/loans/{other}")).isFalse();
        assertThat(forms.linkAllowed("/loans/a b")).isFalse();
        assertThat(forms.linkAllowed("/loans\\evil")).isFalse();
        assertThat(new TaskForms(FormRegistry.NONE, null, null).linkAllowed("https://loans.example.com/x")).isFalse();
    }

    @Test
    void PRE_216_a_schema_outside_the_task_form_subset_is_named_by_path_and_rule() {
        Map<String, Object> pattern = new LinkedHashMap<>(reviewForm());
        pattern.put("properties", Map.of("decision", Map.of("type", "string", "pattern", "^A")));

        assertThat(forms.checkSchema(pattern)).anyMatch(p -> p.contains("unsupported schema keyword pattern"));
        assertThat(forms.checkSchema(Map.of("type", "object", "properties", Map.of("tags",
                Map.of("type", "array")))))
                .anyMatch(p -> p.contains("$.tags: type must be string, integer, number, boolean, object (money)"));
        assertThat(forms.checkSchema(Map.of("type", "object", "properties", Map.of("when",
                Map.of("type", "string", "format", "email")))))
                .anyMatch(p -> p.contains("format is date or date-time"));
        assertThat(forms.checkSchema(Map.of("type", "string"))).contains("$: type must be object");
        assertThat(forms.checkSchema(reviewForm())).isEmpty();
    }
}
