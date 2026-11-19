package io.github.dhluong90.bpm.rules.internal.read;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.rules.api.SourceFormat;
import io.github.dhluong90.bpm.rules.api.ValidationFailedException;
import io.github.dhluong90.bpm.rules.internal.json.Hashes;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Failure paths and exactness of the YAML import (RLE-020): what the reader refuses, and that what it
 * accepts keeps its meaning. Every refusal must be a {@link ValidationFailedException} with a detail
 * a rule author can act on, never another runtime exception.
 */
class RuleSetReaderAcceptanceTest {

    static final String HEADER = "schemaVersion: 1\nruleSet: probe\nhitPolicy: first\n";
    static final String INPUTS = "inputs:\n  - { name: tier, type: string }\n";
    static final String OUTPUTS = "outputs:\n  - { name: fee, type: money }\n";
    static final String RULES = "rules:\n  - { id: R1, when: {}, then: { fee: \"0 VND\" } }\n";

    static ParsedRuleSet read(String yaml) {
        return RuleSetReader.read(SourceFormat.YAML, yaml.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void RLE_020_minimal_document_is_accepted() {
        // Given / When
        ParsedRuleSet parsed = read(HEADER + INPUTS + OUTPUTS + RULES);

        // Then
        assertThat(parsed.model().name()).isEqualTo("probe");
        assertThat(parsed.canonicalHash()).isEqualTo(Hashes.sha256(parsed.canonicalJson()));
    }

    // "[{index}] {0}": the empty document would give a blank name, which JUnit 5 (the Spring Boot 3.5 leg) refuses.
    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {
        "schemaVersion: 1\nruleSet: probe\n  hitPolicy: first\n",                 // bad indentation
        "schemaVersion: 1\nruleSet: [probe\n",                                     // unclosed flow sequence
        "schemaVersion: 1\nruleSet: probe\nruleSet: other\n",                      // duplicate key
        "\tschemaVersion: 1\n",                                                    // tab indentation
        "- just\n- a list\n",                                                      // not a mapping
        "",                                                                        // empty document
        "just a scalar\n"
    })
    void RLE_020_malformed_or_non_mapping_yaml_is_refused_as_validation_failed(String yaml) {
        assertThatThrownBy(() -> read(yaml)).isInstanceOf(ValidationFailedException.class);
    }

    @Test
    void RLE_020_unknown_root_property_is_refused() {
        assertThatThrownBy(() -> read(HEADER + "tenantId: tenant-b\n" + INPUTS + OUTPUTS + RULES))
                .isInstanceOf(ValidationFailedException.class)
                .hasMessageContaining("tenantId");
    }

    /**
     * rule-set.v1.schema.json declares {@code additionalProperties: false} on input, output and rule.
     * A typo such as {@code maks: true} must not import silently: it would turn masking off.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
        "inputs:\n  - { name: tier, type: string, maks: true }\n" + OUTPUTS + RULES,
        INPUTS + "outputs:\n  - { name: fee, type: money, defualt: \"1 VND\" }\n" + RULES,
        INPUTS + OUTPUTS + "rules:\n  - { id: R1, when: {}, then: { fee: \"0 VND\" }, priority: 1 }\n"
    })
    void RLE_020_unknown_property_in_an_input_output_or_rule_is_refused(String body) {
        assertThatThrownBy(() -> read(HEADER + body))
                .isInstanceOf(ValidationFailedException.class)
                .hasMessageContaining("Unknown property");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
        INPUTS + OUTPUTS + "rules: []\n",
        INPUTS + "outputs: []\n" + RULES,
        INPUTS + OUTPUTS,
        INPUTS + RULES,
        OUTPUTS + RULES
    })
    void RLE_020_empty_or_missing_table_parts_are_refused(String body) {
        assertThatThrownBy(() -> read(HEADER + body)).isInstanceOf(ValidationFailedException.class);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
        "schemaVersion: \"1\"\nruleSet: probe\nhitPolicy: first\n" + INPUTS + OUTPUTS + RULES,
        "schemaVersion: 2\nruleSet: probe\nhitPolicy: first\n" + INPUTS + OUTPUTS + RULES,
        "schemaVersion: 1\nruleSet: Probe_Set\nhitPolicy: first\n" + INPUTS + OUTPUTS + RULES,
        "schemaVersion: 1\nruleSet: probe\nhitPolicy: 5\n" + INPUTS + OUTPUTS + RULES,
        "schemaVersion: 1\nruleSet: probe\nhitPolicy: any\n" + INPUTS + OUTPUTS + RULES,
        HEADER + "inputs:\n  - { name: tier, type: money2 }\n" + OUTPUTS + RULES,
        HEADER + "inputs:\n  - { name: tier, type: string, mask: \"yes\" }\n" + OUTPUTS + RULES,
        HEADER + "inputs:\n  - { name: 1tier, type: string }\n" + OUTPUTS + RULES,
        HEADER + "inputs:\n  - { name: tier, type: string }\n  - { name: tier, type: string }\n" + OUTPUTS + RULES,
        HEADER + INPUTS + OUTPUTS + "rules:\n  - { id: R1, when: { tier: 5 }, then: { fee: \"0 VND\" } }\n",
        HEADER + INPUTS + OUTPUTS + "rules:\n  - { id: R1, when: { tier: \"\" }, then: { fee: \"0 VND\" } }\n",
        HEADER + INPUTS + OUTPUTS + "rules:\n  - { id: R1, when: { nope: \"== 1\" }, then: { fee: \"0 VND\" } }\n",
        HEADER + INPUTS + OUTPUTS + "rules:\n  - { id: R1, when: {}, then: { nope: \"0 VND\" } }\n",
        HEADER + INPUTS + OUTPUTS + "rules:\n  - { id: R1, when: {}, then: { fee: [1] } }\n",
        HEADER + INPUTS + OUTPUTS + "rules:\n  - { id: R1, when: {}, then: {} }\n  - { id: R1, when: {}, then: {} }\n",
        HEADER + "moneyRounding: HALF_DOWN\n" + INPUTS + OUTPUTS + RULES,
        HEADER + "timeZone: Mars/Olympus\n" + INPUTS + OUTPUTS + RULES,
        HEADER + "effectiveFrom: 2026-12-01\n" + INPUTS + OUTPUTS + RULES
    })
    void RLE_020_a_value_of_the_wrong_type_or_shape_is_refused_as_validation_failed(String yaml) {
        // As import does: read, then compile. Cell-level problems (an unknown input or output, a cell of the wrong
        // JSON type) are reported by the compiler on their cell, so they never hide later cells (RLE-041).
        assertThatThrownBy(() -> io.github.dhluong90.bpm.rules.internal.eval.CompiledRuleSet.compile(read(yaml).model()))
                .isInstanceOf(ValidationFailedException.class);
    }

    /**
     * The engine evaluates the canonical JSON, not the source. A number in the source must reach the
     * evaluator with the value the author wrote, or be refused: it must never be rounded through
     * {@code double} on the way.
     */
    @Test
    void RLE_020_numeric_literals_keep_their_exact_value_in_the_canonical_form() {
        // Given 2^53 + 1 and a rate with 21 significant digits, both exact decimals
        String yaml = HEADER + INPUTS
                + "outputs:\n  - { name: limit, type: integer }\n  - { name: rate, type: decimal }\n"
                + "rules:\n  - { id: R1, when: {}, then: { limit: 9007199254740993, rate: 0.123456789012345678901 } }\n";

        // When
        ParsedRuleSet parsed;
        try {
            parsed = read(yaml);
        } catch (ValidationFailedException refused) {
            return; // refusing a number that RFC 8785 cannot hold exactly is also correct
        }
        ParsedRuleSet reread = RuleSetReader.read(SourceFormat.JSON,
                parsed.canonicalJson().getBytes(StandardCharsets.UTF_8));

        // Then the value evaluated is the value written
        List<Object> thens = reread.model().decision().table().rules().get(0).then().values().stream()
                .map(cell -> ((io.github.dhluong90.bpm.rules.internal.model.Cell.Literal) cell).value()).toList();
        assertThat(thens).containsExactly(new BigDecimal("9007199254740993"), new BigDecimal("0.123456789012345678901"));
    }

    @Test
    void RLE_020_source_with_bom_crlf_and_vietnamese_comments_has_the_card_fee_canonical_hash() {
        // Given the card-fee file saved by a Windows editor: CRLF and a Vietnamese comment; a BOM is refused
        // (ADR-0026 decision 5), so the stored source is exactly the bytes source_hash was taken over
        String unix = new String(resource("/rules/card-fee.yaml"), StandardCharsets.UTF_8);
        byte[] windows = ("# Phí thẻ hằng tháng\r\n" + unix.replace("\n", "\r\n")).getBytes(StandardCharsets.UTF_8);
        byte[] withBom = ("﻿" + unix).getBytes(StandardCharsets.UTF_8);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> RuleSetReader.read(SourceFormat.YAML, withBom))
                .hasMessageContaining("byte order mark");

        // When
        ParsedRuleSet fromWindows = RuleSetReader.read(SourceFormat.YAML, windows);
        ParsedRuleSet fromUnix = RuleSetReader.read(SourceFormat.YAML, unix.getBytes(StandardCharsets.UTF_8));

        // Then only the source differs; the canonical meaning is the same
        assertThat(fromWindows.canonicalJson()).isEqualTo(fromUnix.canonicalJson());
        assertThat(fromWindows.canonicalHash()).isEqualTo(fromUnix.canonicalHash());
    }

