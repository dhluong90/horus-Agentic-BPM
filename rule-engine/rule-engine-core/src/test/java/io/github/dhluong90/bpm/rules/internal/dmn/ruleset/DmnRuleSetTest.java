package io.github.dhluong90.bpm.rules.internal.dmn.ruleset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.horus.bpm.rules.api.DecisionInput;
import com.horus.bpm.rules.api.DecisionResult;
import com.horus.bpm.rules.api.SourceFormat;
import com.horus.bpm.rules.api.ValidationFailedException;
import com.horus.bpm.rules.internal.eval.CompiledRuleSet;
import com.horus.bpm.rules.internal.read.ParsedRuleSet;
import com.horus.bpm.rules.internal.read.RuleSetReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * DMN 1.5 decision table import and export over schema version 1 rule sets. Statements: TR-STD-25 (import and
 * export, round trip), TR-STD-04 (refusal by name), TR-STD-03 (hash identity, standards-compliance 3.4), RLE-006 (null semantics).
 */
class DmnRuleSetTest {

    // ------------------------------------------------------------------ import of a hand-written table

    @Test
    void TR_STD_25_a_hand_written_modeller_table_imports_and_evaluates() {
        // Given a DMN 1.3 table as a common modeller writes it: generated ids, labels, an empty cell, DMNDI
        byte[] file = resource("/rules/dmn/card-fee-modeller.dmn");

        // When
        ParsedRuleSet parsed = RuleSetReader.read(SourceFormat.DMN_XML, file);
        CompiledRuleSet table = CompiledRuleSet.compile(parsed.model());

        // Then
        assertThat(parsed.model().name()).isEqualTo("card-fee");
        assertThat(parsed.canonicalJson()).isEqualTo("{\"hitPolicy\":\"first\","
                + "\"inputs\":[{\"description\":\"Card tier\",\"name\":\"tier\",\"type\":\"string\"},"
                + "{\"description\":\"Transactions this month\",\"name\":\"txCount\",\"type\":\"integer\"}],"
                + "\"outputs\":[{\"description\":\"Fee (VND)\",\"name\":\"fee\",\"type\":\"decimal\"}],"
                + "\"ruleSet\":\"card-fee\","
                + "\"rules\":[{\"description\":\"Gold customers pay nothing\",\"id\":\"DecisionRule_0fk2x7a\","
                + "\"then\":{\"fee\":0},\"when\":{\"tier\":\"== 'GOLD'\"}},"
                + "{\"id\":\"DecisionRule_1lh0wd3\",\"then\":{\"fee\":10000},\"when\":{\"txCount\":\">= 3\"}},"
                + "{\"id\":\"DecisionRule_09rn3ah\",\"then\":{\"fee\":50000},\"when\":{}}],"
                + "\"schemaVersion\":1}");
        assertThat(evaluate(table, Map.of("tier", "GOLD", "txCount", 5))).isEqualTo(
                new DecisionResult.Matched(List.of("DecisionRule_0fk2x7a"), Map.of("fee", BigDecimal.ZERO)));
        assertThat(evaluate(table, Map.of("tier", "SILVER", "txCount", 3))).isEqualTo(
                new DecisionResult.Matched(List.of("DecisionRule_1lh0wd3"), Map.of("fee", new BigDecimal("10000"))));
        assertThat(evaluate(table, Map.of("tier", "SILVER", "txCount", 2))).isEqualTo(
                new DecisionResult.Matched(List.of("DecisionRule_09rn3ah"), Map.of("fee", new BigDecimal("50000"))));
    }

    // ------------------------------------------------------------------ round trips

