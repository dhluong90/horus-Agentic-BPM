package io.github.dhluong90.bpm.rules.internal.dmn.ruleset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.rules.api.DecisionInput;
import io.github.dhluong90.bpm.rules.api.DecisionResult;
import io.github.dhluong90.bpm.rules.api.SourceFormat;
import io.github.dhluong90.bpm.rules.api.ValidationFailedException;
import io.github.dhluong90.bpm.rules.internal.eval.CompiledRuleSet;
import io.github.dhluong90.bpm.rules.internal.read.ParsedRuleSet;
import io.github.dhluong90.bpm.rules.internal.read.RuleSetReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * DMN item definitions as types (RLE-225), a rule set name and an output name made from the decision (RLE-226) and
 * COLLECT with an aggregation (RLE-227), with banking tables.
 */
class DmnItemDefinitionTest {

    private static final String HEAD = "<definitions xmlns=\"https://www.omg.org/spec/DMN/20230324/MODEL/\" id=\"d\""
            + " name=\"d\" namespace=\"urn:test\">";

    private static ParsedRuleSet read(String dmn) {
        return RuleSetReader.read(SourceFormat.DMN_XML, dmn.getBytes(StandardCharsets.UTF_8));
    }

    private static DecisionResult.Outcome evaluate(ParsedRuleSet parsed, Map<String, ?> inputs) {
        return CompiledRuleSet.compile(parsed.model()).evaluate(DecisionInput.of(inputs));
    }

    private static void assertCode(String dmn, String code, String message) {
        assertThatThrownBy(() -> read(dmn)).isInstanceOf(ValidationFailedException.class)
                .satisfies(e -> {
                    ValidationFailedException v = (ValidationFailedException) e;
                    assertThat(v.violations()).extracting(x -> x.code()).contains(code);
                    assertThat(v.getMessage()).contains(message);
                });
    }

    private static String loanTable(String definitions, String inputs, String outputs, String table) {
        return HEAD + definitions + "<decision id=\"loan-risk\" name=\"Loan risk\">" + table + inputs + outputs
                + "<rule id=\"r1\"><inputEntry><text>\"SALARIED\"</text></inputEntry><outputEntry><text>\"LOW\"</text>"
                + "</outputEntry></rule><rule id=\"r2\"><inputEntry><text>-</text></inputEntry><outputEntry>"
                + "<text>\"HIGH\"</text></outputEntry></rule></decisionTable></decision></definitions>";
    }

    private static final String EMPLOYMENT_INPUT = "<input><inputExpression typeRef=\"tEmployment\"><text>employment"
            + "</text></inputExpression></input>";

    private static final String RISK_OUTPUT = "<output name=\"risk\" typeRef=\"tRisk\"/>";

    private static final String TABLE_FIRST = "<decisionTable hitPolicy=\"FIRST\">";

    // ------------------------------------------------------------------ RLE-225 item definitions as types

    @Test
    void RLE_225_an_item_definition_of_a_string_with_allowed_values_types_the_column_and_closes_its_set() {
        // Given employment types as an item definition with allowed values, and a risk class as another
        String definitions = "<itemDefinition name=\"tEmployment\"><typeRef>string</typeRef><allowedValues>"
                + "<text>\"SALARIED\", \"SELF_EMPLOYED\"</text></allowedValues></itemDefinition>"
                + "<itemDefinition name=\"tRisk\"><typeRef>string</typeRef></itemDefinition>";

        // When
        ParsedRuleSet parsed = read(loanTable(definitions, EMPLOYMENT_INPUT, RISK_OUTPUT, TABLE_FIRST));

        // Then the input is a string with its allowed values and the table evaluates
        assertThat(parsed.canonicalJson()).contains("\"allowed\":[\"SALARIED\",\"SELF_EMPLOYED\"]",
                "\"name\":\"employment\"", "\"type\":\"string\"");
        assertThat(evaluate(parsed, Map.of("employment", "SALARIED")))
                .isEqualTo(new DecisionResult.Matched(List.of("r1"), Map.of("risk", "LOW")));
    }

