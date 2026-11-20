package io.github.dhluong90.bpm.rules.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.github.dhluong90.bpm.rules.api.DecisionInput;
import io.github.dhluong90.bpm.rules.api.DecisionResult;
import io.github.dhluong90.bpm.rules.api.ImportRequest;
import io.github.dhluong90.bpm.rules.api.Money;
import io.github.dhluong90.bpm.rules.api.SourceFormat;
import io.github.dhluong90.bpm.rules.api.ValidationFailedException;
import io.github.dhluong90.bpm.rules.api.VersionService;
import io.github.dhluong90.bpm.rules.api.VersionStatus;
import io.github.dhluong90.bpm.rules.api.Violation;
import io.github.dhluong90.bpm.rules.api.WriteOptions;
import io.github.dhluong90.bpm.rules.internal.eval.CompiledRuleSet;
import io.github.dhluong90.bpm.rules.internal.expr.EvaluationLimits;
import io.github.dhluong90.bpm.rules.internal.expr.RegexLimits;
import io.github.dhluong90.bpm.rules.internal.read.RuleSetReader;
import io.github.dhluong90.bpm.rules.internal.store.StoredVersion;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * RLE-004: an unknown input, a type mismatch or code-like text in a cell is refused at import, by {@code validate}
 * and by {@code requestPublish}, naming the row, the column, a stable code and the character position; the version
 * stays a draft. Also the step budget of one decision (TR-SEC-01). Example: a card fee table of bank-a by tier,
 * card transactions and balance.
 */
class ExpressionValidationTest {

    static final String HEADER = """
            schemaVersion: 1
            ruleSet: fee-check
            hitPolicy: first
            inputs:
              - { name: tier,        type: string }
              - { name: creditScore, type: integer }
              - { name: balance,     type: money }
            outputs:
              - { name: fee, type: money }
            rules:
            """;

    static final String VALID_ROW = """
              - id: R1
                when: { balance: "balance + 100 VND > 0 VND" }
                then: { fee: "10000 VND" }
            """;

    final java.time.Clock clock = java.time.Clock.fixed(Instant.parse("2026-12-01T00:00:00Z"), java.time.ZoneOffset.UTC);
    final InMemoryStores stores = new InMemoryStores();
    final TestCaller caller = new TestCaller("bank-a", "lan");
    final RuleEngines engines = RuleEngines.create(
            new EngineContext(caller, caller, clock, stores, stores, EngineSettings.defaults()), stores, stores, stores);
    final VersionService versions = engines.versionService();

    {
        engines.cursorKeys().load();
    }

    static byte[] table(String rows) {
        return (HEADER + rows).getBytes(StandardCharsets.UTF_8);
    }

    static String row(String id, String condition, String fee) {
        return "  - id: " + id + "\n    when: { " + condition + " }\n    then: { fee: \"" + fee + "\" }\n";
    }

    Violation refusedAtImport(String rows) {
        ValidationFailedException e = catchThrowableOfType(ValidationFailedException.class,
                () -> versions.importRuleSet(ImportRequest.yaml(table(rows))));
        assertThat(e).as("refused at import").isNotNull();
        assertThat(stores.versions).as("a refused document stores nothing").isEmpty();
        return e.violations().getFirst();
    }

    // ---------------------------------------------------------------- unknown input

    @Test
    void RLE_004_unknown_input_is_refused_naming_the_row_and_the_input() {
        Violation v = refusedAtImport(row("R1", "creditScore: \"creditScoreX > 600\"", "0 VND"));

        assertThat(v.ruleId()).isEqualTo("R1");
        assertThat(v.column()).isEqualTo("creditScore");
        assertThat(v.code()).isEqualTo("UNKNOWN_INPUT");
        assertThat(v.position()).isZero();
        assertThat(v.message()).contains("Unknown input 'creditScoreX'");
    }

