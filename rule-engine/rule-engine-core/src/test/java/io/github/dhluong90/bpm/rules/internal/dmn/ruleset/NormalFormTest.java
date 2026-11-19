package io.github.dhluong90.bpm.rules.internal.dmn.ruleset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.rules.api.SourceFormat;
import io.github.dhluong90.bpm.rules.api.ValidationFailedException;
import io.github.dhluong90.bpm.rules.internal.eval.CompiledRuleSet;
import io.github.dhluong90.bpm.rules.internal.json.EngineJson;
import io.github.dhluong90.bpm.rules.internal.read.ParsedRuleSet;
import io.github.dhluong90.bpm.rules.internal.read.RuleSetReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** RLE-045 and ADR-0026: one behaviour hash per meaning, whatever the authoring style or the format. */
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
    void RLE_045_the_v1_printer_is_frozen_by_the_golden_file(Golden row) {
        // When
        if (row.normal().equals("<omitted>")) {
            // an explicit null output, without a default, is the omitted cell
            ObjectNode doc = (ObjectNode) EngineJson.JSON.readTree(document("then", "string", "x"));
            ((ObjectNode) doc.get("rules").get(0).get("then")).putNull("o");
            ParsedRuleSet nulled = RuleSetReader.read(SourceFormat.JSON, EngineJson.JSON.writeValueAsBytes(doc));
            assertThat(cell(nulled, "then")).isNull();
            return;
        }
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

    // ------------------------------------------------------------------ RLE-045

    static final String STYLE_A = StyleCheck.A;

    static final String STYLE_B = StyleCheck.B;

    @Test
    void RLE_045_authoring_style_and_dmn_round_trip_keep_one_behaviour_hash() {
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
    void RLE_045_money_cells_print_at_the_currency_scale_and_keep_their_hash_through_dmn() {
        String rules = "<rule id=\"M1\"><inputEntry><text>-</text></inputEntry><outputEntry><text>money(12.5, \"USD\")</text></outputEntry></rule>"
                + "<rule id=\"M2\"><inputEntry><text>-</text></inputEntry><outputEntry><text>money(10000.00, \"VND\")</text></outputEntry></rule>"
                + "<rule id=\"M3\"><inputEntry><text>-</text></inputEntry><outputEntry><text>money(-5, \"USD\")</text></outputEntry></rule>";
        String xml = DmnRuleSetTest.table("<input><inputExpression typeRef=\"number\"><text>age</text></inputExpression></input>", rules)
                .replace("<output name=\"ok\" typeRef=\"boolean\"/>", "<output name=\"ok\" typeRef=\"Any\" horus:type=\"money\"/>")
                .replace("<definitions ", "<definitions xmlns:horus=\"urn:horus-bpm:dmn:extensions:v1\" ");

        ParsedRuleSet first = RuleSetReader.read(SourceFormat.DMN_XML, xml.getBytes(StandardCharsets.UTF_8));
        ParsedRuleSet second = RuleSetReader.read(SourceFormat.DMN_XML,
                DmnWriter.write(first.canonicalJson()).getBytes(StandardCharsets.UTF_8));

        assertThat(first.canonicalJson()).contains("\"12.50 USD\"", "\"10000 VND\"", "\"-5.00 USD\"");
        assertThat(second.canonicalHash()).isEqualTo(first.canonicalHash());
    }

    @Test
    void RLE_045_a_money_amount_finer_than_its_currency_is_refused_with_its_line() {
        String xml = DmnRuleSetTest.table("<input><inputExpression typeRef=\"number\"><text>age</text></inputExpression></input>",
                "\n<rule id=\"M1\"><inputEntry><text>-</text></inputEntry><outputEntry><text>money(12.505, \"USD\")</text></outputEntry></rule>");

        assertThatThrownBy(() -> RuleSetReader.read(SourceFormat.DMN_XML, xml.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(ValidationFailedException.class)
                .hasMessageContaining("Rule M1, output ok: 'money(12.505, \"USD\")'")
                .hasMessageContaining("more fraction digits than USD allows")
                .hasMessageContaining("(line 2)");
    }

    // ------------------------------------------------------------------ RLE-045: different behaviour never shares a hash

    static String hashOf(String yaml) {
        return RuleSetReader.read(SourceFormat.YAML, yaml.getBytes(StandardCharsets.UTF_8)).canonicalHash();
    }

    static String withRate(String rate) {
        return StyleCheck.B.replace("rate: \"0.10\"", "rate: " + rate);
    }

    static String withCell(String column, String cell) {
        return StyleCheck.B.replace("{ id: R1, when: { x: \">= 3\" }", "{ id: R1, when: { " + column + ": \"" + cell + "\" }");
    }

    @Test
    void RLE_045_one_hash_per_behaviour_in_any_style_and_never_for_different_behaviour() {
        String base = hashOf(StyleCheck.B);

        // n1: a JSON number that does not survive an exact round trip is refused with its line; as a string it is accepted
        assertThatThrownBy(() -> hashOf(withRate("0.1000000000000000055511151231257827")))
                .isInstanceOf(ValidationFailedException.class)
                .hasMessageContaining("cannot be stored exactly").hasMessageContaining("(line 14)");
        assertThat(hashOf(withRate("\"0.1000000000000000055511151231257827\""))).isNotEqualTo(hashOf(withRate("0.1")));
        // n2: the scale is kept
        assertThat(hashOf(withRate("\"0.1\""))).isNotEqualTo(base);
        assertThat(hashOf(withRate("0.10"))).isEqualTo(base); // the JSON number 0.10 is the string "0.10"
        // n3: an integer beyond 2^53 - 1 as a JSON number is refused; as a string it keeps its digits
        assertThatThrownBy(() -> hashOf(withRate("9007199254740993"))).isInstanceOf(ValidationFailedException.class);
        assertThat(hashOf(withRate("\"9007199254740993\""))).isNotEqualTo(hashOf(withRate("\"9007199254740992\"")));
        // n4: the column name is dropped only for the column's own input
        assertThat(hashOf(withCell("x", "y >= 3"))).isNotEqualTo(base);
        assertThat(hashOf(withCell("x", "x > y"))).isNotEqualTo(hashOf(withCell("x", "> y")));
        // n5: a Cyrillic look-alike is a different literal, printed as written
        String cyrillic = StyleCheck.B.replace("\"!= 'VN'\"", "\"!= '\u0412N'\"");
        ParsedRuleSet lookalike = RuleSetReader.read(SourceFormat.YAML, cyrillic.getBytes(StandardCharsets.UTF_8));
        assertThat(lookalike.canonicalHash()).isNotEqualTo(base);
        assertThat(lookalike.canonicalJson()).contains("\"!= '\u0412N'\"");
        // n6: a zero-width space is printed as an escape and has its own hash; a bidirectional override is refused
        String zeroWidth = StyleCheck.B.replace("\"!= 'VN'\"", "\"!= 'V\u200BN'\"");
        ParsedRuleSet invisible = RuleSetReader.read(SourceFormat.YAML, zeroWidth.getBytes(StandardCharsets.UTF_8));
        assertThat(invisible.canonicalJson()).contains("!= 'V\\\\u200BN'");
        assertThat(invisible.canonicalHash()).isNotEqualTo(base);
        String override = StyleCheck.B.replace("\"!= 'VN'\"", "\"!= 'V\u202EN'\"");
        assertThatThrownBy(() -> hashOf(override)).isInstanceOf(ValidationFailedException.class)
                .hasMessageContaining("Rule R4, input country").hasMessageContaining("U+202E");
    }

    @Test
    void RLE_045_invisible_characters_are_escaped_in_cells_kept_as_written() {
        // n6, kept-as-written cells: a cross-column test, a compound cell and a calculated output
        String cross = withCell("x", "y != 'V\u200BN'").replace("- { name: y, type: integer }", "- { name: y, type: string }");
        String compound = StyleCheck.B.replace("\"!= 'VN'\"", "\"country != 'V\u200BN' and country != 'a'\"");
        String calcExpr = StyleCheck.B.replace("rate: \"0.10\"", "rate: \"x * 0.10\"").replace("o: \"'R2'\"", "o: \"'a\u200Bb'\"");

        for (String doc : java.util.List.of(cross, compound, calcExpr)) {
            ParsedRuleSet parsed = RuleSetReader.read(SourceFormat.YAML, doc.getBytes(StandardCharsets.UTF_8));
            assertThat(parsed.canonicalJson()).doesNotContain("\u200B").contains("\\\\u200B");
            CompiledRuleSet.compile(RuleSetReader.readCanonical(parsed.canonicalJson()));
        }
        assertThat(RuleSetReader.read(SourceFormat.YAML, cross.getBytes(StandardCharsets.UTF_8)).canonicalJson())
                .contains("\"y != 'V\\\\u200BN'\"");
        assertThat(RuleSetReader.read(SourceFormat.YAML, compound.getBytes(StandardCharsets.UTF_8)).canonicalJson())
                .contains("\"country != 'V\\\\u200BN' and country != 'a'\"");
        assertThat(CanonicalCells.escapeLiterals("x * 2 + 'a\\'\u200B'")).isEqualTo("x * 2 + 'a\\'\\u200B'");
    }

    @Test
    void RLE_045_bidirectional_controls_are_refused_in_descriptions() {
        String doc = StyleCheck.B.replace("ruleSet: style-check", "ruleSet: style-check\ndescription: \"ok \u202E evil\"");

        assertThatThrownBy(() -> hashOf(doc)).isInstanceOf(ValidationFailedException.class)
                .hasMessageContaining("Description, description").hasMessageContaining("U+202E");
    }

    @Test
    void RLE_045_a_bidirectional_control_in_a_dmn_cell_is_refused_with_its_line() {
        String xml = DmnRuleSetTest.table("<input><inputExpression typeRef=\"string\"><text>c</text></inputExpression></input>",
                "\n<rule id=\"R1\"><inputEntry><text>\"V\u202EN\"</text></inputEntry><outputEntry><text>true</text></outputEntry></rule>");

        assertThatThrownBy(() -> RuleSetReader.read(SourceFormat.DMN_XML, xml.getBytes(StandardCharsets.UTF_8)))
                .hasMessageContaining("Rule R1, input c").hasMessageContaining("U+202E").hasMessageContaining("(line 2)");
    }

    @Test
    void RLE_045_the_golden_file_is_pinned_to_schema_version_1() throws Exception {
        // Any edit of the golden file is a new schema version (ADR-0026 decision 3); this pin fails on any change.
        byte[] golden = DmnRuleSetTest.resource("/rules/normal-form.v1.txt");
        String sha = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(golden));
        assertThat(sha).isEqualTo(GOLDEN_V1_SHA256);
    }

    /**
     * The pin of the schema version 1 golden file. Changed in 0.2.0 (#19) only by appending the rows of the function
     * call production (round, daysBetween): appending rows for a new production is allowed within schema version 1,
     * by the architect's ruling on pull request #252 (ADR-0026 decision 3); editing a shipped row is not, and
     * {@link #RLE_045_the_golden_lines_of_0_1_0_are_an_unchanged_prefix} proves no 0.1.0 row changed.
     */
    static final String GOLDEN_V1_SHA256 = "1412c2592c66ff0db7cc844fa4c47d1234ba8dd5f682f855866fba0f089b97fd";

    @Test
    void RLE_045_the_golden_lines_of_0_1_0_are_an_unchanged_prefix() throws Exception {
        // A later release may append golden lines for a new production (0.2.0: round and daysBetween, #19); it may never
        // change a line that 0.1.0 shipped. The first GOLDEN_0_1_0_LENGTH bytes are the 0.1.0 file, byte for byte.
        byte[] golden = DmnRuleSetTest.resource("/rules/normal-form.v1.txt");
        byte[] prefix = java.util.Arrays.copyOf(golden, GOLDEN_0_1_0_LENGTH);
        String sha = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(prefix));
        assertThat(sha).isEqualTo(GOLDEN_0_1_0_SHA256);
    }

    /** The golden file as 0.1.0 shipped it: its length and SHA-256, the pin of that release. */
    static final int GOLDEN_0_1_0_LENGTH = 4003;

    static final String GOLDEN_0_1_0_SHA256 = "dc44beae55cd278fe820d7c9e22def959b854655946bbfa4711387ca6f7eb40f";

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