    @Test
    void RLE_225_a_collection_item_definition_is_a_list_input() {
        // Given a list of strings used as an input type
        String definitions = "<itemDefinition name=\"tFlags\" isCollection=\"true\"><typeRef>string</typeRef>"
                + "</itemDefinition>";
        String input = "<input><inputExpression typeRef=\"tFlags\"><text>flags</text></inputExpression></input>";

        // When
        ParsedRuleSet parsed = read(loanTable(definitions, input, "<output name=\"risk\" typeRef=\"string\"/>",
                TABLE_FIRST).replace("\"SALARIED\"", "-"));

        // Then
        assertThat(parsed.canonicalJson()).contains("\"name\":\"flags\"", "\"type\":\"list<string>\"");
    }

    @Test
    void RLE_225_a_structure_types_a_decision_with_several_outputs() {
        // Given a decision whose result is a structure of two components, the outputs carry no type of their own
        String definitions = "<itemDefinition name=\"tOffer\"><itemComponent name=\"status\"><typeRef>string</typeRef>"
                + "</itemComponent><itemComponent name=\"limit\"><typeRef>number</typeRef></itemComponent>"
                + "</itemDefinition>";
        String dmn = HEAD + definitions + "<decision id=\"card-offer\" name=\"Card offer\">"
                + "<variable name=\"Card offer\" typeRef=\"tOffer\"/>" + TABLE_FIRST
                + "<input><inputExpression typeRef=\"number\"><text>income</text></inputExpression></input>"
                + "<output name=\"status\"/><output name=\"limit\"/>"
                + "<rule id=\"r1\"><inputEntry><text>&gt;= 20000000</text></inputEntry>"
                + "<outputEntry><text>\"APPROVED\"</text></outputEntry><outputEntry><text>50000000</text></outputEntry>"
                + "</rule></decisionTable></decision></definitions>";

        // When
        ParsedRuleSet parsed = read(dmn);

        // Then the outputs take the component types
        assertThat(parsed.canonicalJson()).contains("\"name\":\"status\",\"type\":\"string\"",
                "\"name\":\"limit\",\"type\":\"decimal\"");
        assertThat(evaluate(parsed, Map.of("income", 25_000_000)))
                .isEqualTo(new DecisionResult.Matched(List.of("r1"),
                        Map.of("status", "APPROVED", "limit", new BigDecimal("50000000"))));
    }