    @Test
    void RLE_004_validate_and_request_publish_refuse_a_stored_draft_that_no_longer_passes() {
        // Given a draft stored by an earlier release, before today's checks, whose R1 reads creditScoreX
        versions.importRuleSet(ImportRequest.yaml(table(VALID_ROW)));
        storeSourceAsIs(table(row("R1", "creditScore: \"creditScoreX > 600\"", "0 VND")));
        int historyBefore = stores.history.size();
        int eventsBefore = stores.outbox.size();

        // When the author validates it, and then asks for publication
        ValidationFailedException validated = catchThrowableOfType(ValidationFailedException.class,
                () -> versions.validate("fee-check", 1));
        ValidationFailedException requested = catchThrowableOfType(ValidationFailedException.class,
                () -> versions.requestPublish("fee-check", 1, WriteOptions.none()));

        // Then both name R1 and the unknown input, and the version stays a draft with nothing written
        for (ValidationFailedException e : List.of(validated, requested)) {
            assertThat(e.violations()).singleElement().satisfies(v -> {
                assertThat(v.ruleId()).isEqualTo("R1");
                assertThat(v.code()).isEqualTo("UNKNOWN_INPUT");
                assertThat(v.message()).contains("creditScoreX");
            });
        }
        assertThat(stores.versions).singleElement().extracting(StoredVersion::status).isEqualTo(VersionStatus.DRAFT);
        assertThat(stores.history).hasSize(historyBefore);
        assertThat(stores.outbox).hasSize(eventsBefore);
    }

    @Test
    void RLE_004_validate_passes_a_valid_draft_and_changes_nothing() {
        versions.importRuleSet(ImportRequest.yaml(table(VALID_ROW)));
        StoredVersion before = stores.versions.getFirst();

        versions.validate("fee-check", 1);

        assertThat(stores.versions).containsExactly(before);
        assertThat(versions.requestPublish("fee-check", 1, WriteOptions.none()).status())
                .isEqualTo(VersionStatus.PENDING_APPROVAL);
    }

    // ---------------------------------------------------------------- type mismatch

    @Test
    void RLE_004_money_plus_integer_is_a_type_mismatch_at_the_operator() {
        Violation v = refusedAtImport(row("R1", "balance: \"balance + 100 > 0\"", "0 VND"));

        assertThat(v.ruleId()).isEqualTo("R1");
        assertThat(v.column()).isEqualTo("balance");
        assertThat(v.code()).isEqualTo("TYPE_MISMATCH");
        assertThat(v.position()).as("the + of 'balance + 100 > 0'").isEqualTo(8);
        assertThat(v.message()).contains("+ is not defined for money and number")
                .contains("for example 100 VND");
    }

    @Test
    void RLE_004_a_stored_draft_with_a_type_mismatch_cannot_reach_pending_approval() {
        versions.importRuleSet(ImportRequest.yaml(table(VALID_ROW)));
        storeSourceAsIs(table(row("R1", "balance: \"balance + 100 > 0\"", "0 VND")));

        assertThatThrownBy(() -> versions.requestPublish("fee-check", 1, WriteOptions.none()))
                .isInstanceOfSatisfying(ValidationFailedException.class, e -> assertThat(e.violations())
                        .singleElement().extracting(Violation::ruleId, Violation::code)
                        .containsExactly("R1", "TYPE_MISMATCH"));
        assertThat(stores.versions.getFirst().status()).isEqualTo(VersionStatus.DRAFT);
    }

    @Test
    void RLE_004_type_mismatch_in_shorthand_is_placed_in_the_cell_as_written() {
        // "> 600 + tier" is read as "creditScore > 600 + tier"; the + is at position 6 of the cell
        Violation v = refusedAtImport(row("R1", "creditScore: \"> 600 + tier\"", "0 VND"));

        assertThat(v.code()).isEqualTo("TYPE_MISMATCH");
        assertThat(v.position()).isEqualTo(6);
        assertThat(v.message()).contains("+ is not defined for number and string").endsWith("at position 6");
    }

    @Test
    void RLE_004_output_computed_from_inputs_is_type_checked() {
        // money * money has no meaning; before RLE-004 it failed only when a customer's transaction ran it
        Violation v = refusedAtImport(row("R1", "tier: \"== 'GOLD'\"", "balance * balance"));

        assertThat(v.column()).isEqualTo("fee");
        assertThat(v.code()).isEqualTo("TYPE_MISMATCH");
        assertThat(v.position()).isEqualTo(8);
        assertThat(v.message()).contains("money is multiplied or divided by a number");
    }

    @Test
    void RLE_004_well_typed_money_arithmetic_is_accepted() {
        versions.importRuleSet(ImportRequest.yaml(table(row("R1", "balance: \"balance - 50000 VND >= 0 VND\"",
                "balance * 0.015"))));

        assertThat(stores.versions).hasSize(1);
    }

    // ---------------------------------------------------------------- forbidden construct

    @Test
    void RLE_004_TR_SEC_01_method_call_is_refused_as_a_forbidden_construct() {
        Violation v = refusedAtImport(row("R1", "tier: \"T(java.lang.Runtime).getRuntime()\"", "0 VND"));

        assertThat(v.ruleId()).isEqualTo("R1");
        assertThat(v.column()).isEqualTo("tier");
        assertThat(v.code()).isEqualTo("FORBIDDEN_CONSTRUCT");
        assertThat(v.position()).as("the first '.'").isEqualTo(6);
    }