    @Test
    void RLE_024_v1_rule_set_round_trips_through_dmn_model() {
        // Given card-fee.yaml, every cell already in normal form, and the RFC 8785 hash of that document
        String expectedHash = io.github.dhluong90.bpm.rules.internal.json.Hashes.sha256(
                io.github.dhluong90.bpm.rules.internal.service.CardFee.CANONICAL);

        // When the v1 reader builds the model and the canonical writer prints it back
        ParsedRuleSet parsed = RuleSetReader.read(SourceFormat.YAML, resource("/rules/card-fee.yaml"));

        // Then one Definitions card-fee holding one Decision card-fee with a FIRST table, 2 inputs, 1 output, R1 to R3
        io.github.dhluong90.bpm.rules.internal.model.Definitions model = parsed.model();
        assertThat(model.name()).isEqualTo("card-fee");
        assertThat(model.decisions()).hasSize(1);
        assertThat(model.decision().id()).isEqualTo("card-fee");
        io.github.dhluong90.bpm.rules.internal.model.DecisionTable table = model.decision().table();
        assertThat(table.hitPolicy()).isEqualTo(io.github.dhluong90.bpm.rules.internal.model.HitPolicy.FIRST);
        assertThat(table.inputs()).extracting(io.github.dhluong90.bpm.rules.internal.model.InputClause::name)
                .containsExactly("tier", "txCount");
        assertThat(table.outputs()).extracting(io.github.dhluong90.bpm.rules.internal.model.OutputClause::name).containsExactly("fee");
        assertThat(table.rules()).extracting(io.github.dhluong90.bpm.rules.internal.model.Rule::id).containsExactly("R1", "R2", "R3");
        // and the printed canonical JSON is byte for byte the RFC 8785 document, with that hash
        assertThat(parsed.canonicalJson()).isEqualTo(io.github.dhluong90.bpm.rules.internal.service.CardFee.CANONICAL);
        assertThat(parsed.canonicalHash()).isEqualTo(expectedHash);
    }

