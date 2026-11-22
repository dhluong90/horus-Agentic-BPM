package io.github.dhluong90.bpm.rules.internal.dmn.ruleset;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dhluong90.bpm.rules.internal.json.EngineJson;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** The shorthand of a spreadsheet cell (PD-107) turns into the normal form of the engine and prints back as written. */
class CsvTableTest {

    private static final String BASE = """
            {"schemaVersion":1,"ruleSet":"loan-pricing","hitPolicy":"first",
             "inputs":[{"name":"segment","type":"string"},{"name":"amount","type":"money"},{"name":"age","type":"integer"},
                       {"name":"country","type":"string"}],
             "outputs":[{"name":"rate","type":"decimal"},{"name":"note","type":"string"}],
             "rules":[{"id":"R1","when":{},"then":{"rate":"1.5","note":"'x'"}}]}
            """;

    private static JsonNode read(String row) {
        String file = "Rule,segment,amount,age,country,rate,note\r\n" + row + "\r\n";
        return EngineJson.JSON.readTree(CsvTable.read(BASE, file.getBytes(StandardCharsets.UTF_8))).get("rules").get(0);
    }

    @Test
    void RLE_192_shorthand_cells_become_the_normal_forms_of_the_engine() {
        JsonNode rule = read("R1,\"RETAIL, SME\",>= 50000000 VND,18 to 65,!= VN,1.5,standard");

        assertThat(rule.get("when").get("segment").stringValue()).isEqualTo("in ['RETAIL', 'SME']");
        assertThat(rule.get("when").get("amount").stringValue()).isEqualTo(">= 50000000 VND");
        assertThat(rule.get("when").get("age").stringValue()).isEqualTo("between 18 and 65");
        assertThat(rule.get("when").get("country").stringValue()).isEqualTo("!= 'VN'");
        assertThat(rule.get("then").get("rate").stringValue()).isEqualTo("1.5");
        assertThat(rule.get("then").get("note").stringValue()).isEqualTo("'standard'");
    }

    @Test
    void RLE_192_a_quoted_text_keeps_its_commas_and_a_half_open_range_uses_brackets() {
        JsonNode rule = read("R1,\"\"\"Retail, small\"\"\",-,[18..65),null,2,\"a, b\"");

        assertThat(rule.get("when").get("segment").stringValue()).isEqualTo("== 'Retail, small'");
        assertThat(rule.get("when").get("age").stringValue()).isEqualTo("age >= 18 and age < 65");
        assertThat(rule.get("when").get("country").stringValue()).isEqualTo("is null");
        assertThat(rule.get("when").has("amount")).isFalse();
        assertThat(rule.get("then").get("rate").intValue()).isEqualTo(2);
        assertThat(rule.get("then").get("note").stringValue()).isEqualTo("'a, b'");
    }

    @Test
    void RLE_191_a_text_that_looks_like_shorthand_is_written_quoted_and_reads_back_the_same() {
        List<String> awkward = List.of("-", "null", "<5", "a, b", " padded", "=SUM(A1)", "x to y", "[x]");
        for (String text : awkward) {
            String canonical = BASE.replace("\"when\":{},\"then\":{\"rate\":\"1.5\",\"note\":\"'x'\"}",
                    "\"when\":{\"segment\":\"== '" + text + "'\"},\"then\":{\"rate\":\"1.5\",\"note\":\"'x'\"}");
            String file = CsvTable.write(canonical);
            JsonNode back = EngineJson.JSON.readTree(CsvTable.read(canonical, file.getBytes(StandardCharsets.UTF_8)))
                    .get("rules").get(0);

            assertThat(back.get("when").get("segment").stringValue()).as(text).isEqualTo("== '" + text + "'");
        }
    }
}
