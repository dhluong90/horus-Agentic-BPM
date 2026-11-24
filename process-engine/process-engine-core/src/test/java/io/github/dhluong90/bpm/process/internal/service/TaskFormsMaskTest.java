package io.github.dhluong90.bpm.process.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The masking of nested and listed fields by a schema (AUD-04, PRE-361). */
class TaskFormsMaskTest {

    private static final Map<String, Object> SCHEMA = Map.of("type", "object", "properties", Map.of(
            "riskScore", Map.of("type", "integer"),
            "summary", Map.of("type", "string", "mask", true),
            "applicant", Map.of("type", "object", "properties", Map.of(
                    "name", Map.of("type", "string"),
                    "nationalId", Map.of("type", "string", "mask", true))),
            "guarantors", Map.of("type", "array", "items", Map.of("type", "object", "properties", Map.of(
                    "name", Map.of("type", "string"),
                    "nationalId", Map.of("type", "string", "mask", true))))));

    @Test
    void PRE_361_control_a_masked_field_is_masked_inside_an_object_and_in_every_item_of_a_list() {
        Map<String, Object> output = Map.of("riskScore", 42, "summary", "salary slip shows 38,000,000 VND",
                "applicant", Map.of("name", "Tran Thi Mai", "nationalId", "079012345678"),
                "guarantors", List.of(Map.of("name", "Le Van An", "nationalId", "079099887766"),
                        Map.of("name", "Pham Thi Lan", "nationalId", "079011223344")),
                "note", "not in the schema");

        Map<String, Object> masked = TaskForms.maskVariables(SCHEMA, output);

        assertThat(masked).containsEntry("riskScore", 42).containsEntry("summary", "***")
                .containsEntry("note", "not in the schema");
        assertThat(masked.get("applicant")).isEqualTo(Map.of("name", "Tran Thi Mai", "nationalId", "***"));
        assertThat(masked.get("guarantors")).isEqualTo(List.of(Map.of("name", "Le Van An", "nationalId", "***"),
                Map.of("name", "Pham Thi Lan", "nationalId", "***")));
        // the input is not changed
        assertThat(output.get("applicant")).isEqualTo(Map.of("name", "Tran Thi Mai", "nationalId", "079012345678"));
    }

    @Test
    void PRE_361_control_a_null_masked_field_stays_null_and_a_masked_object_is_masked_whole() {
        Map<String, Object> schema = Map.of("properties", Map.of(
                "pan", Map.of("type", "string", "mask", true),
                "card", Map.of("type", "object", "mask", true)));
        Map<String, Object> variables = new java.util.HashMap<>();
        variables.put("pan", null);
        variables.put("card", Map.of("number", "4111111111111111"));

        Map<String, Object> masked = TaskForms.maskVariables(schema, variables);

        assertThat(masked.get("pan")).isNull();
        assertThat(masked.get("card")).isEqualTo("***");
    }
}