    @Test
    void RLE_225_outputs_that_do_not_match_the_components_of_the_decision_structure_are_refused() {
        String definitions = "<itemDefinition name=\"tOffer\"><itemComponent name=\"status\"><typeRef>string</typeRef>"
                + "</itemComponent><itemComponent name=\"limit\"><typeRef>number</typeRef></itemComponent>"
                + "</itemDefinition>";
        String dmn = HEAD + definitions + "<decision id=\"card-offer\" name=\"Card offer\">"
                + "<variable name=\"Card offer\" typeRef=\"tOffer\"/>" + TABLE_FIRST
                + "<input><inputExpression typeRef=\"number\"><text>income</text></inputExpression></input>"
                + "<output name=\"status\" typeRef=\"string\"/>"
                + "<rule id=\"r1\"><inputEntry><text>-</text></inputEntry><outputEntry><text>\"X\"</text>"
                + "</outputEntry></rule></decisionTable></decision></definitions>";

        assertCode(dmn, "DMN_TYPE_UNSUPPORTED", "name each output after one component");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            time                                    | typeRef 'time' of input 'employment' has no schema version 1 type
            Any                                     | typeRef 'Any' of input 'employment' has no schema version 1 type
            days and time duration                  | typeRef 'days and time duration' of input 'employment' has no schema version 1 type
            """)
    void RLE_225_a_built_in_type_with_no_v1_form_is_refused_with_a_named_code(String typeRef, String message) {
        String input = "<input><inputExpression typeRef=\"" + typeRef + "\"><text>employment</text></inputExpression></input>";

        assertCode(loanTable("", input, "<output name=\"risk\" typeRef=\"string\"/>", TABLE_FIRST)
                .replace("\"SALARIED\"", "-"), "DMN_TYPE_UNSUPPORTED", message);
    }

    @Test
    void RLE_225_a_structured_input_a_type_constraint_and_a_self_reference_are_refused_with_a_named_code() {
        String structure = "<itemDefinition name=\"tEmployment\"><itemComponent name=\"kind\"><typeRef>string</typeRef>"
                + "</itemComponent></itemDefinition>";
        String constraint = "<itemDefinition name=\"tEmployment\"><typeRef>string</typeRef><typeConstraint>"
                + "<text>string length(?) &gt; 2</text></typeConstraint></itemDefinition>";
        String loop = "<itemDefinition name=\"tEmployment\"><typeRef>tEmployment</typeRef></itemDefinition>";
        String risk = "<itemDefinition name=\"tRisk\"><typeRef>string</typeRef></itemDefinition>";

        assertCode(loanTable(structure + risk, EMPLOYMENT_INPUT, RISK_OUTPUT, TABLE_FIRST), "DMN_TYPE_UNSUPPORTED",
                "Input 'employment' has a structured type");
        assertCode(loanTable(constraint + risk, EMPLOYMENT_INPUT, RISK_OUTPUT, TABLE_FIRST), "DMN_TYPE_UNSUPPORTED",
                "has a typeConstraint");
        assertCode(loanTable(loop + risk, EMPLOYMENT_INPUT, RISK_OUTPUT, TABLE_FIRST), "DMN_TYPE_UNSUPPORTED",
                "refers to itself");
    }

    @Test
    void RLE_225_an_item_definition_that_no_column_uses_does_not_stop_the_import() {
        // Given a nested structure and a function item nobody uses
        String unused = "<itemDefinition name=\"tUnused\"><itemComponent name=\"a\"><itemComponent name=\"b\">"
                + "<typeRef>string</typeRef></itemComponent></itemComponent></itemDefinition>";
        String definitions = unused + "<itemDefinition name=\"tEmployment\"><typeRef>string</typeRef></itemDefinition>"
                + "<itemDefinition name=\"tRisk\"><typeRef>string</typeRef></itemDefinition>";

        assertThat(read(loanTable(definitions, EMPLOYMENT_INPUT, RISK_OUTPUT, TABLE_FIRST)).model().name())
                .isEqualTo("loan-risk");
    }

    @Test
    void RLE_225_a_literal_outside_the_output_values_is_refused_and_one_inside_is_kept() {
        // Given outputValues that list LOW and MEDIUM only; the table answers HIGH in its last rule
        String definitions = "<itemDefinition name=\"tEmployment\"><typeRef>string</typeRef></itemDefinition>"
                + "<itemDefinition name=\"tRisk\"><typeRef>string</typeRef></itemDefinition>";
        String output = "<output name=\"risk\" typeRef=\"tRisk\"><outputValues><text>\"LOW\", \"MEDIUM\"</text>"
                + "</outputValues></output>";

        assertCode(loanTable(definitions, EMPLOYMENT_INPUT, output, TABLE_FIRST), "DMN_OUTPUT_NOT_ALLOWED",
                "Rule r2, output risk: '\"HIGH\"' is not one of the output values");
        assertThat(read(loanTable(definitions, EMPLOYMENT_INPUT, output.replace("\"MEDIUM\"", "\"HIGH\""), TABLE_FIRST))
                .model().name()).isEqualTo("loan-risk");
    }

    @Test
    void RLE_225_allowed_values_that_are_a_range_are_refused_not_dropped() {
        String definitions = "<itemDefinition name=\"tEmployment\"><typeRef>number</typeRef><allowedValues>"
                + "<text>[0..100]</text></allowedValues></itemDefinition>"
                + "<itemDefinition name=\"tRisk\"><typeRef>string</typeRef></itemDefinition>";

        assertCode(loanTable(definitions, EMPLOYMENT_INPUT, RISK_OUTPUT, TABLE_FIRST).replace("\"SALARIED\"", "5"),
                "DMN_ALLOWED_VALUES_UNSUPPORTED", "must be a list of constants");
    }

    // ------------------------------------------------------------------ RLE-226 names made from the decision

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            _3b2953a3-745f-4d2e-b55d-75c8c5ae653c | decision-3b2953a3-745f-4d2e-b55d-75c8c5ae653c
            cardFee                               | card-fee
            Decision_1                            | decision-1
            Loan Risk Score                       | loan-risk-score
            """)
    void RLE_226_a_rule_set_name_is_made_from_the_decision_id(String id, String expected) {
        String dmn = HEAD + "<decision id=\"" + id + "\" name=\"" + id + "\">" + TABLE_FIRST
                + "<input><inputExpression typeRef=\"number\"><text>age</text></inputExpression></input>"
                + "<output name=\"ok\" typeRef=\"boolean\"/><rule id=\"r1\"><inputEntry><text>-</text></inputEntry>"
                + "<outputEntry><text>true</text></outputEntry></rule></decisionTable></decision></definitions>";

        assertThat(read(dmn.replace("id=\"d\" name=\"d\"", "id=\"_\" name=\"_\"")).model().name()).isEqualTo(expected);
    }