    /** #233: effectiveFrom stays on the model but is never printed, so it is outside the behaviour hash. */
    @Test
    void RLE_020_effective_from_is_kept_on_the_model_and_left_out_of_the_canonical_form_and_hash() {
        String unix = new String(resource("/rules/card-fee.yaml"), StandardCharsets.UTF_8);
        byte[] dated = unix.replace("hitPolicy: first", "effectiveFrom: \"2026-11-01T00:00:00Z\"\nhitPolicy: first")
                .getBytes(StandardCharsets.UTF_8);
        byte[] datedLater = unix.replace("hitPolicy: first", "effectiveFrom: \"2027-01-01T07:00:00+07:00\"\nhitPolicy: first")
                .getBytes(StandardCharsets.UTF_8);

        ParsedRuleSet plain = RuleSetReader.read(SourceFormat.YAML, unix.getBytes(StandardCharsets.UTF_8));
        ParsedRuleSet first = RuleSetReader.read(SourceFormat.YAML, dated);
        ParsedRuleSet second = RuleSetReader.read(SourceFormat.YAML, datedLater);

        assertThat(first.model().effectiveFrom()).isEqualTo(java.time.Instant.parse("2026-11-01T00:00:00Z"));
        assertThat(second.model().effectiveFrom()).isEqualTo(java.time.Instant.parse("2027-01-01T00:00:00Z"));
        assertThat(first.canonicalJson()).doesNotContain("effectiveFrom")
                .isEqualTo(second.canonicalJson()).isEqualTo(plain.canonicalJson())
                .isEqualTo(io.github.dhluong90.bpm.rules.internal.service.CardFee.CANONICAL);
        assertThat(first.canonicalHash()).isEqualTo(second.canonicalHash()).isEqualTo(plain.canonicalHash());
        // and the stored canonical form reads back without a date: the date lives on the version row only
        assertThat(RuleSetReader.readCanonical(first.canonicalJson()).effectiveFrom()).isNull();
    }

    static byte[] resource(String path) {
        try (InputStream in = RuleSetReaderAcceptanceTest.class.getResourceAsStream(path)) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
