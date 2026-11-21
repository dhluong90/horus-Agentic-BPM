package io.github.dhluong90.bpm.rules.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.rules.api.DraftAuthoringDisabledException;
import io.github.dhluong90.bpm.rules.api.DraftValidation;
import io.github.dhluong90.bpm.rules.api.ImportRequest;
import io.github.dhluong90.bpm.rules.api.InputInvalidException;
import io.github.dhluong90.bpm.rules.api.InvalidStateException;
import io.github.dhluong90.bpm.rules.api.PayloadTooLargeException;
import io.github.dhluong90.bpm.rules.api.PermissionDeniedException;
import io.github.dhluong90.bpm.rules.api.RuleSetNotFoundException;
import io.github.dhluong90.bpm.rules.api.RuleSetVersion;
import io.github.dhluong90.bpm.rules.api.ValidationFailedException;
import io.github.dhluong90.bpm.rules.api.VersionNotFoundException;
import io.github.dhluong90.bpm.rules.api.VersionService;
import io.github.dhluong90.bpm.rules.api.WriteOptions;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * {@code :validate} (RLE-090): the checks of a save on an edited table, with nothing stored. card-fee v1 is approved,
 * v2 is lan's draft; bank-b is another tenant.
 */
class ValidateDraftTest {

    static final Set<String> AUTHOR = Set.of("rules:read", "rules:author");
    static final Set<String> APPROVER = Set.of("rules:read", "rules:approve");

    final EditorApiTest.MovableClock clock = new EditorApiTest.MovableClock(Instant.parse("2026-12-01T00:00:00Z"));
    final InMemoryStores stores = new InMemoryStores();
    final TestCaller caller = new TestCaller("bank-a", "lan");
    final VersionService versions = engines(true).versionService();

    RuleEngines engines(boolean draftAuthoring) {
        RuleEngines engines = RuleEngines.create(new EngineContext(caller, caller, clock, stores, stores,
                EngineSettings.defaults().withDraftAuthoring(draftAuthoring)), stores, stores, stores);
        engines.cursorKeys().load();
        return engines;
    }

    RuleSetVersion lansDraftV2() {
        caller.become("bank-a", "lan", AUTHOR);
        versions.importRuleSet(ImportRequest.yaml(CardFee.yaml()));
        RuleSetVersion requested = versions.requestPublish("card-fee", 1, WriteOptions.none());
        caller.become("bank-a", "bob", APPROVER);
        versions.approve("card-fee", 1, new WriteOptions(null, requested.lockVersion(), null)
                .expectingContent(requested.canonicalHash()));
        caller.become("bank-a", "lan", AUTHOR);
        return versions.importVersion("card-fee", ImportRequest.yaml(CardFee.yaml()));
    }

    static ImportRequest yaml(String text) {
        return ImportRequest.yaml(text.getBytes(StandardCharsets.UTF_8));
    }

    static String cardFeeWith(String from, String to) {
        return new String(CardFee.yaml(), StandardCharsets.UTF_8).replace(from, to);
    }

    @Test
    void RLE_090_a_valid_table_is_valid_with_a_reading_per_rule_in_english_and_vietnamese_and_nothing_is_stored() {
        // Given lan's draft v2 and the rows written so far
        RuleSetVersion draft = lansDraftV2();
        int history = stores.history.size();
        int events = stores.outbox.size();
        int keys = stores.idempotency.size();
        int versionRows = stores.versions.size();

        // When she validates card-fee with R2 at 0 VND
        DraftValidation result = versions.validateDraft("card-fee", 2,
                yaml(cardFeeWith("then: { fee: \"10000 VND\" }", "then: { fee: \"0 VND\" }")));

        // Then it is valid, with R1, R2 and R3 read in both languages
        assertThat(result.valid()).isTrue();
        assertThat(result.problems()).isEmpty();
        assertThat(result.readingsTruncated()).isFalse();
        assertThat(result.readingsReason()).isNull();
        assertThat(result.readings()).extracting(DraftValidation.Reading::ruleId).containsExactly("R1", "R2", "R3");
        assertThat(result.readings().get(0).text().get("en"))
                .isEqualTo("Rule R1 applies when tier is Gold. Then fee is 0 VND.");
        assertThat(result.readings().get(1).text().get("vi"))
                .startsWith("Quy tắc R2 áp dụng khi tx count ít nhất là 3.");
        assertThat(result.readings().get(2).text().get("en")).startsWith("Rule R3 always applies; it has no condition.");
        // and no version, history, event or idempotency row was written; the draft is as it was
        assertThat(stores.history).hasSize(history);
        assertThat(stores.outbox).hasSize(events);
        assertThat(stores.idempotency).hasSize(keys);
        assertThat(stores.versions).hasSize(versionRows);
        RuleSetVersion after = versions.find("card-fee", 2, true).orElseThrow();
        assertThat(after.lockVersion()).isEqualTo(draft.lockVersion());
        assertThat(after.canonicalHash()).isEqualTo(draft.canonicalHash());
    }