    @Test
    void RLE_226_a_kebab_case_id_is_kept_as_written() {
        assertThat(DmnReader.kebabFrom("card-fee")).isEqualTo("card-fee");
        assertThat(DmnReader.kebabFrom("x".repeat(150))).hasSize(100);
        assertThat(DmnReader.kebabFrom("???")).isEmpty();
    }

    @Test
    void RLE_226_a_single_output_with_no_name_takes_the_name_of_the_decision() {
        // Given the table of an approval with a one-column output that the modeller left unnamed
        String dmn = HEAD + "<decision id=\"_3b29\" name=\"Approval Status\"><variable name=\"Approval Status\""
                + " typeRef=\"string\"/>" + TABLE_FIRST
                + "<input><inputExpression typeRef=\"number\"><text>age</text></inputExpression></input>"
                + "<output><outputValues><text>\"Approved\", \"Declined\"</text></outputValues></output>"
                + "<rule id=\"r1\"><inputEntry><text>&gt;= 18</text></inputEntry><outputEntry><text>\"Approved\"</text>"
                + "</outputEntry></rule><rule id=\"r2\"><inputEntry><text>-</text></inputEntry><outputEntry>"
                + "<text>\"Declined\"</text></outputEntry></rule></decisionTable></decision></definitions>";

        ParsedRuleSet parsed = read(dmn);

        assertThat(parsed.canonicalJson()).contains("\"outputs\":[{\"name\":\"Approval_Status\",\"type\":\"string\"}]");
        assertThat(evaluate(parsed, Map.of("age", 17)))
                .isEqualTo(new DecisionResult.Matched(List.of("r2"), Map.of("Approval_Status", "Declined")));
    }

    @Test
    void RLE_226_two_unnamed_outputs_are_refused() {
        String dmn = HEAD + "<decision id=\"x\" name=\"x\">" + TABLE_FIRST
                + "<input><inputExpression typeRef=\"number\"><text>age</text></inputExpression></input>"
                + "<output typeRef=\"string\"/><output typeRef=\"string\"/>"
                + "<rule id=\"r1\"><inputEntry><text>-</text></inputEntry><outputEntry><text>\"a\"</text></outputEntry>"
                + "<outputEntry><text>\"b\"</text></outputEntry></rule></decisionTable></decision></definitions>";

        assertThatThrownBy(() -> read(dmn)).isInstanceOf(ValidationFailedException.class)
                .hasMessageContaining("Every output column needs a name attribute");
    }

