package io.github.dhluong90.bpm.rules.internal.dmn.ruleset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.horus.bpm.rules.api.SourceFormat;
import com.horus.bpm.rules.api.ValidationFailedException;
import com.horus.bpm.rules.internal.eval.CompiledRuleSet;
import com.horus.bpm.rules.internal.json.EngineJson;
import com.horus.bpm.rules.internal.read.ParsedRuleSet;
import com.horus.bpm.rules.internal.read.RuleSetReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** RLE-039 and ADR-0026: one behaviour hash per meaning, whatever the authoring style or the format. */
class NormalFormTest {

    // ------------------------------------------------------------------ the frozen printer

    record Golden(int line, String kind, String type, String cell, String normal) {
        @Override
        public String toString() {
            return "line " + line + ": " + kind + " " + type + " '" + cell + "' -> '" + normal + "'";
        }
    }

    static Stream<Golden> golden() {
        String text = new String(DmnRuleSetTest.resource("/rules/normal-form.v1.txt"), StandardCharsets.UTF_8);
        List<Golden> rows = new ArrayList<>();
        String[] lines = text.split("\n");
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].isBlank() || lines[i].startsWith("#")) {
                continue;
            }
            String[] f = lines[i].split("\\|", 4);
            rows.add(new Golden(i + 1, f[0].strip(), f[1].strip(), f[2].strip(), f[3].strip()));
        }
        return rows.stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("golden")
    void RLE_039_the_v1_printer_is_frozen_by_the_golden_file(Golden row) {
        // When
        ParsedRuleSet parsed = RuleSetReader.read(SourceFormat.JSON, document(row.kind(), row.type(), row.cell()));
        JsonNode printed = cell(parsed, row.kind());

        // Then: the normal form, a document that still compiles, and a printer that is idempotent
        if (row.normal().startsWith("#")) {
            assertThat(printed.isString()).as("a JSON literal, not text").isFalse();
            assertThat(printed.toString()).isEqualTo(row.normal().substring(1));
        } else {
            assertThat(printed.stringValue()).isEqualTo(row.normal());
            ParsedRuleSet again = RuleSetReader.read(SourceFormat.JSON, document(row.kind(), row.type(), row.normal()));
            assertThat(again.canonicalHash()).isEqualTo(parsed.canonicalHash());
        }
        CompiledRuleSet.compile(parsed.model());
    }

    // ------------------------------------------------------------------ RLE-039

    static final String STYLE_A = StyleCheck.A;

    static final String STYLE_B = StyleCheck.B;

    @Test
    void RLE_039_authoring_style_and_dmn_round_trip_keep_one_behaviour_hash() {
        // Given the same table in author style (A) and in normal form (B)
        byte[] a = STYLE_A.getBytes(StandardCharsets.UTF_8);
        byte[] b = STYLE_B.getBytes(StandardCharsets.UTF_8);

        // When
        ParsedRuleSet fromA = RuleSetReader.read(SourceFormat.YAML, a);
        ParsedRuleSet fromB = RuleSetReader.read(SourceFormat.YAML, b);
        String dmn = DmnWriter.write(fromA.canonicalJson());
        ParsedRuleSet once = RuleSetReader.read(SourceFormat.DMN_XML, dmn.getBytes(StandardCharsets.UTF_8));
        ParsedRuleSet twice = RuleSetReader.read(SourceFormat.DMN_XML,
                DmnWriter.write(once.canonicalJson()).getBytes(StandardCharsets.UTF_8));

        // Then
        assertThat(STYLE_B).contains("2026-11-30T17:00:00Z").doesNotContain("+07:00");
        assertThat(fromA.canonicalJson()).isEqualTo(fromB.canonicalJson());
        assertThat(fromA.canonicalHash()).isEqualTo(fromB.canonicalHash())
                .isEqualTo(once.canonicalHash()).isEqualTo(twice.canonicalHash());
        assertThat(fromA.canonicalJson()).contains("\">= 3\"", "\"between 1 and 5\"", "\"is not null\"", "\"!= 'VN'\"",
                "\"in ['VN', 'SG']\"", "\"== datetime('2026-11-30T17:00:00Z')\"", "\"== 12.50 USD\"");
    }

    @Test
    void RLE_039_money_cells_print_at_the_currency_scale_and_keep_their_hash_through_dmn() {
        String rules = "<rule id=\"M1\"><inputEntry><text>-</text></inputEntry><outputEntry><text>money(12.5, \"USD\")</text></outputEntry></rule>"
                + "<rule id=\"M2\"><inputEntry><text>-</text></inputEntry><outputEntry><text>money(10000.00, \"VND\")</text></outputEntry></rule>"
                + "<rule id=\"M3\"><inputEntry><text>-</text></inputEntry><outputEntry><text>money(-5, \"USD\")</text></outputEntry></rule>";
        String xml = DmnRuleSetTest.table("<input><inputExpression typeRef=\"number\"><text>age</text></inputExpression></input>", rules)
                .replace("<output name=\"ok\" typeRef=\"boolean\"/>", "<output name=\"ok\" typeRef=\"Any\" horus:type=\"money\"/>")
                .replace("<definitions ", "<definitions xmlns:horus=\"urn:horus-bpm:dmn:extensions:1\" ");

        ParsedRuleSet first = RuleSetReader.read(SourceFormat.DMN_XML, xml.getBytes(StandardCharsets.UTF_8));
        ParsedRuleSet second = RuleSetReader.read(SourceFormat.DMN_XML,
                DmnWriter.write(first.canonicalJson()).getBytes(StandardCharsets.UTF_8));

        assertThat(first.canonicalJson()).contains("\"12.50 USD\"", "\"10000 VND\"", "\"-5.00 USD\"");
        assertThat(second.canonicalHash()).isEqualTo(first.canonicalHash());
    }

    @Test
    void RLE_039_a_money_amount_finer_than_its_currency_is_refused_with_its_line() {
        String xml = DmnRuleSetTest.table("<input><inputExpression typeRef=\"number\"><text>age</text></inputExpression></input>",
                "\n<rule id=\"M1\"><inputEntry><text>-</text></inputEntry><outputEntry><text>money(12.505, \"USD\")</text></outputEntry></rule>");

        assertThatThrownBy(() -> RuleSetReader.read(SourceFormat.DMN_XML, xml.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(ValidationFailedException.class)
                .hasMessageContaining("Rule M1, output ok: 'money(12.505, \"USD\")'")
                .hasMessageContaining("more fraction digits than USD allows")
                .hasMessageContaining("(line 2)");
    }

    // ------------------------------------------------------------------ helpers

    static byte[] document(String kind, String type, String cell) {
        ObjectNode doc = EngineJson.NODES.objectNode();
        doc.put("schemaVersion", 1).put("ruleSet", "golden").put("hitPolicy", "first");
        boolean when = kind.equals("when");
        doc.putArray("inputs").add(EngineJson.NODES.objectNode().put("name", "x").put("type", when ? type : "integer"))
                .add(EngineJson.NODES.objectNode().put("name", "y").put("type", "integer"));
        doc.putArray("outputs").add(EngineJson.NODES.objectNode().put("name", "o").put("type", when ? "boolean" : type));
        ObjectNode rule = doc.putArray("rules").addObject().put("id", "R1");
        if (when) {
            rule.putObject("when").put("x", cell);
            rule.putObject("then").put("o", true);
        } else {
            rule.putObject("when");
            rule.putObject("then").put("o", cell);
        }
        return EngineJson.JSON.writeValueAsBytes(doc);
    }

    static JsonNode cell(ParsedRuleSet parsed, String kind) {
        JsonNode rule = EngineJson.JSON.readTree(parsed.canonicalJson()).get("rules").get(0);
        return kind.equals("when") ? rule.get("when").get("x") : rule.get("then").get("o");
    }
}