    static ImportRequest cardFeeOfRows(int rows) {
        StringBuilder text = new StringBuilder("""
                schemaVersion: 1
                ruleSet: card-fee
                hitPolicy: first
                inputs:
                  - { name: tier, type: string }
                outputs:
                  - { name: fee, type: money }
                rules:
                """);
        for (int i = 1; i <= rows; i++) {
            text.append("  - { id: R").append(i).append(", when: { tier: \"== 'T").append(i)
                    .append("'\" }, then: { fee: \"10000 VND\" } }\n");
        }
        return yaml(text.toString());
    }

    @Test
    void RLE_090_a_valid_table_of_500_rows_still_has_its_readings() {
        lansDraftV2();
        DraftValidation result = versions.validateDraft("card-fee", 2, cardFeeOfRows(EngineDefaults.READINGS_MAX));
        assertThat(result.valid()).isTrue();
        assertThat(result.readings()).hasSize(EngineDefaults.READINGS_MAX);
        assertThat(result.readingsTruncated()).isFalse();
        assertThat(result.readingsReason()).isNull();
    }

    @Test
    void RLE_090_a_valid_draft_of_501_rows_is_valid_and_leaves_its_readings_out_with_a_reason() {
        lansDraftV2();
        int history = stores.history.size();
        DraftValidation result = versions.validateDraft("card-fee", 2, cardFeeOfRows(EngineDefaults.READINGS_MAX + 1));
        assertThat(result.valid()).isTrue();
        assertThat(result.problems()).isEmpty();
        assertThat(result.readings()).isEmpty();
        assertThat(result.readingsTruncated()).isTrue();
        assertThat(result.readingsReason()).isEqualTo("TABLE_TOO_LARGE");
        assertThat(stores.history).hasSize(history);
    }

    @Test
    void RLE_090_a_valid_draft_of_5000_rows_is_valid_and_leaves_its_readings_out_with_a_reason() {
        lansDraftV2();
        DraftValidation result = versions.validateDraft("card-fee", 2, cardFeeOfRows(EngineDefaults.RULES_MAX));
        assertThat(result.valid()).isTrue();
        assertThat(result.readings()).isEmpty();
        assertThat(result.readingsTruncated()).isTrue();
        assertThat(result.readingsReason()).isEqualTo("TABLE_TOO_LARGE");
    }

    @Test
    void RLE_090_a_cell_problem_is_placed_on_its_rule_and_column_with_a_code_and_both_languages() {
        // Given lan's draft v2
        lansDraftV2();
        int history = stores.history.size();

        // When R2 tests txCount against a name that is not an input
        DraftValidation result = versions.validateDraft("card-fee", 2,
                yaml(cardFeeWith("txCount: \">= 3\"", "txCount: \">= tierX\"")));

        // Then 200-style result: not valid, one problem on R2 / txCount, no readings
        assertThat(result.valid()).isFalse();
        assertThat(result.readings()).isEmpty();
        assertThat(result.problems()).singleElement().satisfies(p -> {
            assertThat(p.ruleId()).isEqualTo("R2");
            assertThat(p.column()).isEqualTo("txCount");
            assertThat(p.code()).isEqualTo("UNKNOWN_INPUT");
            assertThat(p.message().get("en")).isEqualTo("The cell uses a name that is not a declared input.");
            assertThat(p.message().get("vi")).isEqualTo("Ô này dùng một tên không phải là đầu vào đã khai báo.");
            assertThat(p.message().values()).noneMatch(m -> m.contains("tierX"));
        });
        assertThat(stores.history).hasSize(history);
    }