    // ------------------------------------------------------------------ RLE-227 COLLECT with an aggregation

    private static String fees(String aggregation, String outputType) {
        return HEAD + "<decision id=\"monthly-fees\" name=\"Monthly fees\">"
                + "<decisionTable hitPolicy=\"COLLECT\" aggregation=\"" + aggregation + "\">"
                + "<input><inputExpression typeRef=\"number\"><text>txCount</text></inputExpression></input>"
                + "<output name=\"fee\" typeRef=\"" + outputType + "\"/>"
                + "<rule id=\"base\"><inputEntry><text>-</text></inputEntry><outputEntry><text>10000</text>"
                + "</outputEntry></rule><rule id=\"busy\"><inputEntry><text>&gt;= 20</text></inputEntry>"
                + "<outputEntry><text>5000</text></outputEntry></rule><rule id=\"very-busy\"><inputEntry>"
                + "<text>&gt;= 50</text></inputEntry><outputEntry><text>7000</text></outputEntry></rule>"
                + "</decisionTable></decision></definitions>";
    }

    @Test
    void RLE_227_collect_sum_adds_the_matched_rules() {
        ParsedRuleSet parsed = read(fees("SUM", "number"));

        assertThat(parsed.canonicalJson()).contains("\"collect\":\"sum\"", "\"type\":\"decimal\"");
        assertThat(evaluate(parsed, Map.of("txCount", 60)))
                .isEqualTo(new DecisionResult.Matched(List.of("base", "busy", "very-busy"),
                        Map.of("fee", new BigDecimal("22000"))));
        assertThat(evaluate(parsed, Map.of("txCount", 5)))
                .isEqualTo(new DecisionResult.Matched(List.of("base"), Map.of("fee", new BigDecimal("10000"))));
    }

    @Test
    void RLE_227_collect_count_counts_the_matched_rules_as_an_integer() {
        ParsedRuleSet parsed = read(fees("COUNT", "number"));

        assertThat(parsed.canonicalJson()).contains("\"collect\":\"count\"", "\"type\":\"integer\"");
        DecisionResult.Matched matched = (DecisionResult.Matched) evaluate(parsed, Map.of("txCount", 25));
        assertThat(matched.ruleIds()).containsExactly("base", "busy");
        assertThat(matched.outputs().get("fee")).hasToString("2");
    }

    @Test
    void RLE_227_collect_min_and_max_pick_the_smallest_and_the_largest() {
        assertThat(evaluate(read(fees("MIN", "number")), Map.of("txCount", 60)))
                .isEqualTo(new DecisionResult.Matched(List.of("base", "busy", "very-busy"),
                        Map.of("fee", new BigDecimal("5000"))));
        assertThat(evaluate(read(fees("MAX", "number")), Map.of("txCount", 60)))
                .isEqualTo(new DecisionResult.Matched(List.of("base", "busy", "very-busy"),
                        Map.of("fee", new BigDecimal("10000"))));
    }

    @Test
    void RLE_227_an_aggregate_exports_as_dmn_and_imports_again_with_the_same_hash() {
        ParsedRuleSet first = read(fees("SUM", "number"));

        String exported = DmnWriter.write(first.canonicalJson());
        ParsedRuleSet second = read(exported);

        assertThat(exported).contains("hitPolicy=\"COLLECT\" aggregation=\"SUM\"");
        assertThat(second.canonicalJson()).isEqualTo(first.canonicalJson());
        ParsedRuleSet count = read(fees("COUNT", "number"));
        assertThat(read(DmnWriter.write(count.canonicalJson())).canonicalJson()).isEqualTo(count.canonicalJson());
    }

