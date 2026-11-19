package io.github.dhluong90.bpm.rules.internal.read;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.rules.api.DecisionInput;
import io.github.dhluong90.bpm.rules.api.DecisionResult;
import io.github.dhluong90.bpm.rules.api.SourceFormat;
import io.github.dhluong90.bpm.rules.api.ValidationFailedException;
import io.github.dhluong90.bpm.rules.internal.eval.CompiledRuleSet;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Regression tests for the review probes of #1 on the reader and the compiler. */
class RuleSetReaderSecurityTest {

    static String base(String extraInputs, String rules) {
        return "schemaVersion: 1\nruleSet: t\nhitPolicy: first\ninputs:\n  - { name: tier, type: string }\n"
                + "  - { name: rate, type: decimal }\n  - { name: fee, type: money }\n  - { name: waiver, type: boolean }\n"
                + extraInputs + "outputs:\n  - { name: out, type: decimal }\n  - { name: m, type: money }\nrules:\n" + rules;
    }

    static ParsedRuleSet read(String yaml) {
        return RuleSetReader.read(SourceFormat.YAML, yaml.getBytes(StandardCharsets.UTF_8));
    }

    static CompiledRuleSet compile(String yaml) {
        return CompiledRuleSet.compile(read(yaml).model());
    }

    // Finding 6: what runs is what the checker read.

    @ParameterizedTest
    @ValueSource(strings = {"0.12345678901234567891", "12345678901234567891", "1e999999"})
    void RLE_020_a_number_the_canonical_form_would_change_is_refused(String number) {
        assertThatThrownBy(() -> read(base("", "  - id: R1\n    when: {}\n    then: { out: " + number + ", m: \"1 VND\" }\n")))
                .isInstanceOf(ValidationFailedException.class)
                .hasMessageContaining("/rules/0/then/out");
    }

    @Test
    void RLE_020_exact_numbers_keep_their_value_through_the_canonical_form() {
        ParsedRuleSet parsed = read(base("", "  - id: R1\n    when: {}\n    then: { out: 0.085, m: \"1 VND\" }\n"));

        DecisionResult.Outcome outcome = CompiledRuleSet.compile(RuleSetReader.readCanonical(parsed.canonicalJson()))
                .evaluate(DecisionInput.of(Map.of()));

        assertThat(((DecisionResult.Matched) outcome).outputs()).containsEntry("out", new BigDecimal("0.085"));
    }

    // Finding 7: anchors, aliases and tags.

    @ParameterizedTest
    @CsvSource(delimiter = '|', ignoreLeadingAndTrailingWhitespace = false, value = {
        "  - id: R1\\n    when: { tier: &c \"== 'A'\" }\\n    then: { out: 1, m: \"1 VND\" }\\n  - id: R2\\n    when: { tier: *c }\\n    then: { out: 2, m: \"1 VND\" }\\n|anchors",
        "  - id: R1\\n    when: { tier: !!str \"== 'A'\" }\\n    then: { out: 1, m: \"1 VND\" }\\n|tags",
        "  - id: R1\\n    when: {}\\n    then: { out: !foo 1, m: \"1 VND\" }\\n|tags"
    })
    void TR_SEC_01_yaml_anchors_aliases_and_tags_are_refused(String rules, String what) {
        assertThatThrownBy(() -> read(base("", rules.replace("\\n", "\n"))))
                .isInstanceOf(ValidationFailedException.class).hasMessageContaining("YAML " + what.strip());
    }

    @Test
    void TR_SEC_01_a_class_instantiating_tag_is_refused() {
        assertThatThrownBy(() -> read("!!javax.script.ScriptEngineManager [!!java.net.URLClassLoader [[!!java.net.URL [\"http://x\"]]]]\n"))
                .isInstanceOf(ValidationFailedException.class);
    }