    @Test
    void RLE_004_TR_SEC_01_call_of_an_unknown_function_is_refused_before_its_arguments() {
        Violation v = refusedAtImport(row("R1", "tier: \"exec('rm -rf /') == 'GOLD'\"", "0 VND"));

        assertThat(v.code()).isEqualTo("FORBIDDEN_CONSTRUCT");
        assertThat(v.position()).isZero();
        assertThat(v.message()).contains("Unknown function 'exec'");
    }

    // ---------------------------------------------------------------- the demo file: three mistakes, three rows

    @Test
    void RLE_004_three_mistakes_give_three_violations_in_row_order() {
        ValidationFailedException e = catchThrowableOfType(ValidationFailedException.class,
                () -> versions.importRuleSet(ImportRequest.yaml(table(
                        row("R1", "creditScore: \"creditScoreX > 600\"", "0 VND")
                                + row("R2", "balance: \"balance + 100 > 0\"", "10000 VND")
                                + row("R3", "tier: \"T(java.lang.Runtime).getRuntime()\"", "50000 VND")))));

        assertThat(e.violations()).extracting(Violation::ruleId, Violation::code).containsExactly(
                org.assertj.core.groups.Tuple.tuple("R1", "UNKNOWN_INPUT"),
                org.assertj.core.groups.Tuple.tuple("R2", "TYPE_MISMATCH"),
                org.assertj.core.groups.Tuple.tuple("R3", "FORBIDDEN_CONSTRUCT"));
        assertThat(stores.versions).isEmpty();
    }

    // ---------------------------------------------------------------- step budget

    @Test
    void TR_SEC_01_a_decision_past_its_step_budget_fails_the_same_way_every_time() {
        byte[] source = table(row("R1", "balance: \"balance + 1 VND + 1 VND + 1 VND > 0 VND\"", "10000 VND"));
        var model = RuleSetReader.read(SourceFormat.YAML, source).model();
        CompiledRuleSet tight = CompiledRuleSet.compile(model, EngineDefaults.OUTPUT_MAX_BYTES,
                EngineDefaults.STRING_MAX_LENGTH, RegexLimits.defaults(), new EvaluationLimits(5, 1000));
        DecisionInput input = DecisionInput.of(Map.of("tier", "GOLD", "creditScore", 700,
                "balance", Money.ofMinor("VND", 2_500_000)));

        for (int run = 0; run < 3; run++) {
            CompiledRuleSet.Evaluation evaluation = tight.evaluateWithReason(input);
            assertThat(evaluation.reason()).isEqualTo(EvaluationLimits.EVALUATION_BUDGET_EXCEEDED);
            assertThat(evaluation.outcome()).isInstanceOfSatisfying(DecisionResult.Failed.class, failed ->
                    assertThat(failed.detail()).isEqualTo("EVALUATION_BUDGET_EXCEEDED: the decision took more than 5"
                            + " evaluation steps"));
        }

        CompiledRuleSet roomy = CompiledRuleSet.compile(model, EngineDefaults.OUTPUT_MAX_BYTES,
                EngineDefaults.STRING_MAX_LENGTH, RegexLimits.defaults(), EvaluationLimits.defaults());
        assertThat(roomy.evaluateWithReason(input).reason()).isNull();
    }

    @Test
    void TR_SEC_01_evaluation_limits_may_be_lowered_but_never_switched_off() {
        assertThatThrownBy(() -> new EvaluationLimits(0, 1000)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EvaluationLimits(EvaluationLimits.STEP_BUDGET_MAX + 1, 1000))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EvaluationLimits(100, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EvaluationLimits(100, EvaluationLimits.TIMEOUT_MILLIS_MAX + 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** Replaces the stored source of version 1, as a draft written by an earlier release with fewer checks. */
    private void storeSourceAsIs(byte[] source) {
        StoredVersion v = stores.versions.getFirst();
        stores.versions.set(0, new StoredVersion(v.tenantId(), v.id(), v.ruleSetId(), v.ruleSet(), v.version(),
                v.status(), v.hitPolicy(), v.effectiveFrom(), v.canonicalJson(), v.canonicalHash(), v.sourceFormat(),
                new String(source, StandardCharsets.UTF_8), v.sourceHash(), v.lockVersion(), v.requestedBy(),
                v.requestedAt(), v.approvedBy(), v.approvedAt(), v.approvalComment(), v.createdBy(), v.createdAt(),
                v.retiredAt()));
    }
}