    @Test
    void RLE_227_an_aggregation_with_another_hit_policy_or_name_or_outputs_is_refused_with_a_named_code() {
        assertCode(fees("SUM", "number").replace("hitPolicy=\"COLLECT\"", "hitPolicy=\"FIRST\""),
                "DMN_AGGREGATION_UNSUPPORTED", "belongs to hit policy COLLECT");
        assertCode(fees("AVERAGE", "number"), "DMN_AGGREGATION_UNSUPPORTED", "is not SUM, COUNT, MIN or MAX");
        assertCode(fees("SUM", "number").replace("<output name=\"fee\" typeRef=\"number\"/>",
                        "<output name=\"fee\" typeRef=\"number\"/><output name=\"tax\" typeRef=\"number\"/>")
                        .replace("</outputEntry></rule>", "</outputEntry><outputEntry><text>1</text></outputEntry></rule>"),
                "DMN_AGGREGATION_UNSUPPORTED", "needs exactly one output");
    }

    @Test
    void RLE_227_a_table_with_aggregates_on_several_outputs_has_no_dmn_form() {
        String yaml = "{\"hitPolicy\":\"collect\",\"inputs\":[{\"name\":\"a\",\"type\":\"integer\"}],"
                + "\"outputs\":[{\"collect\":\"sum\",\"name\":\"x\",\"type\":\"integer\"},"
                + "{\"collect\":\"count\",\"name\":\"y\",\"type\":\"integer\"}],\"ruleSet\":\"r\","
                + "\"rules\":[{\"id\":\"R1\",\"then\":{\"x\":1,\"y\":1},\"when\":{}}],\"schemaVersion\":1}";

        assertThatThrownBy(() -> DmnWriter.write(yaml)).hasMessageContaining("DMN has one aggregation per table");
    }

    // ------------------------------------------------------------------ allowed values never dropped (RLE-225)

    private static final String TYPES_PLAIN = "<itemDefinition name=\"tRisk\"><typeRef>string</typeRef></itemDefinition>";

    @Test
    void RLE_225_allowed_values_of_an_alias_of_an_alias_reach_the_input() {
        String definitions = "<itemDefinition name=\"tBase\"><typeRef>string</typeRef><allowedValues>"
                + "<text>\"SALARIED\", \"SELF_EMPLOYED\"</text></allowedValues></itemDefinition>"
                + "<itemDefinition name=\"tEmployment\"><typeRef>tBase</typeRef></itemDefinition>" + TYPES_PLAIN;

        assertThat(read(loanTable(definitions, EMPLOYMENT_INPUT, RISK_OUTPUT, TABLE_FIRST)).canonicalJson())
                .contains("\"allowed\":[\"SALARIED\",\"SELF_EMPLOYED\"]");
    }

    @Test
    void RLE_225_input_values_and_the_allowed_values_of_its_type_both_hold() {
        String definitions = "<itemDefinition name=\"tEmployment\"><typeRef>string</typeRef><allowedValues>"
                + "<text>\"SALARIED\", \"SELF_EMPLOYED\", \"RETIRED\"</text></allowedValues></itemDefinition>"
                + TYPES_PLAIN;
        String input = "<input><inputExpression typeRef=\"tEmployment\"><text>employment</text></inputExpression>"
                + "<inputValues><text>\"SALARIED\", \"RETIRED\", \"STUDENT\"</text></inputValues></input>";

        assertThat(read(loanTable(definitions, input, RISK_OUTPUT, TABLE_FIRST)).canonicalJson())
                .contains("\"allowed\":[\"SALARIED\",\"RETIRED\"]");
        assertCode(loanTable(definitions, input.replace("\"SALARIED\", \"RETIRED\", \"STUDENT\"", "\"STUDENT\""),
                RISK_OUTPUT, TABLE_FIRST), "DMN_ALLOWED_VALUES_UNSUPPORTED", "have no value in common");
    }

