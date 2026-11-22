package io.github.dhluong90.bpm.rules.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import io.github.dhluong90.bpm.rules.api.ImportRequest;
import io.github.dhluong90.bpm.rules.api.PermissionDeniedException;
import io.github.dhluong90.bpm.rules.api.RuleSetNotFoundException;
import io.github.dhluong90.bpm.rules.api.RuleSetVersion;
import io.github.dhluong90.bpm.rules.api.SourceFormat;
import io.github.dhluong90.bpm.rules.api.ValidationFailedException;
import io.github.dhluong90.bpm.rules.api.VersionNotFoundException;
import io.github.dhluong90.bpm.rules.api.VersionService;
import io.github.dhluong90.bpm.rules.api.Violation;
import io.github.dhluong90.bpm.rules.internal.json.EngineJson;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The spreadsheet round trip of the engine (PD-107, PD-142, issue #39): a version exported as CSV, edited in Excel
 * and imported back as the next draft, with every cell checked and no cell quoted in an error. Users of
 * {@code bank-a}: lan authors, dan only reads.
 */
class SpreadsheetRoundTripTest {

    static final Set<String> AUTHOR = Set.of("rules:read", "rules:author");
    static final Set<String> READER = Set.of("rules:read");
    static final String BOM = "﻿";

    final EditorApiTest.MovableClock clock = new EditorApiTest.MovableClock(Instant.parse("2027-01-10T00:00:00Z"));
    final InMemoryStores stores = new InMemoryStores();
    final TestCaller caller = new TestCaller("bank-a", "lan");
    final UnmaskedPersonalDataTest.Policy policy = new UnmaskedPersonalDataTest.Policy();
    final RuleEngines engines = RuleEngines.create(new EngineContext(caller, caller, clock, stores, stores,
            EngineSettings.defaults(), policy), stores, stores, stores);
    final VersionService versions = engines.versionService();

    /** card-fee: Gold and Platinum pay nothing, Silver with 50,000,000 VND or more pays 50,000 VND, others 100,000. */
    static final String CARD_FEE = """
            schemaVersion: 1
            ruleSet: card-fee
            hitPolicy: first
            inputs:
              - { name: tier, type: string, mask: false }
              - { name: balance, type: money, mask: false }
              - { name: opened, type: date, mask: false }
            outputs:
              - { name: fee, type: money }
              - { name: waived, type: boolean }
            rules:
              - { id: R1, description: "Gold and Platinum are free", when: { tier: "in ['GOLD', 'PLATINUM']" },
                  then: { fee: "0 VND", waived: true } }
              - { id: R2, when: { tier: "== 'SILVER'", balance: ">= 50000000 VND" }, then: { fee: "50000 VND", waived: false } }
              - { id: R3, when: { opened: "between date('2025-01-01') and date('2025-12-31')" }, then: { fee: "20000 VND", waived: false } }
              - { id: R4, when: {}, then: { fee: "100000 VND", waived: false } }
            """;

    static final String HEADER = "Rule,tier,balance,opened,fee,waived,Description\r\n";

    RuleSetVersion importBase() {
        caller.become("bank-a", "lan", AUTHOR);
        return versions.importRuleSet(ImportRequest.yaml(CARD_FEE.getBytes(StandardCharsets.UTF_8)));
    }

    String export(int version) {
        return versions.export("card-fee", version, SourceFormat.CSV);
    }

    RuleSetVersion importCsv(String file) {
        return importCsv(file, null);
    }

    RuleSetVersion importCsv(String file, String key) {
        return versions.importCsv("card-fee", 1, new ImportRequest(SourceFormat.CSV, file.getBytes(StandardCharsets.UTF_8), key));
    }

    ValidationFailedException refused(String file) {
        try {
            importCsv(file);
        } catch (ValidationFailedException e) {
            return e;
        }
        throw new AssertionError("the file was accepted");
    }

    @Test
    void RLE_191_a_version_exports_as_utf8_csv_with_a_byte_order_mark_crlf_and_the_shorthand() {
        // Given card-fee v1 with rules R1 to R4, and dan who holds rules:read
        importBase();
        caller.become("bank-a", "dan", READER);

        // When v1 is exported as CSV
        String file = export(1);

        // Then the file starts with the byte order mark, the header names inputs and outputs, and cells use the shorthand
        assertThat(file).isEqualTo(BOM + HEADER
                + "R1,\"GOLD, PLATINUM\",-,-,0 VND,true,Gold and Platinum are free\r\n"
                + "R2,SILVER,>= 50000000 VND,-,50000 VND,false,\r\n"
                + "R3,-,-,2025-01-01 to 2025-12-31,20000 VND,false,\r\n"
                + "R4,-,-,-,100000 VND,false,\r\n");
    }

    @Test
    void RLE_191_a_version_with_a_grouped_condition_has_no_spreadsheet_form_and_names_the_rule() {
        importBase();
        String grouped = CARD_FEE.replace("R4, when: {}", "R4, when: { any: [ { tier: \"== 'GOLD'\" }, { balance: \"> 1 VND\" } ] }");
        caller.become("bank-a", "lan", AUTHOR);
        versions.importVersion("card-fee", ImportRequest.yaml(grouped.getBytes(StandardCharsets.UTF_8)));

        assertThatThrownBy(() -> export(2)).isInstanceOfSatisfying(ValidationFailedException.class, e ->
                assertThat(e.violations()).extracting(Violation::ruleId, Violation::code)
                        .containsExactly(tuple("R4", "CSV_NO_TABLE_FORM")));
    }

    @Test
    void RLE_191_exporting_needs_the_read_permission_and_stays_in_the_callers_tenant() {
        importBase();
        caller.become("bank-a", "nobody", Set.of());
        assertThatThrownBy(() -> export(1)).isInstanceOf(PermissionDeniedException.class);

        caller.become("bank-b", "lan", READER);
        assertThatThrownBy(() -> export(1)).isInstanceOf(RuleSetNotFoundException.class);
    }

    @Test
    void RLE_192_a_file_that_changes_one_fee_becomes_the_next_draft_with_the_same_columns_and_hit_policy() {
        // Given v1 and a file that changes R2's fee from 50000 VND to 30000 VND
        RuleSetVersion v1 = importBase();
        String edited = export(1).replace("R2,SILVER,>= 50000000 VND,-,50000 VND", "R2,SILVER,>= 50000000 VND,-,30000 VND");

        // When lan imports it based on v1
        RuleSetVersion v2 = importCsv(edited);

        // Then v2 is a draft with R2 at 30000 VND, and everything outside the rows is v1's
        assertThat(v2.version()).isEqualTo(2);
        assertThat(v2.status().name()).isEqualTo("DRAFT");
        JsonNode doc = EngineJson.JSON.readTree(v2.canonicalDocument());
        JsonNode base = EngineJson.JSON.readTree(v1.canonicalDocument());
        assertThat(doc.get("inputs")).isEqualTo(base.get("inputs"));
        assertThat(doc.get("outputs")).isEqualTo(base.get("outputs"));
        assertThat(doc.get("hitPolicy")).isEqualTo(base.get("hitPolicy"));
        assertThat(doc.get("rules").get(1).get("then").get("fee").stringValue()).isEqualTo("30000 VND");
        assertThat(doc.get("rules").get(0).get("description").stringValue()).isEqualTo("Gold and Platinum are free");
        assertThat(versions.diff("card-fee", 2, 1).rows()).filteredOn(r -> r.change() != null
                && !"UNCHANGED".equals(r.change().name())).hasSize(1);
    }

    @Test
    void RLE_192_an_unedited_export_imports_back_with_the_same_canonical_hash() {
        RuleSetVersion v1 = importBase();

        RuleSetVersion v2 = importCsv(export(1));

        assertThat(v2.canonicalHash()).isEqualTo(v1.canonicalHash());
    }

    @Test
    void RLE_192_a_repeated_key_returns_the_original_draft_and_a_new_key_makes_the_next_one() {
        importBase();
        String file = export(1).replace("0 VND,true", "0 VND,false");

        RuleSetVersion first = importCsv(file, "k-39-1");
        RuleSetVersion again = importCsv(file, "k-39-1");
        RuleSetVersion other = importCsv(file, "k-39-2");

        assertThat(again).isEqualTo(first);
        assertThat(first.version()).isEqualTo(2);
        assertThat(other.version()).isEqualTo(3);
        assertThatThrownBy(() -> importCsv(file.replace("20000 VND", "21000 VND"), "k-39-1"))
                .isInstanceOf(io.github.dhluong90.bpm.rules.api.IdempotencyKeyReuseException.class);
    }

    @Test
    void RLE_192_a_semicolon_file_without_a_byte_order_mark_is_read_and_a_blank_rule_gets_the_next_free_id() {
        importBase();
        String file = "Rule;tier;balance;opened;fee;waived\r\n"
                + "R1;GOLD;-;-;0 VND;true\r\n"
                + ";SILVER;>= 50000000 VND;-;30000 VND;false\r\n"
                + "R2;-;-;-;100000 VND;false\r\n";

        RuleSetVersion v2 = importCsv(file);

        JsonNode rules = EngineJson.JSON.readTree(v2.canonicalDocument()).get("rules");
        assertThat(rules).extracting(r -> r.get("id").stringValue()).containsExactly("R1", "R3", "R2");
        assertThat(rules.get(1).get("when").get("balance").stringValue()).isEqualTo(">= 50000000 VND");
    }

    @Test
    void RLE_192_the_file_needs_the_author_permission_a_base_in_the_callers_tenant_and_an_existing_base() {
        importBase();
        String file = export(1);

        caller.become("bank-a", "dan", READER);
        assertThatThrownBy(() -> importCsv(file)).isInstanceOf(PermissionDeniedException.class);
        caller.become("bank-b", "lan", AUTHOR);
        assertThatThrownBy(() -> importCsv(file)).isInstanceOf(RuleSetNotFoundException.class);
        caller.become("bank-a", "lan", AUTHOR);
        assertThatThrownBy(() -> versions.importCsv("card-fee", 9, new ImportRequest(SourceFormat.CSV,
                file.getBytes(StandardCharsets.UTF_8), null))).isInstanceOf(VersionNotFoundException.class);
    }

    @Test
    void RLE_193_an_amount_without_a_currency_is_refused_with_its_rule_and_column_and_nothing_is_saved() {
        // Given R2's balance cell reads ">= 50000000"
        importBase();
        String file = export(1).replace(">= 50000000 VND", ">= 50000000");

        ValidationFailedException e = refused(file);

        assertThat(e.violations()).extracting(Violation::ruleId, Violation::column, Violation::code)
                .containsExactly(tuple("R2", "balance", "CSV_CELL_NO_CURRENCY"));
        assertThat(versions.find("card-fee", 2, false)).isEmpty();
    }

    @Test
    void RLE_193_excel_damage_is_refused_and_never_read_as_another_value() {
        // Given a scientific number, thousands separators, a date written 20/11/2026, text in a number column
        importBase();
        String base = export(1);

        assertThat(refused(base.replace(">= 50000000 VND", ">= 5E+07 VND")).violations()).extracting(Violation::code)
                .containsExactly("CSV_CELL_SCIENTIFIC");
        assertThat(refused(base.replace(">= 50000000 VND", "\">= 50,000,000 VND\"")).violations())
                .extracting(Violation::ruleId, Violation::column, Violation::code)
                .containsExactly(tuple("R2", "balance", "CSV_CELL_SEPARATORS"));
        assertThat(refused(base.replace(">= 50000000 VND", ">= 50.000.000 VND")).violations()).extracting(Violation::code)
                .containsExactly("CSV_CELL_SEPARATORS");
        assertThat(refused(base.replace("2025-01-01 to 2025-12-31", "20/11/2026")).violations())
                .extracting(Violation::ruleId, Violation::column, Violation::code)
                .containsExactly(tuple("R3", "opened", "CSV_CELL_DATE"));
        assertThat(refused(base.replace(">= 50000000 VND", ">= about fifty million VND")).violations())
                .extracting(Violation::code).containsExactly("CSV_CELL_NOT_A_NUMBER");
        assertThat(refused(base.replace("50000 VND", "")).violations())
                .extracting(Violation::ruleId, Violation::column, Violation::code)
                .containsExactly(tuple("R2", "fee", "CSV_CELL_EMPTY"));
    }

    @Test
    void RLE_193_no_problem_quotes_a_cell_so_a_masked_value_never_leaves_in_an_error() {
        // Given an input nationalId that is masked, and a rule that compares it with a number written as text
        caller.become("bank-a", "lan", AUTHOR);
        versions.importRuleSet(ImportRequest.yaml("""
                schemaVersion: 1
                ruleSet: card-fee
                hitPolicy: first
                inputs:
                  - { name: nationalId, type: integer, mask: true }
                outputs:
                  - { name: fee, type: money }
                rules:
                  - { id: R1, when: { nationalId: "== 123456789012" }, then: { fee: "0 VND" } }
                  - { id: R2, when: {}, then: { fee: "10000 VND" } }
                """.getBytes(StandardCharsets.UTF_8)));

        // When a cell holds the identity number with a leading letter
        ValidationFailedException e = refused("Rule,nationalId,fee\r\nR1,\"CCCD 079123456789\",0 VND\r\nR2,-,10000 VND\r\n");

        // Then the problem names the rule and column but holds no part of the cell
        assertThat(e.violations()).hasSize(1);
        assertThat(e.violations().getFirst().column()).isEqualTo("nationalId");
        assertThat(e.getMessage() + e.violations().getFirst().message()).doesNotContain("079123456789");
    }

    @Test
    void RLE_194_a_file_cannot_add_remove_rename_or_reorder_a_column() {
        importBase();
        String base = export(1);

        assertThat(refused(base.replace("balance", "segment")).violations()).extracting(Violation::column, Violation::code)
                .containsExactlyInAnyOrder(tuple("segment", "CSV_COLUMN_UNKNOWN"), tuple("balance", "CSV_COLUMN_MISSING"));
        assertThat(refused(base.replace(",waived", "")).violations()).extracting(Violation::column, Violation::code)
                .contains(tuple("waived", "CSV_COLUMN_MISSING"));
        assertThat(refused(base.replace("Rule,tier,balance", "Rule,balance,tier")).violations())
                .extracting(Violation::code).contains("CSV_COLUMN_ORDER");
    }

    @Test
    void RLE_194_a_duplicate_rule_id_a_short_row_a_bad_encoding_and_an_empty_file_are_refused() {
        importBase();
        String base = export(1);

        assertThat(refused(base.replace("R2,SILVER", "R1,SILVER")).violations())
                .extracting(Violation::ruleId, Violation::code).containsExactly(tuple("R1", "CSV_RULE_DUPLICATE"));
        assertThat(refused(base.replace("R4,-,-,-,100000 VND,false,", "R4,-,-")).violations())
                .extracting(Violation::ruleId, Violation::code).containsExactly(tuple("R4", "CSV_ROW_SHAPE"));
        assertThatThrownBy(() -> versions.importCsv("card-fee", 1, new ImportRequest(SourceFormat.CSV,
                new byte[] {'R', 'u', 'l', 'e', ',', (byte) 0xE9}, null))).isInstanceOfSatisfying(
                ValidationFailedException.class, e -> assertThat(e.violations()).extracting(Violation::code)
                        .containsExactly("CSV_ENCODING"));
        assertThat(refused("").violations()).extracting(Violation::code).containsExactly("CSV_EMPTY");
    }

    @Test
    void RLE_194_a_change_the_engine_checks_on_import_is_refused_by_the_same_checks_as_a_document() {
        // Given a Gold tier row whose fee is a boolean instead of an amount
        importBase();
        String file = export(1).replace("R1,\"GOLD, PLATINUM\",-,-,0 VND,true", "R1,\"GOLD, PLATINUM\",-,-,true,true");

        ValidationFailedException e = refused(file);

        assertThat(e.violations()).extracting(Violation::ruleId, Violation::column).contains(tuple("R1", "fee"));
        assertThat(e.violations()).allSatisfy(v -> assertThat(v.position()).isNull());
        List<String> codes = e.violations().stream().map(Violation::code).toList();
        assertThat(codes).doesNotContain((String) null);
    }
}