    @Test
    void RLE_090_the_verdict_is_the_one_a_save_gives() {
        // Given a draft and a table with a bad cell
        RuleSetVersion draft = lansDraftV2();
        ImportRequest bad = yaml(cardFeeWith("tier: \"== 'GOLD'\"", "tier: \"== 'GOLD\""));

        // When it is validated and then saved
        DraftValidation result = versions.validateDraft("card-fee", 2, bad);
        assertThatThrownBy(() -> versions.replaceDraft("card-fee", 2, bad,
                new WriteOptions("k-900", draft.lockVersion(), null)))
                .isInstanceOfSatisfying(ValidationFailedException.class, refused -> {
                    // Then both name the same cells
                    assertThat(result.valid()).isFalse();
                    assertThat(result.problems().stream().map(p -> p.ruleId() + "/" + p.column()).toList())
                            .isEqualTo(refused.violations().stream().map(v -> v.ruleId() + "/" + v.column()).toList());
                });
    }

    @Test
    void RLE_090_a_version_that_is_not_a_draft_is_invalid_state_and_a_missing_one_is_not_found() {
        // Given v1 approved
        lansDraftV2();

        // When / Then
        assertThatThrownBy(() -> versions.validateDraft("card-fee", 1, ImportRequest.yaml(CardFee.yaml())))
                .isInstanceOf(InvalidStateException.class).hasMessageContaining("validate");
        assertThatThrownBy(() -> versions.validateDraft("card-fee", 9, ImportRequest.yaml(CardFee.yaml())))
                .isInstanceOf(VersionNotFoundException.class);
        assertThatThrownBy(() -> versions.validateDraft("nope", 1, ImportRequest.yaml(CardFee.yaml())))
                .isInstanceOf(RuleSetNotFoundException.class);
    }

    @Test
    void RLE_090_it_needs_rules_author_stays_in_the_tenant_and_checks_the_name_and_the_size() {
        // Given lan's draft
        lansDraftV2();

        // When dan only reads, a bank-b author asks for card-fee, the document names another set, or is over the limit
        caller.become("bank-a", "dan", Set.of("rules:read"));
        assertThatThrownBy(() -> versions.validateDraft("card-fee", 2, ImportRequest.yaml(CardFee.yaml())))
                .isInstanceOf(PermissionDeniedException.class);
        caller.become("bank-b", "eva", AUTHOR);
        assertThatThrownBy(() -> versions.validateDraft("card-fee", 2, ImportRequest.yaml(CardFee.yaml())))
                .isInstanceOf(RuleSetNotFoundException.class);
        caller.become("bank-a", "lan", AUTHOR);
        assertThatThrownBy(() -> versions.validateDraft("card-fee", 2,
                yaml(cardFeeWith("ruleSet: card-fee", "ruleSet: fx-margin"))))
                .isInstanceOf(InputInvalidException.class);
        assertThatThrownBy(() -> versions.validateDraft("card-fee", 2,
                new ImportRequest(io.github.dhluong90.bpm.rules.api.SourceFormat.YAML, new byte[1_048_577], null)))
                .isInstanceOf(PayloadTooLargeException.class);
    }

    @Test
    void RLE_090_it_is_refused_when_cockpit_drafts_are_off() {
        lansDraftV2();
        VersionService off = engines(false).versionService();

        assertThatThrownBy(() -> off.validateDraft("card-fee", 2, ImportRequest.yaml(CardFee.yaml())))
                .isInstanceOf(DraftAuthoringDisabledException.class);
    }

    @Test
    void RLE_090_a_reading_of_a_masked_input_never_shows_its_test() {
        // Given a draft whose nationalId declares mask: true and is tested against a number
        caller.become("bank-a", "lan", AUTHOR);
        versions.importRuleSet(yaml(KYC.replace("079123456789", "000000000000")));
        String edited = KYC;

        // When the edited table is validated
        DraftValidation result = versions.validateDraft("kyc-routing", 1, yaml(edited));

        // Then the reading names the input as masked and the value is nowhere in the result
        assertThat(result.valid()).isTrue();
        assertThat(result.readings()).singleElement().satisfies(r -> {
            assertThat(r.text().get("en")).startsWith("Rule R1 applies when national id is masked; its test is not shown.");
            assertThat(r.text().get("vi")).contains("national id").doesNotContain("079123456789");
        });
        assertThat(result.toString()).doesNotContain("079123456789");
    }