    @Test
    void TR_STD_03_card_fee_exported_as_dmn_imports_back_with_the_same_canonical_hash() {
        // Given card-fee.yaml (RLE-001), with money outputs, an input description and a default row
        ParsedRuleSet yaml = RuleSetReader.read(SourceFormat.YAML, resource("/rules/card-fee.yaml"));

        // When
        String dmn = DmnWriter.write(yaml.canonicalJson());
        ParsedRuleSet back = RuleSetReader.read(SourceFormat.DMN_XML, dmn.getBytes(StandardCharsets.UTF_8));

        // Then
        assertThat(dmn).contains("xmlns=\"https://www.omg.org/spec/DMN/20230324/MODEL/\"", "hitPolicy=\"FIRST\"",
                "<text>\"GOLD\"</text>", "<text>&gt;= 3</text>", "<text>-</text>", "<text>money(10000, \"VND\")</text>",
                "horus:type=\"money\"", "horus:type=\"integer\"");
        assertThat(back.canonicalJson()).isEqualTo(yaml.canonicalJson());
        assertThat(back.canonicalHash()).isEqualTo(yaml.canonicalHash());
        assertThat(DmnWriter.write(back.canonicalJson())).isEqualTo(dmn);
    }

    @Test
    void TR_STD_25_dmn_import_then_export_then_import_keeps_the_canonical_hash() {
        for (String file : List.of("/rules/dmn/card-fee-modeller.dmn", "/rules/dmn/loan-eligibility.dmn")) {
            // Given
            ParsedRuleSet first = RuleSetReader.read(SourceFormat.DMN_XML, resource(file));

            // When
            String exported = DmnWriter.write(first.canonicalJson());
            ParsedRuleSet second = RuleSetReader.read(SourceFormat.DMN_XML, exported.getBytes(StandardCharsets.UTF_8));

            // Then
            assertThat(second.canonicalHash()).as(file).isEqualTo(first.canonicalHash());
            assertThat(DmnWriter.write(second.canonicalJson())).as(file).isEqualTo(exported);
        }
    }

