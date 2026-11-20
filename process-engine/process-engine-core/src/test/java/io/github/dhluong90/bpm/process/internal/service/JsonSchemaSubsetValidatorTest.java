package io.github.dhluong90.bpm.process.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The default output schema check: the supported subset, and fail-closed handling of everything else (#363). */
class JsonSchemaSubsetValidatorTest {

    private final JsonSchemaSubsetValidator validator = new JsonSchemaSubsetValidator();

    private static final Map<String, Object> ASSESSMENT = Map.of("type", "object",
            "required", List.of("riskScore", "recommendation"), "additionalProperties", false,
            "properties", Map.of(
                    "riskScore", Map.of("type", "integer", "minimum", 0, "maximum", 100),
                    "recommendation", Map.of("type", "string", "enum", List.of("APPROVE", "REFER", "DECLINE")),
                    "documents", Map.of("type", "array", "maxItems", 2, "items", Map.of("type", "string",
                            "maxLength", 8))));

    @Test
    void PRE_058_a_valid_assessment_passes() {
        assertThat(validator.validate(ASSESSMENT, Map.of("riskScore", 85, "recommendation", "APPROVE"))).isEmpty();
        assertThat(validator.validate(ASSESSMENT, Map.of("riskScore", new BigDecimal("40.0"),
                "recommendation", "REFER", "documents", List.of("ID-CARD", "PAYSLIP")))).isEmpty();
    }

    @Test
    void PRE_058_violations_name_the_path_and_the_rule_never_the_value() {
        List<String> violations = validator.validate(ASSESSMENT, Map.of("riskScore", 120,
                "recommendation", "APPROVE-NOW", "documents", List.of("PASSPORT-079088001234", "A", "B"),
                "nationalId", "079088001234"));

        assertThat(violations).containsExactlyInAnyOrder("$.riskScore: above the maximum",
                "$.recommendation: not one of the allowed values", "$.documents: more items than the maximum",
                "$.documents[0]: longer than the maximum length", "$.nationalId: not allowed");
        assertThat(String.join(" ", violations)).doesNotContain("079088001234").doesNotContain("APPROVE-NOW");
    }

    @Test
    void PRE_058_missing_fields_and_wrong_types_are_violations() {
        assertThat(validator.validate(ASSESSMENT, Map.of("riskScore", 12.5))).containsExactlyInAnyOrder(
                "$.recommendation: required", "$.riskScore: expected integer");
    }

    @Test
    void PRE_060_an_unsupported_keyword_fails_closed() {
        Map<String, Object> schema = Map.of("type", "object", "properties", Map.of("recommendation",
                Map.of("type", "string", "pattern", "^(A|B)+$")));

        assertThat(validator.validate(schema, Map.of("recommendation", "A")))
                .containsExactly("$.recommendation: unsupported schema keyword pattern");
        assertThat(validator.validate(Map.of("$ref", "#/defs/x"), Map.of()))
                .containsExactly("$: unsupported schema keyword $ref");
        assertThat(validator.validate(null, Map.of())).containsExactly("$: no output schema");
    }
    @Test
    void PRE_061_a_schema_in_the_subset_passes_the_import_check() {
        assertThat(validator.checkSchema(ASSESSMENT)).isEmpty();
        assertThat(validator.checkSchema(Map.of("type", "object", "properties", Map.of("summary",
                Map.of("type", "string", "mask", true, "description", "free text"))))).isEmpty();
    }

    @Test
    void PRE_061_a_schema_outside_the_subset_is_refused_at_import_naming_the_path() {
        Map<String, Object> schema = Map.of("type", "object", "properties", Map.of(
                "nationalId", Map.of("type", "string", "pattern", "^[0-9]{12}$"),
                "address", Map.of("$ref", "#/$defs/address"),
                "riskScore", Map.of("type", "decimal", "minimum", "zero"),
                "documents", Map.of("type", "array", "items", Map.of("type", "string", "format", "uri"))),
                "additionalProperties", Map.of("type", "string"), "required", "riskScore");

        List<String> problems = validator.checkSchema(schema);

        assertThat(problems).contains("$.nationalId: unsupported schema keyword pattern",
                "$.address: unsupported schema keyword $ref", "$.riskScore: unsupported type decimal",
                "$.riskScore: minimum is not a number", "$.documents[]: unsupported schema keyword format",
                "$: additionalProperties must be true or false in this subset", "$: required is not a list");
        assertThat(String.join(" ", problems)).doesNotContain("^[0-9]{12}$");
    }

    @Test
    void PRE_061_a_host_validator_accepts_every_schema_at_import_unless_it_says_otherwise() {
        io.github.dhluong90.bpm.process.spi.OutputSchemaValidator host = (schema, output) -> List.of();

        assertThat(host.checkSchema(Map.of("pattern", "^x$"))).isEmpty();
    }
}