    @Test
    void RLE_225_allowed_values_of_a_component_are_checked_on_an_output_that_has_its_own_type() {
        String definitions = "<itemDefinition name=\"tOffer\"><itemComponent name=\"status\"><typeRef>string</typeRef>"
                + "<allowedValues><text>\"APPROVED\", \"DECLINED\"</text></allowedValues></itemComponent>"
                + "</itemDefinition>";
        String dmn = HEAD + definitions + "<decision id=\"card-offer\" name=\"Card offer\">"
                + "<variable name=\"Card offer\" typeRef=\"tOffer\"/>" + TABLE_FIRST
                + "<input><inputExpression typeRef=\"number\"><text>income</text></inputExpression></input>"
                + "<output name=\"status\" typeRef=\"string\"/><rule id=\"r1\"><inputEntry><text>-</text></inputEntry>"
                + "<outputEntry><text>\"PENDING\"</text></outputEntry></rule></decisionTable></decision></definitions>";

        assertCode(dmn, "DMN_OUTPUT_NOT_ALLOWED", "is not one of the output values");
        assertThat(read(dmn.replace("PENDING", "APPROVED")).model().name()).isEqualTo("card-offer");
    }

    @Test
    void RLE_225_allowed_values_on_a_structure_are_refused_not_dropped() {
        String definitions = "<itemDefinition name=\"tOffer\"><allowedValues><text>\"A\"</text></allowedValues>"
                + "<itemComponent name=\"status\"><typeRef>string</typeRef></itemComponent></itemDefinition>";
        String dmn = HEAD + definitions + "<decision id=\"card-offer\" name=\"Card offer\">"
                + "<variable name=\"Card offer\" typeRef=\"tOffer\"/>" + TABLE_FIRST
                + "<input><inputExpression typeRef=\"number\"><text>income</text></inputExpression></input>"
                + "<output name=\"status\" typeRef=\"string\"/><rule id=\"r1\"><inputEntry><text>-</text></inputEntry>"
                + "<outputEntry><text>\"A\"</text></outputEntry></rule></decisionTable></decision></definitions>";

        assertCode(dmn, "DMN_ALLOWED_VALUES_UNSUPPORTED", "allowed values belong to a component");
    }

    // ------------------------------------------------------------------ aggregates with no match and null inputs (RLE-227)

    private static String feesOnTier(String aggregation) {
        return fees(aggregation, "number").replace("<text>txCount</text>", "<text>txCount</text>")
                .replace("<rule id=\"base\"><inputEntry><text>-</text>", "<rule id=\"base\"><inputEntry><text>&gt;= 1</text>");
    }

    @Test
    void RLE_227_with_no_matching_rule_count_is_zero_and_sum_is_null() {
        // Given a fee table whose first rule needs at least 1 transaction; a customer with none matches no rule
        Map<String, Object> none = Map.of("txCount", 0);

        DecisionResult.Outcome count = evaluate(read(feesOnTier("COUNT")), none);
        DecisionResult.Outcome sum = evaluate(read(feesOnTier("SUM")), none);

        // Then, as FEEL: count([]) is 0 and sum([]) is null
        assertThat(count).isInstanceOf(DecisionResult.NoMatch.class);
        assertThat(((DecisionResult.NoMatch) count).outputs().get("fee")).hasToString("0");
        assertThat(sum).isInstanceOf(DecisionResult.NoMatch.class);
        assertThat(((DecisionResult.NoMatch) sum).outputs().get("fee")).isNull();
    }

    @Test
    void RLE_227_a_null_input_matches_no_comparison_so_count_is_zero_and_sum_is_null() {
        Map<String, Object> nullInput = new java.util.HashMap<>();
        nullInput.put("txCount", null);

        for (String aggregation : List.of("COUNT", "SUM")) {
            DecisionResult.Outcome outcome = evaluate(read(feesOnTier(aggregation)), nullInput);
            Object fee = outcome instanceof DecisionResult.NoMatch n ? n.outputs().get("fee") : "not NoMatch";
            if (aggregation.equals("COUNT")) {
                assertThat(fee).as(aggregation).hasToString("0");
            } else {
                assertThat(fee).as(aggregation).isNull();
            }
        }
    }
}