    // ------------------------------------------------------------------ the FEEL subset and null semantics

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', value = {
        "\"GOLD\"                    | == 'GOLD'",
        "< 18                        | < 18",
        "<= 18.5                     | <= 18.5",
        "> -1                        | > -1",
        ">= 3                        | >= 3",
        "= 7                         | == 7",
        "[18..65]                    | between 18 and 65",
        "(18..65]                    | age > 18 and age <= 65",
        "]18..65[                    | age > 18 and age < 65",
        "[18..65)                    | age >= 18 and age < 65",
        "\"VN\", \"SG\"              | in ['VN', 'SG']",
        "not(\"VN\")                 | != 'VN'",
        "not(\"VN\", \"SG\")         | not in ['VN', 'SG']",
        "not(< 18)                   | not (age < 18)",
        "< 18, > 65                  | age < 18 or age > 65",
        "< 18, (60..65]              | age < 18 or (age > 60 and age <= 65)",
        "not(< 18, > 65)             | not (age < 18 or age > 65)",
        "null                        | is null",
        "not(null)                   | is not null",
        "true                        | == true",
        "date(\"2026-01-01\")        | == date('2026-01-01')",
        ">= date and time(\"2026-01-01T00:00:00+07:00\") | >= datetime('2025-12-31T17:00:00Z')",
        "money(12.50, \"USD\")       | == 12.50 USD",
        "\"it's \\\"x\\\"\"          | == 'it\\'s \"x\"'",
    })
    void TR_STD_25_feel_unary_tests_translate_to_one_v1_normal_form(String feel, String v1) {
        assertThat(V1Forms.condition("age", Feel.parseUnaryTests(feel))).isEqualTo(v1);
    }

    @ParameterizedTest(name = "{0} with age={1} -> {2}")
    @CsvSource(nullValues = "NULL", delimiter = '|', value = {
        "[18..65]       | 18   | true",
        "[18..65]       | 65   | true",
        "[18..65]       | 66   | false",
        "[18..65]       | NULL | false",
        "(18..65]       | 18   | false",
        "(18..65]       | NULL | false",
        "< 18, > 65     | 70   | true",
        "< 18, > 65     | NULL | false",
        "not(< 18)      | 20   | true",
        "not(< 18)      | NULL | false",
        "17, 18         | NULL | false",
        "not(17, 18)    | NULL | true",
        "not(17)        | NULL | true",
        "null           | NULL | true",
        "not(null)      | NULL | false",
        "not(null)      | 30   | true",
    })
    void RLE_006_translated_cells_follow_feel_null_semantics(String feel, Integer age, boolean matches) {
        // Given a one-row table whose only cell is the translated test
        String dmn = table("<input><inputExpression typeRef=\"number\"><text>age</text></inputExpression></input>",
                "<rule id=\"R1\"><inputEntry><text>" + escape(feel) + "</text></inputEntry>"
                        + "<outputEntry><text>true</text></outputEntry></rule>");
        CompiledRuleSet table = CompiledRuleSet.compile(RuleSetReader.read(SourceFormat.DMN_XML, utf8(dmn)).model());
        Map<String, Object> inputs = new HashMap<>();
        inputs.put("age", age);

        // When
        DecisionResult.Outcome outcome = evaluate(table, inputs);

        // Then: FEEL truth table of DMN 1.5 section 10.3.2.5, a cell matches only when it is true
        assertThat(outcome instanceof DecisionResult.Matched).isEqualTo(matches);
    }

    @Test
    void TR_STD_25_every_construct_of_the_subset_evaluates_as_feel_specifies() {
        CompiledRuleSet loan = CompiledRuleSet.compile(
                RuleSetReader.read(SourceFormat.DMN_XML, resource("/rules/dmn/loan-eligibility.dmn")).model());
        LocalDate applied = LocalDate.parse("2026-12-01");

        assertThat(ruleOf(loan, 17, "VN", 1000, applied, false)).isEqualTo("L1");
        assertThat(ruleOf(loan, 30, "US", 1000, applied, false)).isEqualTo("L2");
        assertThat(ruleOf(loan, 30, null, 1000, applied, false)).isEqualTo("L2"); // not("VN","SG") matches null
        assertThat(ruleOf(loan, 30, "SG", null, applied, false)).isEqualTo("L3");
        assertThat(ruleOf(loan, 30, "VN", 500_000_000, applied, false)).isEqualTo("L4");
        assertThat(ruleOf(loan, 30, "VN", 500_000_001, applied, true)).isEqualTo("L5");
        assertThat(ruleOf(loan, 30, "VN", 1000, LocalDate.parse("2025-12-31"), false)).isEqualTo("L6");
        assertThat(ruleOf(loan, null, "VN", 1000, applied, true)).isEqualTo("L6"); // [18..65] is null for a null age
    }

    // ------------------------------------------------------------------ refusals

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
        "? > 18                       | Rule R1, input age: '? > 18': '?' (a unary test expression) is not supported before full FEEL",
        "abs(-20)                     | Rule R1, input age: 'abs(-20)': function 'abs' is not supported before full FEEL",
        "income                       | Rule R1, input age: 'income': name 'income' is not supported",
        "!= 18                        | Rule R1, input age: '!= 18': '!=' is not a unary test",
        "> 18 and < 65                | Rule R1, input age: '> 18 and < 65': 'and' is not supported in a cell",
        "[18..\"x\"]                  | range endpoints 18 and \"x\" are of different types",
        "@\"2026-01-01\"              | character '@' at position 1 is not supported before full FEEL",
        "date(\"2026-02-30\")         | date(\"2026-02-30\") is not an ISO 8601 date",
    })
    void TR_STD_04_an_input_entry_outside_the_subset_is_refused_naming_the_cell(String cell, String message) {
        String dmn = table("<input><inputExpression typeRef=\"number\"><text>age</text></inputExpression></input>",
                "<rule id=\"R1\"><inputEntry><text>" + escape(cell) + "</text></inputEntry>"
                        + "<outputEntry><text>true</text></outputEntry></rule>");

        assertThatThrownBy(() -> RuleSetReader.read(SourceFormat.DMN_XML, utf8(dmn)))
                .isInstanceOf(ValidationFailedException.class)
                .hasMessageContaining(message)
                .hasMessageContaining("(line ");
    }

    @Test
    void TR_STD_04_unsupported_dmn_features_are_refused_by_name_with_line_and_stage() {
        String input = "<input><inputExpression typeRef=\"number\"><text>age</text></inputExpression></input>";
        String rule = "<rule id=\"R1\"><inputEntry><text>-</text></inputEntry><outputEntry><text>true</text></outputEntry></rule>";

        assertRefused(table(input, rule).replace("hitPolicy=\"FIRST\"", "hitPolicy=\"PRIORITY\""),
                "Hit policy PRIORITY arrives in stage S1 (release 1.1)");
        assertRefused(table(input, rule).replace("hitPolicy=\"FIRST\"", "hitPolicy=\"COLLECT\" aggregation=\"SUM\""),
                "Hit policy COLLECT with aggregation SUM arrives in stage S1");
        assertRefused(table(input, rule.replace("<text>true</text></outputEntry>", "<text>age * 2</text></outputEntry>")),
                "Rule R1, output ok: 'age * 2'");
        assertRefused(table(input.replace("<text>age</text>", "<text>applicant.age</text>"), rule),
                "Input expression 'applicant.age' must be a plain input name");
        assertRefused(table(input.replace("typeRef=\"number\"", "typeRef=\"tAge\""), rule),
                "typeRef 'tAge' of input 'age' is not supported; item definitions arrive in stage S1");
        assertRefused(table(input, rule).replace("<decision ", "<businessKnowledgeModel id=\"bkm1\" name=\"b\"/><decision "),
                "<businessKnowledgeModel id=\"bkm1\"> is not supported in this release; it arrives in stage S2 (release 1.2)");
        assertRefused(table(input, rule).replace("<decision ", "<itemDefinition id=\"tAge\" name=\"tAge\"/><decision "),
                "<itemDefinition id=\"tAge\"> is not supported in this release; it arrives in stage S1");
        assertRefused(DMN15 + "<decision id=\"a\" name=\"a\"><literalExpression><text>1</text></literalExpression></decision>"
                + "</definitions>", "<literalExpression> is not supported in this release; it arrives in stage S1");
        assertRefused(table(input, rule).replace("</definitions>", "<decision id=\"second\" name=\"second\"/></definitions>"),
                "More than one <decision>");
        assertRefused(table(input, rule).replace("id=\"rate-check\"", "id=\"Decision_1\"").replace("name=\"rate-check\"", "name=\"Rate check\"")
                        .replace("id=\"d\" name=\"d\"", "id=\"Definitions_1\" name=\"DRD\""),
                "give the decision a kebab-case id such as card-fee");
        assertRefused(table(input, rule).replace("<inputExpression ", "<inputExpression expressionLanguage=\"javascript\" "),
                "expressionLanguage 'javascript' is not FEEL");
        assertRefused("<definitions xmlns=\"http://www.omg.org/spec/CMMN/20151109/MODEL\"/>",
                "The root element must be a DMN 1.2 to 1.5 <definitions>");
    }

    // ------------------------------------------------------------------ XML safety

    @Test
    void TR_STD_25_xml_with_a_dtd_or_external_entity_is_refused_without_resolving_it() {
        String xxe = "<?xml version=\"1.0\"?><!DOCTYPE definitions [<!ENTITY secret SYSTEM \"file:///etc/passwd\">]>"
                + DMN15.substring(DMN15.indexOf("<definitions")) + "<decision id=\"x\" name=\"&secret;\"/></definitions>";
        String laughs = "<?xml version=\"1.0\"?><!DOCTYPE lolz [<!ENTITY lol \"lol\"><!ENTITY lol2 \"&lol;&lol;&lol;&lol;\">]>"
                + "<definitions xmlns=\"https://www.omg.org/spec/DMN/20230324/MODEL/\" name=\"&lol2;\"/>";

        assertRefused(xxe, "DTDs are not allowed in a DMN document");
        assertRefused(laughs, "DTDs are not allowed in a DMN document");
    }

    @Test
    void TR_STD_25_xml_size_depth_and_text_limits_hold() {
        String deep = DMN15 + "<extensionElements>" + "<x:a xmlns:x=\"urn:x\">".repeat(40) + "</x:a>".repeat(40)
                + "</extensionElements></definitions>";
        String longText = table("<input><inputExpression typeRef=\"string\"><text>s</text></inputExpression></input>",
                "<rule id=\"R1\"><inputEntry><text>\"" + "a".repeat(70_000) + "\"</text></inputEntry>"
                        + "<outputEntry><text>true</text></outputEntry></rule>");
        byte[] huge = new byte[RuleSetReader.MAX_SOURCE_BYTES + 1];

        assertRefused(deep, "Elements nested deeper than 32");
        assertRefused(longText, "Element text longer than 65536 characters");
        assertThatThrownBy(() -> RuleSetReader.read(SourceFormat.DMN_XML, huge)).hasMessageContaining("larger than");
        assertRefused("<definitions", "not well-formed XML");
    }

    // ------------------------------------------------------------------ export refusals

    @Test
    void TR_STD_04_a_rule_set_without_a_decision_table_form_is_refused_at_export_naming_the_rule() {
        String any = "{\"hitPolicy\":\"first\",\"inputs\":[{\"name\":\"a\",\"type\":\"integer\"},{\"name\":\"b\",\"type\":\"integer\"}],"
                + "\"outputs\":[{\"name\":\"o\",\"type\":\"boolean\"}],\"ruleSet\":\"r\",\"rules\":[{\"id\":\"R7\",\"then\":{\"o\":true},"
                + "\"when\":{\"any\":[{\"a\":\"> 1\"},{\"b\":\"> 1\"}]}}],\"schemaVersion\":1}";
        String calc = any.replace("\"when\":{\"any\":[{\"a\":\"> 1\"},{\"b\":\"> 1\"}]}", "\"when\":{\"a\":\"a > b\"}");
        String output = any.replace("\"then\":{\"o\":true}", "\"then\":{\"o\":\"a > 1\"}")
                .replace("\"when\":{\"any\":[{\"a\":\"> 1\"},{\"b\":\"> 1\"}]}", "\"when\":{}");

        assertThatThrownBy(() -> DmnWriter.write(any)).hasMessageContaining("Rule R7 uses any");
        assertThatThrownBy(() -> DmnWriter.write(calc)).hasMessageContaining("Rule R7, input a: condition 'a > b'");
        assertThatThrownBy(() -> DmnWriter.write(output)).hasMessageContaining("Rule R7, output o: output 'a > 1' is not a literal");
    }

    // ------------------------------------------------------------------ helpers

    static final String DMN15 = "<definitions xmlns=\"https://www.omg.org/spec/DMN/20230324/MODEL/\" id=\"d\" name=\"d\""
            + " namespace=\"urn:test\">";

    static String table(String inputs, String rules) {
        return DMN15 + "<decision id=\"rate-check\" name=\"rate-check\"><decisionTable hitPolicy=\"FIRST\">" + inputs
                + "<output name=\"ok\" typeRef=\"boolean\"/>" + rules + "</decisionTable></decision></definitions>";
    }

    static void assertRefused(String dmn, String message) {
        assertThatThrownBy(() -> RuleSetReader.read(SourceFormat.DMN_XML, utf8(dmn)))
                .isInstanceOf(ValidationFailedException.class)
                .hasMessageContaining(message);
    }

    static String ruleOf(CompiledRuleSet table, Integer age, String country, Integer amount, LocalDate applied, boolean vip) {
        Map<String, Object> inputs = new HashMap<>();
        inputs.put("age", age);
        inputs.put("country", country);
        inputs.put("amount", amount);
        inputs.put("appliedOn", applied);
        inputs.put("vip", vip);
        return ((DecisionResult.Matched) evaluate(table, inputs)).ruleIds().getFirst();
    }

    static DecisionResult.Outcome evaluate(CompiledRuleSet table, Map<String, ?> inputs) {
        return table.evaluate(DecisionInput.of(inputs));
    }

    static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    static byte[] resource(String path) {
        try (InputStream in = DmnRuleSetTest.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("missing test resource " + path);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