    @Test
    void RLE_099_a_syntax_error_names_the_expected_token_the_position_and_an_example_in_both_languages() {
        // Given lan's draft v2
        lansDraftV2();
        int history = stores.history.size();

        // When R2 writes a condition with an opening parenthesis that is never closed
        DraftValidation open = versions.validateDraft("card-fee", 2,
                yaml(cardFeeWith("txCount: \">= 3\"", "txCount: \">= (3 + 1\"")));

        // Then the problem is a SYNTAX_ERROR at the end of the cell, expecting a closing parenthesis, with an example
        assertThat(open.valid()).isFalse();
        assertThat(open.problems()).singleElement().satisfies(p -> {
            assertThat(p.ruleId()).isEqualTo("R2");
            assertThat(p.column()).isEqualTo("txCount");
            assertThat(p.code()).isEqualTo("SYNTAX_ERROR");
            assertThat(p.position()).isEqualTo(9);
            assertThat(p.expected().get("en")).isEqualTo("A closing parenthesis ) to match the opening one.");
            assertThat(p.expected().get("vi")).isEqualTo("Dấu đóng ngoặc ) khớp với dấu mở ngoặc.");
            assertThat(p.example()).isEqualTo("(balance + 100) > 0");
            assertThat(p.message().get("en")).isEqualTo("The cell is not a valid expression.");
        });

        // and a single = is answered with the equality operator, at its position
        DraftValidation equals = versions.validateDraft("card-fee", 2,
                yaml(cardFeeWith("tier: \"== 'GOLD'\"", "tier: \"= 'GOLD'\"")));
        assertThat(equals.problems()).singleElement().satisfies(p -> {
            assertThat(p.code()).isEqualTo("SYNTAX_ERROR");
            assertThat(p.position()).isEqualTo(0);
            assertThat(p.expected().get("en")).isEqualTo("Two equals signs == to compare for equality.");
            assertThat(p.example()).isEqualTo("tier == 'GOLD'");
        });

        // and a problem that is not a syntax error carries no expected token and no example
        DraftValidation unknown = versions.validateDraft("card-fee", 2,
                yaml(cardFeeWith("txCount: \">= 3\"", "txCount: \">= tierX\"")));
        assertThat(unknown.problems()).singleElement().satisfies(p -> {
            assertThat(p.expected()).isEmpty();
            assertThat(p.example()).isNull();
        });
        assertThat(stores.history).hasSize(history);
    }

    @Test
    void RLE_099_a_syntax_error_in_a_masked_cell_never_echoes_its_value() {
        // Given a draft whose nationalId declares mask: true
        caller.become("bank-a", "lan", AUTHOR);
        versions.importRuleSet(yaml(KYC));

        // When the cell is edited with an unterminated text that starts with the masked number
        DraftValidation result = versions.validateDraft("kyc-routing", 1,
                yaml(KYC.replace("== '079123456789'", "== '079123456789")));

        // Then the problem expects a closing quote and the number is nowhere in the answer
        assertThat(result.problems()).singleElement().satisfies(p -> {
            assertThat(p.code()).isEqualTo("SYNTAX_ERROR");
            assertThat(p.expected().get("en")).isEqualTo("A closing single quote to end the text.");
        });
        assertThat(result.toString()).doesNotContain("079123456789");
    }

    @Test
    void RLE_099_every_syntax_kind_has_an_expected_token_and_an_example_in_every_shipped_language() {
        CatalogueExplainer catalogue = CatalogueExplainer.bundled();
        for (io.github.dhluong90.bpm.expr.internal.SyntaxHint hint
                : io.github.dhluong90.bpm.expr.internal.SyntaxHint.values()) {
            DraftValidation.Problem problem = catalogue.problem(new io.github.dhluong90.bpm.rules.api.Violation("R1",
                    "tier", "x", "SYNTAX_ERROR", 0, hint.name()));
            assertThat(problem.expected()).as(hint.name()).containsKeys("en", "vi");
            assertThat(problem.expected().values()).as(hint.name()).allMatch(text -> !text.isBlank());
            assertThat(problem.example()).as(hint.name()).isNotBlank();
        }
    }

    static final String KYC = """
            schemaVersion: 1
            ruleSet: kyc-routing
            hitPolicy: first
            inputs:
              - { name: nationalId, type: string, mask: true }
            outputs:
              - { name: route, type: string }
            rules:
              - id: R1
                when: { nationalId: "== '079123456789'" }
                then: { route: "'MANUAL'" }
            """;
}
