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
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** QA review of DMN import and export (#144). */
class DmnRuleSetQaTest {

    static final String DMN15 = "<definitions xmlns=\"https://www.omg.org/spec/DMN/20230324/MODEL/\" id=\"d\" name=\"d\""
            + " namespace=\"urn:test\" xmlns:horus=\"urn:horus-bpm:dmn:extensions:1\">";

    static String table(String hit, String inputs, String outputs, String rules) {
        return DMN15 + "<decision id=\"qa-table\" name=\"qa-table\"><decisionTable" + hit + ">" + inputs + outputs
                + rules + "</decisionTable></decision></definitions>";
    }

    static String numInput(String name) {
        return "<input><inputExpression typeRef=\"number\"><text>" + name + "</text></inputExpression></input>";
    }

    static String esc(String t) {
        return t.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    static ParsedRuleSet dmn(String xml) {
        return RuleSetReader.read(SourceFormat.DMN_XML, xml.getBytes(StandardCharsets.UTF_8));
    }

    static ParsedRuleSet reimport(ParsedRuleSet first) {
        return dmn(DmnWriter.write(first.canonicalJson()));
    }

    // ---------------------------------------------------------------- round trip, DMN first

    @ParameterizedTest(name = "{0} | {1} | {2}")
    @CsvSource(delimiter = '|', value = {
        "number | <text></text>                       | 1",
        "number | <text>   </text>                    | 1",
        "number | <text>-</text>                      | 1",
        "number | <text>[-10..-1]</text>              | 1",
        "number | <text>&lt; -5</text>                | 1",
        "number | <text>not(-1, -2)</text>            | 1",
        "number | <text>not([1..5])</text>            | 1",
        "number | <text>.5</text>                     | 1",
        "number | <text>1.50</text>                   | 1.50",
        "date   | <text>date(\"2024-02-29\")</text>   | 1",
        "date   | <text>[date(\"2026-01-01\")..date(\"2026-12-31\")]</text> | 1",
        "date and time | <text>&gt; date and time(\"2026-01-01T00:00:00+07:00\")</text> | 1",
        "string | <text>\"Hà Nội\", \"a\\nb\", \"q\\\"x\"</text> | 1",
        "string | <text>not(\"VN\", null)</text>      | 1",
    })
    void TR_STD_25_edge_cells_survive_import_export_import_with_the_same_hash(String type, String cell, String out) {
        // Given
        String xml = table(" hitPolicy=\"FIRST\"",
                "<input><inputExpression typeRef=\"" + type + "\"><text>x</text></inputExpression></input>",
                "<output name=\"o\" typeRef=\"number\"/>",
                "<rule id=\"R1\"><inputEntry>" + cell + "</inputEntry><outputEntry><text>" + out + "</text></outputEntry></rule>");
        ParsedRuleSet first = dmn(xml);

        // When
        ParsedRuleSet second = reimport(first);

        // Then
        assertThat(second.canonicalJson()).isEqualTo(first.canonicalJson());
        assertThat(second.canonicalHash()).isEqualTo(first.canonicalHash());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"money(12.5, \"USD\")", "money(10000.00, \"VND\")", "money(-5, \"USD\")", "money(0.10, \"USD\")",
        "money(12.50, \"USD\")", "money(10000, \"VND\")"})
    void TR_STD_25_money_cells_survive_import_export_import_with_the_same_hash(String money) {
        // Given a money input with the cell and a money output with the same literal
        String xml = table(" hitPolicy=\"FIRST\"",
                "<input horus:type=\"money\"><inputExpression typeRef=\"Any\"><text>amount</text></inputExpression></input>",
                "<output name=\"fee\" typeRef=\"Any\" horus:type=\"money\"/>",
                "<rule id=\"R1\"><inputEntry><text>&gt;= " + money + "</text></inputEntry><outputEntry><text>" + money
                        + "</text></outputEntry></rule>");
        ParsedRuleSet first = dmn(xml);

        // When
        ParsedRuleSet second = reimport(first);

        // Then
        assertThat(second.canonicalJson()).isEqualTo(first.canonicalJson());
        assertThat(second.canonicalHash()).isEqualTo(first.canonicalHash());
    }

    // ---------------------------------------------------------------- round trip, YAML first (standards-compliance 3.4)

    @ParameterizedTest(name = "{0}: {1}")
    @CsvSource(delimiter = '|', value = {
        "decimal  | \"== 7\"",
        "decimal  | \"x >= 3\"",
        "decimal  | \"x between 1 and 5\"",
        "decimal  | \"x > 1 and x <= 5\"",
        "decimal  | \"x is not null\"",
        "string   | \"in ['VN','SG']\"",
        "string   | \"not in ['VN', 'SG']\"",
        "string   | \"x != 'VN'\"",
        "datetime | \"== datetime('2026-12-01T00:00:00+07:00')\"",
        "money    | \"== 12.5 USD\"",
    })
    void TR_STD_03_a_valid_v1_cell_exported_as_dmn_imports_back_with_the_v1_hash(String type, String cell) {
        // Given a v1 rule set with one valid condition cell
        String yaml = "schemaVersion: 1\nruleSet: qa-table\nhitPolicy: first\n"
                + "inputs:\n  - { name: x, type: " + type + " }\n"
                + "outputs:\n  - { name: o, type: boolean }\n"
                + "rules:\n  - id: R1\n    when: { x: " + cell + " }\n    then: { o: true }\n";
        ParsedRuleSet v1 = RuleSetReader.read(SourceFormat.YAML, yaml.getBytes(StandardCharsets.UTF_8));
        CompiledRuleSet.compile(v1.model());

        // When
        ParsedRuleSet back = reimport(v1);

        // Then: a definition exported from v1 and imported again keeps its v1 hash
        assertThat(back.canonicalHash()).as(back.canonicalJson() + " vs " + v1.canonicalJson()).isEqualTo(v1.canonicalHash());
    }

    // ---------------------------------------------------------------- modeller file with extensionElements

    @Test
    void TR_STD_25_a_camunda_7_style_file_with_extension_elements_everywhere_imports() {
        // Given a modeller file: extensionElements on definitions, decision, table, input, output, rule and entries,
        // foreign attributes, DMNDI, and the hit policy left to its DMN default
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<definitions xmlns=\"https://www.omg.org/spec/DMN/20191111/MODEL/\" xmlns:camunda=\"http://camunda.org/schema/1.0/dmn\""
                + " xmlns:dmndi=\"https://www.omg.org/spec/DMN/20191111/DMNDI/\" xmlns:dc=\"http://www.omg.org/spec/DMN/20180521/DC/\""
                + " xmlns:biodi=\"http://bpmn.io/schema/dmn/biodi/2.0\" id=\"Definitions_1\" name=\"DRD\" namespace=\"http://camunda.org/schema/1.0/dmn\">\n"
                + "  <extensionElements><camunda:properties><camunda:property name=\"a\" value=\"b\"/></camunda:properties></extensionElements>\n"
                + "  <decision id=\"card-fee\" name=\"Card fee\" camunda:historyTimeToLive=\"180\">\n"
                + "    <extensionElements><camunda:properties/></extensionElements>\n"
                + "    <decisionTable id=\"DecisionTable_1\" hitPolicy=\"FIRST\">\n"
                + "      <extensionElements><camunda:x/></extensionElements>\n"
                + "      <input id=\"Input_1\" label=\"Tier\" camunda:inputVariable=\"t\" biodi:width=\"192\">\n"
                + "        <extensionElements><camunda:x/></extensionElements>\n"
                + "        <inputExpression id=\"InputExpression_1\" typeRef=\"string\"><extensionElements/><text>tier</text></inputExpression>\n"
                + "      </input>\n"
                + "      <output id=\"Output_1\" label=\"Fee\" name=\"fee\" typeRef=\"number\" biodi:width=\"192\">\n"
                + "        <extensionElements><camunda:x/></extensionElements>\n"
                + "      </output>\n"
                + "      <rule id=\"DecisionRule_1\">\n"
                + "        <extensionElements><camunda:x/></extensionElements>\n"
                + "        <inputEntry id=\"UnaryTests_1\"><extensionElements><camunda:x/></extensionElements><text>\"GOLD\"</text></inputEntry>\n"
                + "        <outputEntry id=\"LiteralExpression_1\"><extensionElements><camunda:x/></extensionElements><text>0</text></outputEntry>\n"
                + "      </rule>\n"
                + "    </decisionTable>\n"
                + "  </decision>\n"
                + "  <dmndi:DMNDI><dmndi:DMNDiagram id=\"DMNDiagram_1\"><dmndi:DMNShape id=\"s\" dmnElementRef=\"card-fee\">"
                + "<dc:Bounds height=\"80\" width=\"180\" x=\"160\" y=\"100\"/></dmndi:DMNShape></dmndi:DMNDiagram></dmndi:DMNDI>\n"
                + "</definitions>\n";

        // When
        ParsedRuleSet parsed = dmn(xml);
        CompiledRuleSet compiled = CompiledRuleSet.compile(parsed.model());

        // Then
        assertThat(compiled.evaluate(DecisionInput.of(Map.of("tier", "GOLD"))))
                .isInstanceOf(DecisionResult.Matched.class);
    }

    // ---------------------------------------------------------------- PD-088: unique and collect from DMN

    @Test
    void RLE_002_a_dmn_table_with_the_default_hit_policy_imports_as_unique_and_zero_matches_is_no_match() {
        // Given a DMN table with no hitPolicy attribute (DMN default UNIQUE), as a modeller writes it
        String xml = table("", numInput("age"), "<output name=\"band\" typeRef=\"string\"/>",
                "<rule id=\"R1\"><inputEntry><text>&lt; 18</text></inputEntry><outputEntry><text>\"MINOR\"</text></outputEntry></rule>"
                        + "<rule id=\"R2\"><inputEntry><text>[18..65]</text></inputEntry><outputEntry><text>\"ADULT\"</text></outputEntry></rule>");

        // When
        CompiledRuleSet compiled = CompiledRuleSet.compile(dmn(xml).model());

        // Then: PD-088, unique with zero matches is NO_MATCH
        assertThat(compiled.evaluate(DecisionInput.of(Map.of("age", 70)))).isEqualTo(new DecisionResult.NoMatch());
    }

    @Test
    void RLE_002_a_dmn_collect_table_keeps_null_outputs_of_matched_rules() {
        // Given a COLLECT table where one matched rule outputs null
        String xml = table(" hitPolicy=\"COLLECT\"", numInput("age"), "<output name=\"flag\" typeRef=\"string\"/>",
                "<rule id=\"R1\"><inputEntry><text>-</text></inputEntry><outputEntry><text>\"A\"</text></outputEntry></rule>"
                        + "<rule id=\"R2\"><inputEntry><text>-</text></inputEntry><outputEntry><text>null</text></outputEntry></rule>");

        // When
        CompiledRuleSet compiled = CompiledRuleSet.compile(dmn(xml).model());
        DecisionResult.Outcome outcome = compiled.evaluate(DecisionInput.of(Map.of("age", 30)));

        // Then: PD-088, collect keeps nulls
        assertThat(outcome).isInstanceOf(DecisionResult.Matched.class);
        assertThat(((DecisionResult.Matched) outcome).ruleIds()).containsExactly("R1", "R2");
    }

    @Test
    void TR_STD_04_a_hit_policy_the_engine_cannot_run_yet_is_refused_at_import_with_its_line() {
        // Given a DMN table with no hitPolicy attribute (DMN default UNIQUE)
        String xml = table("", numInput("age"), "<output name=\"band\" typeRef=\"string\"/>",
                "<rule id=\"R1\"><inputEntry><text>-</text></inputEntry><outputEntry><text>\"A\"</text></outputEntry></rule>");

        // When, Then: either it runs (RLE_002 above) or the refusal names the line, as every other refusal does
        try {
            CompiledRuleSet.compile(dmn(xml).model());
        } catch (ValidationFailedException e) {
            assertThat(e.getMessage()).contains("(line ");
        }
    }

    // ---------------------------------------------------------------- PD-058 on negated ranges and lists

    @ParameterizedTest(name = "{0} with x={1} -> {2}")
    @CsvSource(nullValues = "NULL", delimiter = '|', value = {
        "not([1..5])     | NULL | false",
        "not([1..5])     | 3    | false",
        "not([1..5])     | 7    | true",
        "not(> 5, < 1)   | NULL | false",
        "not(null, 5)    | NULL | false",
        "not(null, 5)    | 7    | true",
        "null, < 1       | NULL | true",
        "null, < 1       | 3    | false",
        "(1..5)          | 1    | false",
        "(1..5)          | 5    | false",
        "[1..5)          | 5    | false",
    })
    void RLE_006_negated_and_mixed_cells_follow_feel_null_semantics(String feel, Integer x, boolean matches) {
        String xml = table(" hitPolicy=\"FIRST\"", numInput("x"), "<output name=\"o\" typeRef=\"boolean\"/>",
                "<rule id=\"R1\"><inputEntry><text>" + esc(feel) + "</text></inputEntry><outputEntry><text>true</text></outputEntry></rule>");
        CompiledRuleSet compiled = CompiledRuleSet.compile(dmn(xml).model());
        Map<String, Object> in = new HashMap<>();
        in.put("x", x);

        assertThat(compiled.evaluate(DecisionInput.of(in)) instanceof DecisionResult.Matched).isEqualTo(matches);
    }

    // ---------------------------------------------------------------- refusals with exact lines

    @Test
    void TR_STD_04_a_refused_cell_names_its_exact_line() {
        // Given the unsupported cell on line 7
        String xml = DMN15 + "\n"                                                        // 1
                + "<decision id=\"qa-table\" name=\"qa-table\">\n"                      // 2
                + "<decisionTable hitPolicy=\"FIRST\">\n"                                // 3
                + numInput("age") + "\n"                                                 // 4
                + "<output name=\"o\" typeRef=\"boolean\"/>\n"                           // 5
                + "<rule id=\"R1\">\n"                                                   // 6
                + "<inputEntry><text>? &gt; 1</text></inputEntry>\n"                     // 7
                + "<outputEntry><text>true</text></outputEntry></rule>\n"
                + "</decisionTable></decision></definitions>";

        assertThatThrownBy(() -> dmn(xml)).isInstanceOf(ValidationFailedException.class)
                .hasMessageContaining("Rule R1, input age").hasMessageContaining("(line 7)");
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
        "<decisionService id=\"ds\" name=\"ds\"/>                 | <decisionService id=\"ds\"> is not supported",
        "<knowledgeSource id=\"ks\" name=\"ks\"/>                 | <knowledgeSource id=\"ks\"> is not supported",
        "<import namespace=\"urn:x\" importType=\"urn:y\"/>        | <import> is not supported",
        "<textAnnotation id=\"ta\"><text>x</text></textAnnotation> | <textAnnotation id=\"ta\"> is not supported",
    })
    void TR_STD_04_unsupported_top_level_elements_are_refused_by_name_and_line(String element, String message) {
        String xml = DMN15 + "\n" + element + "\n<decision id=\"qa-table\" name=\"qa-table\"><decisionTable hitPolicy=\"FIRST\">"
                + numInput("age") + "<output name=\"o\" typeRef=\"boolean\"/><rule id=\"R1\"><inputEntry><text>-</text>"
                + "</inputEntry><outputEntry><text>true</text></outputEntry></rule></decisionTable></decision></definitions>";

        assertThatThrownBy(() -> dmn(xml)).isInstanceOf(ValidationFailedException.class)
                .hasMessageContaining(message).hasMessageContaining("(line 2)");
    }

    @Test
    void TR_STD_04_a_duplicate_input_column_is_refused() {
        String xml = table(" hitPolicy=\"FIRST\"", numInput("age") + numInput("age"), "<output name=\"o\" typeRef=\"boolean\"/>",
                "<rule id=\"R1\"><inputEntry><text>&lt; 18</text></inputEntry><inputEntry><text>&gt; 65</text></inputEntry>"
                        + "<outputEntry><text>true</text></outputEntry></rule>");

        assertThatThrownBy(() -> dmn(xml)).isInstanceOf(ValidationFailedException.class).hasMessageContaining("age");
    }
}
