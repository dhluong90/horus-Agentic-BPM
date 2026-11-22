package io.github.dhluong90.bpm.rules.internal.read;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.rules.api.SourceFormat;
import io.github.dhluong90.bpm.rules.api.ValidationFailedException;
import io.github.dhluong90.bpm.rules.internal.dmn.ruleset.DmnWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The schema version 1 format before the 1.0 freeze (#38): names that full DMN needs later are reserved and refused
 * by name with the stage that adds them (RLE-095), and the error mode is a member of the format that changes no
 * hash while only {@code strict} exists (RLE-096).
 */
class DmnReadyFormatTest {

    private static String cardFee() {
        try (InputStream in = DmnReadyFormatTest.class.getResourceAsStream("/rules/card-fee.yaml")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static ParsedRuleSet read(String yaml) {
        return RuleSetReader.read(SourceFormat.YAML, yaml.getBytes(StandardCharsets.UTF_8));
    }

    private static String with(String member) {
        return cardFee().replace("hitPolicy: first ", member + "\nhitPolicy: first ");
    }

    @ParameterizedTest(name = "hitPolicy {0}")
    @ValueSource(strings = {"any", "priority", "ruleOrder", "outputOrder"})
    void RLE_095_a_reserved_hit_policy_is_refused_by_name_with_stage_s1(String policy) {
        // Given card-fee.yaml with a DMN hit policy that schema version 1 reserves
        String yaml = cardFee().replace("hitPolicy: first ", "hitPolicy: " + policy + " ");

        // When it is read / Then it is refused naming the policy and the stage that adds it
        assertThatThrownBy(() -> read(yaml))
                .isInstanceOf(ValidationFailedException.class)
                .hasMessageContaining("hitPolicy " + policy + " is added in stage S1")
                .hasMessageContaining("first, unique and collect");
    }

    @Test
    void RLE_095_an_unknown_hit_policy_keeps_the_plain_refusal() {
        String yaml = cardFee().replace("hitPolicy: first ", "hitPolicy: cheapest ");

        assertThatThrownBy(() -> read(yaml))
                .isInstanceOf(ValidationFailedException.class)
                .hasMessageContaining("hitPolicy must be first, unique or collect")
                .hasMessageNotContaining("cheapest")
                .hasMessageNotContaining("stage");
    }

    @ParameterizedTest(name = "expressionLanguage {0}")
    @ValueSource(strings = {"feel", "FEEL", "https://www.omg.org/spec/DMN/20230324/FEEL/",
        "http://www.omg.org/spec/DMN/20180521/FEEL/"})
    void RLE_095_feel_as_expression_language_is_refused_by_name_with_stage_s1(String language) {
        String yaml = with("expressionLanguage: \"" + language + "\"");

        assertThatThrownBy(() -> read(yaml))
                .isInstanceOf(ValidationFailedException.class)
                .hasMessageContaining("expressionLanguage FEEL is added in stage S1")
                .hasMessageContaining(RuleSetReader.EXPR_V1);
    }

    @Test
    void RLE_095_another_expression_language_keeps_the_plain_refusal() {
        String yaml = with("expressionLanguage: \"javascript\"");

        assertThatThrownBy(() -> read(yaml))
                .isInstanceOf(ValidationFailedException.class)
                .hasMessageContaining("expressionLanguage must be " + RuleSetReader.EXPR_V1)
                .hasMessageNotContaining("stage");
    }

    @ParameterizedTest(name = "schemaVersion {0}")
    @ValueSource(ints = {2, 3})
    void RLE_095_a_later_schema_version_is_refused_by_name_and_never_read_as_version_1(int version) {
        String yaml = cardFee().replace("schemaVersion: 1", "schemaVersion: " + version);

        assertThatThrownBy(() -> read(yaml))
                .isInstanceOf(ValidationFailedException.class)
                .hasMessageContaining("schemaVersion " + version + " is written for a later engine release")
                .hasMessageContaining("reads schema version 1");
    }

    @ParameterizedTest(name = "schemaVersion {0}")
    @ValueSource(strings = {"0", "-1", "\"2\"", "1.5", "99999999999"})
    void RLE_095_a_schema_version_that_is_not_a_later_version_keeps_the_plain_refusal(String version) {
        String yaml = cardFee().replace("schemaVersion: 1", "schemaVersion: " + version);

        assertThatThrownBy(() -> read(yaml))
                .isInstanceOf(ValidationFailedException.class)
                .hasMessageContaining("schemaVersion must be 1");
    }

    @Test
    void RLE_095_error_mode_spec_is_refused_by_name_with_stage_s1() {
        assertThatThrownBy(() -> read(with("errorMode: spec")))
                .isInstanceOf(ValidationFailedException.class)
                .hasMessageContaining("errorMode spec is added in stage S1")
                .hasMessageContaining("errorMode strict");
    }

    @Test
    void RLE_096_error_mode_strict_is_accepted_and_keeps_the_canonical_json_and_hash() {
        // Given card-fee.yaml without errorMode, and the same document declaring errorMode: strict
        ParsedRuleSet without = read(cardFee());
        ParsedRuleSet strict = read(with("errorMode: strict"));

        // Then both print the same canonical JSON, without the member, and have one hash
        assertThat(strict.canonicalJson()).isEqualTo(without.canonicalJson()).doesNotContain("errorMode");
        assertThat(strict.canonicalHash()).isEqualTo(without.canonicalHash());
        assertThat(strict.model()).isEqualTo(without.model());
    }

    @Test
    void RLE_096_error_mode_strict_survives_a_dmn_round_trip_with_the_same_hash() {
        // Given card-fee.yaml declaring errorMode: strict, exported to DMN and imported again (RLE-045)
        ParsedRuleSet strict = read(with("errorMode: strict"));
        byte[] dmn = DmnWriter.write(strict.canonicalJson()).getBytes(StandardCharsets.UTF_8);

        // When the DMN file is read
        ParsedRuleSet imported = RuleSetReader.read(SourceFormat.DMN_XML, dmn);

        // Then it has the same canonical hash: an imported DMN table evaluates in strict mode (PD-125)
        assertThat(imported.canonicalHash()).isEqualTo(strict.canonicalHash());
    }

    @ParameterizedTest(name = "errorMode {0}")
    @CsvSource({"lenient", "STRICT", "''"})
    void RLE_096_another_error_mode_is_refused_naming_both_modes(String mode) {
        assertThatThrownBy(() -> read(with("errorMode: \"" + mode + "\"")))
                .isInstanceOf(ValidationFailedException.class)
                .hasMessageContaining("errorMode must be strict or spec");
    }

    @Test
    void RLE_096_error_mode_must_be_a_string() {
        assertThatThrownBy(() -> read(with("errorMode: true")))
                .isInstanceOf(ValidationFailedException.class)
                .hasMessageContaining("'errorMode' must be a string");
    }
}