    @Test
    void TR_SEC_01_an_alias_bomb_is_refused_without_expansion() {
        StringBuilder bomb = new StringBuilder("a: &a [\"x\",\"x\",\"x\",\"x\",\"x\",\"x\",\"x\",\"x\",\"x\",\"x\"]\n");
        for (int i = 0; i < 9; i++) {
            char p = (char) ('a' + i);
            char q = (char) ('b' + i);
            bomb.append(q).append(": &").append(q).append(" [*").append(p);
            for (int j = 0; j < 9; j++) {
                bomb.append(",*").append(p);
            }
            bomb.append("]\n");
        }
        assertThatThrownBy(() -> read(bomb.toString())).isInstanceOf(ValidationFailedException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"[", "{"})
    void TR_SEC_01_deep_nesting_is_refused(String bracket) {
        assertThatThrownBy(() -> read(bracket.repeat(5000))).isInstanceOf(ValidationFailedException.class);
        assertThatThrownBy(() -> RuleSetReader.read(SourceFormat.JSON, bracket.repeat(5000).getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(ValidationFailedException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {".nan", ".inf"})
    void TR_SEC_01_non_finite_numbers_are_refused(String value) {
        assertThatThrownBy(() -> read(base("", "  - id: R1\n    when: {}\n    then: { out: " + value + ", m: \"1 VND\" }\n")))
                .isInstanceOf(ValidationFailedException.class);
    }

    // Finding 8: conditions fail closed.

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "tier: \"> 5\"            | compares a string with a number",
        "rate: \"rate + 1\"       | does not produce true or false",
        "rate: \"rate\"           | does not produce true or false",
        "tier: \"in [1, 2]\"      | compares a string with a number",
        "fee: \">= 5\"            | compares a money with a number",
        "waiver: \"== 'yes'\"     | compares a boolean with a string"
    })
    void TR_DET_02_an_ill_typed_or_non_boolean_condition_is_refused_at_import(String cell, String message) {
        assertThatThrownBy(() -> compile(base("", "  - id: BLOCK\n    when: { " + cell.strip() + " }\n    then: { out: 1, m: \"1 VND\" }\n"
                + "  - id: ALLOW\n    when: {}\n    then: { out: 0, m: \"0 VND\" }\n")))
                .isInstanceOf(ValidationFailedException.class)
                .hasMessageContaining("Rule BLOCK").hasMessageContaining(message.strip());
    }

    @Test
    void TR_DET_02_well_typed_conditions_still_compile() {
        compile(base("", "  - id: R1\n    when: { tier: \"in ['A', 'B']\", fee: \">= 5 VND\", waiver: \"waiver\", "
                + "rate: \"rate * 2 > 1 and not(rate > 9)\" }\n    then: { out: 1, m: \"1 VND\" }\n"));
    }

    // Finding 9: mask is a boolean.

    @ParameterizedTest
    @ValueSource(strings = {"\"true\"", "1", "0", "2", "null", "yes"})
    void TR_SEC_05_mask_accepts_only_true_or_false(String value) {
        assertThatThrownBy(() -> read(base("  - { name: nid, type: string, mask: " + value + " }\n",
                "  - id: R1\n    when: {}\n    then: { out: 1, m: \"1 VND\" }\n")))
                .isInstanceOf(ValidationFailedException.class).hasMessageContaining("mask must be true or false");
    }

    @Test
    void TR_SEC_05_mask_true_and_false_are_read() {
        ParsedRuleSet parsed = read(base("  - { name: nid, type: string, mask: true }\n  - { name: other, type: string, mask: false }\n",
                "  - id: R1\n    when: {}\n    then: { out: 1, m: \"1 VND\" }\n"));

        assertThat(parsed.model().decision().table().inputs()).filteredOn(i -> i.name().equals("nid")).singleElement()
                .satisfies(i -> assertThat(i.masked()).isTrue());
        assertThat(parsed.model().decision().table().inputs()).filteredOn(i -> i.name().equals("other")).singleElement()
                .satisfies(i -> assertThat(i.masked()).isFalse());
    }

    // Finding 10: ASCII digits only.

    @Test
    void TR_DET_02_non_ascii_digits_are_refused() {
        assertThatThrownBy(() -> compile(base("", "  - id: R1\n    when: { rate: \"> 1٣\" }\n    then: { out: 1, m: \"1 VND\" }\n")))
                .isInstanceOf(ValidationFailedException.class).hasMessageContaining("digits 0 to 9");
    }
}
