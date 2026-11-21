package io.github.dhluong90.bpm.rules.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.expr.api.Money;
import io.github.dhluong90.bpm.rules.api.DecisionInput;
import io.github.dhluong90.bpm.rules.api.DecisionResult;
import io.github.dhluong90.bpm.rules.api.ImportRequest;
import io.github.dhluong90.bpm.rules.api.InputInvalidException;
import io.github.dhluong90.bpm.rules.api.MaskingKey;
import io.github.dhluong90.bpm.rules.api.PermissionDeniedException;
import io.github.dhluong90.bpm.rules.api.ReadingsTooLargeException;
import io.github.dhluong90.bpm.rules.api.RuleReadings;
import io.github.dhluong90.bpm.rules.api.RuleReadings.Reading;
import io.github.dhluong90.bpm.rules.api.RuleSetNotFoundException;
import io.github.dhluong90.bpm.rules.api.SimulationResult;
import io.github.dhluong90.bpm.rules.api.VersionDiff;
import io.github.dhluong90.bpm.rules.api.VersionNotFoundException;
import io.github.dhluong90.bpm.rules.api.VersionStatus;
import io.github.dhluong90.bpm.rules.api.WriteOptions;
import io.github.dhluong90.bpm.rules.spi.MaskingPolicy;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Plain-language readings of rule rows (RLE-091 to RLE-093): every row of a version, the rows of a diff in both
 * versions, and a simulation result, each in English and Vietnamese as a full sentence and a short form. The rule set
 * is the account tier table of a retail bank: balance in VND, segment, and a masked national id.
 */
class RuleReadingsTest {

    static final Instant CLOCK = Instant.parse("2026-12-01T00:00:00Z");

    static final String R4 = """
              - id: R4
                when: { segment: "== 'MASS'" }
                then: { tier: "'BASIC'", fee: "50000 VND" }
                explain:
                  en: "Mass customers: {input.segment}."
            """;

    static final String TIERS_V1 = """
            schemaVersion: 1
            ruleSet: account-tier
            hitPolicy: first
            inputs:
              - { name: balance, type: money, label: Balance }
              - { name: segment, type: string }
              - { name: nationalId, type: string, mask: true }
            outputs:
              - { name: tier, type: string, label: Tier }
              - { name: fee, type: money }
            rules:
              - id: R1
                when: { balance: ">= 50000000 VND", segment: "== 'VIP'" }
                then: { tier: "'GOLD'", fee: "0 VND" }
              - id: R2
                when: { balance: "between 10000000 VND and 49999999 VND", nationalId: "== '079123456789'" }
                then: { tier: "'SILVER'", fee: "10000 VND" }
              - id: R3
                when: { segment: "in ['STAFF', 'PARTNER']" }
                then: { tier: "'SILVER'", fee: "0 VND" }
                explain:
                  en: "Staff and partners hold {output.tier} and pay {output.fee}."
            """
            + R4 + """
              - id: R5
                when: {}
                then: { tier: "'BASIC'", fee: "50000 VND" }
            """;

    static final String TIERS_V2 = TIERS_V1.replace(R4, "").replace(">= 50000000 VND", ">= 60000000 VND");

    final InMemoryStores stores = new InMemoryStores();
    final TestCaller caller = new TestCaller("bank-a", "lan");
    final RuleEngines engines = engines();

    RuleEngines engines() {
        RuleEngines created = RuleEngines.create(new EngineContext(caller, caller, Clock.fixed(CLOCK, ZoneOffset.UTC),
                stores, stores, EngineSettings.defaults(), MaskingPolicy.withKey(MaskingKey.of(MaskedInputsTest.KEY_A))),
                stores, stores, stores, DecisionQuerySettings.defaults(), CatalogueExplainer.bundled());
        created.cursorKeys().load();
        return created;
    }

    void publish(String ruleSet, String yaml, int version) {
        caller.become("bank-a", "lan");
        byte[] source = yaml.getBytes(StandardCharsets.UTF_8);
        if (version == 1) {
            engines.versionService().importRuleSet(ImportRequest.yaml(source));
        } else {
            engines.versionService().importVersion(ruleSet, ImportRequest.yaml(source));
        }
        long lock = engines.versionService().requestPublish(ruleSet, version, WriteOptions.none()).lockVersion();
        caller.become("bank-a", "minh");
        engines.versionService().approve(ruleSet, version, new WriteOptions(null, lock, null));
        caller.become("bank-a", "dan", Set.of("rules:read"));
    }

    @Test
    void RLE_091_every_row_reads_in_english_and_vietnamese_as_a_full_sentence_and_a_short_form() {
        // Given account-tier v1 published and a reader with rules:read
        publish("account-tier", TIERS_V1, 1);

        // When every row is read
        RuleReadings.RuleSetReadings readings = engines.versionService().readings("account-tier", 1);

        // Then there is one entry per row in table order, written in both languages
        assertThat(readings.languages()).containsExactly("en", "vi");
        assertThat(readings.rows()).extracting(RuleReadings.Row::ruleId).containsExactly("R1", "R2", "R3", "R4", "R5");
        assertThat(readings.rows()).extracting(RuleReadings.Row::position).containsExactly(1, 2, 3, 4, 5);
        Reading r1en = readings.rows().get(0).readings().get("en");
        assertThat(r1en.full()).isEqualTo("Rule R1 applies when Balance is at least 50,000,000 VND and segment is VIP."
                + " Then fee is 0 VND; Tier is GOLD.");
        assertThat(r1en.shortForm()).isEqualTo("Balance ≥ 50,000,000 VND, segment = VIP → fee = 0 VND; Tier = GOLD");
        assertThat(r1en.source()).isEqualTo("GENERATED");
        Reading r1vi = readings.rows().get(0).readings().get("vi");
        assertThat(r1vi.full()).isEqualTo("Quy tắc R1 áp dụng khi Balance ít nhất là 50.000.000 VND và segment là VIP."
                + " Khi đó fee là 0 VND; Tier là GOLD.");
        assertThat(r1vi.shortForm()).isEqualTo("Balance ≥ 50.000.000 VND, segment = VIP → fee = 0 VND; Tier = GOLD");
        // and a list, a row with no condition, and the other tests read plainly
        assertThat(readings.rows().get(2).readings().get("vi").shortForm()).startsWith("Segment thuộc STAFF, PARTNER");
        assertThat(readings.rows().get(4).readings().get("en").full())
                .startsWith("Rule R5 always applies; it has no condition.");
        assertThat(readings.rows().get(4).readings().get("en").shortForm()).startsWith("Always");
    }

    @Test
    void RLE_091_a_masked_input_is_named_and_its_test_is_never_written() {
        // Given R2 tests the masked nationalId against 079123456789
        publish("account-tier", TIERS_V1, 1);

        // When the rows are read
        RuleReadings.RuleSetReadings readings = engines.versionService().readings("account-tier", 1);

        // Then the reading says the input is masked and the id appears nowhere
        Reading r2en = readings.rows().get(1).readings().get("en");
        assertThat(r2en.full()).contains("national id is masked; its test is not shown")
                .contains("Balance is between 10,000,000 VND and 49,999,999 VND");
        assertThat(r2en.shortForm()).contains("national id (masked)")
                .doesNotContain("079123456789");
        assertThat(readings.toString()).doesNotContain("079123456789");
    }

    @Test
    void RLE_091_the_authors_sentence_is_the_full_sentence_when_the_table_can_fill_it() {
        // Given R3 has an English sentence that needs only outputs and R4 one that needs an input value
        publish("account-tier", TIERS_V1, 1);

        // When the rows are read
        RuleReadings.RuleSetReadings readings = engines.versionService().readings("account-tier", 1);

        // Then R3 uses the author's English sentence and still reads generated in Vietnamese
        Reading r3en = readings.rows().get(2).readings().get("en");
        assertThat(r3en.full()).isEqualTo("Staff and partners hold SILVER and pay 0 VND.");
        assertThat(r3en.source()).isEqualTo("TEMPLATE");
        assertThat(readings.rows().get(2).readings().get("vi").source()).isEqualTo("GENERATED");
        // and R4 needs the value of an input, which a table does not hold, so the generated sentence is used
        Reading r4en = readings.rows().get(3).readings().get("en");
        assertThat(r4en.source()).isEqualTo("GENERATED");
        assertThat(r4en.full()).startsWith("Rule R4 applies when segment is MASS.");
    }

    @Test
    void RLE_091_the_same_request_gives_the_same_readings() {
        publish("account-tier", TIERS_V1, 1);

        assertThat(engines.versionService().readings("account-tier", 1))
                .isEqualTo(engines.versionService().readings("account-tier", 1));
    }

    @Test
    void RLE_091_it_needs_rules_read_stays_in_the_tenant_and_names_what_is_missing() {
        publish("account-tier", TIERS_V1, 1);

        caller.become("bank-a", "teller", Set.of("rules:evaluate"));
        assertThatThrownBy(() -> engines.versionService().readings("account-tier", 1))
                .isInstanceOf(PermissionDeniedException.class);
        caller.become("bank-b", "eva", Set.of("rules:read"));
        assertThatThrownBy(() -> engines.versionService().readings("account-tier", 1))
                .isInstanceOf(RuleSetNotFoundException.class);
        caller.become("bank-a", "dan", Set.of("rules:read"));
        assertThatThrownBy(() -> engines.versionService().readings("account-tier", 7))
                .isInstanceOf(VersionNotFoundException.class);
    }

    @Test
    void RLE_091_a_version_with_more_rows_than_one_request_reads_is_refused_not_cut_short() {
        // Given a version of 501 rows, one more than a request reads
        StringBuilder rows = new StringBuilder();
        for (int i = 1; i <= EngineDefaults.READINGS_MAX + 1; i++) {
            rows.append("  - { id: R").append(i).append(", when: { segment: \"== 'S").append(i)
                    .append("'\" }, then: { tier: \"'BASIC'\" } }\n");
        }
        publish("big-table", """
                schemaVersion: 1
                ruleSet: big-table
                hitPolicy: first
                inputs:
                  - { name: segment, type: string }
                outputs:
                  - { name: tier, type: string }
                rules:
                """ + rows, 1);

        // When it is read, Then the refusal says the limit and the count
        assertThatThrownBy(() -> engines.versionService().readings("big-table", 1))
                .isInstanceOf(ReadingsTooLargeException.class)
                .hasMessageContaining("at most 500 rows").hasMessageContaining("covers 501")
                .extracting("code").isEqualTo("READINGS_TOO_LARGE");
    }

    @Test
    void RLE_092_a_diff_reads_each_changed_row_in_both_versions() {
        // Given v1 and v2, which raises R1 to 60,000,000 VND and drops R4
        publish("account-tier", TIERS_V1, 1);
        publish("account-tier", TIERS_V2, 2);

        // When the readings of the diff are read
        RuleReadings.ReadingsDiff diff = engines.versionService().readingsDiff("account-tier", 2, 1);

        // Then R1 is changed with the old reading and the new one, R4 is removed with only the old
        assertThat(diff.rows()).extracting(RuleReadings.ChangedRow::ruleId).containsExactly("R1", "R4");
        RuleReadings.ChangedRow r1 = diff.rows().get(0);
        assertThat(r1.change()).isEqualTo(VersionDiff.Change.CHANGED);
        assertThat(r1.readingChanged()).isTrue();
        assertThat(r1.previous().get("en").full()).contains("Balance is at least 50,000,000 VND");
        assertThat(r1.current().get("en").full()).contains("Balance is at least 60,000,000 VND");
        assertThat(r1.current().get("vi").shortForm()).startsWith("Balance ≥ 60.000.000 VND");
        RuleReadings.ChangedRow r4 = diff.rows().get(1);
        assertThat(r4.change()).isEqualTo(VersionDiff.Change.REMOVED);
        assertThat(r4.current()).isEmpty();
        assertThat(r4.previous().get("en").shortForm()).startsWith("Segment = MASS");
        assertThat(diff.unchangedCount()).isEqualTo(3);
        // and a diff of a version with itself is refused as the diff is
        assertThatThrownBy(() -> engines.versionService().readingsDiff("account-tier", 2, 2))
                .isInstanceOf(InputInvalidException.class);
    }

    @Test
    void RLE_092_a_change_that_leaves_the_reading_alone_says_so() {
        // Given v2 differs from v1 only in a description of R5
        publish("account-tier", TIERS_V1, 1);
        publish("account-tier", TIERS_V1.replace("  - id: R5\n", "  - id: R5\n    description: fallback tier\n"), 2);

        // When the diff is read
        RuleReadings.ReadingsDiff diff = engines.versionService().readingsDiff("account-tier", 2, 1);

        // Then R5 is listed, and its reading is marked unchanged so a screen strikes nothing
        assertThat(diff.rows()).singleElement().satisfies(row -> {
            assertThat(row.ruleId()).isEqualTo("R5");
            assertThat(row.readingChanged()).isFalse();
        });
    }

    @Test
    void RLE_093_a_simulation_result_reads_each_side_in_the_version_that_decided_it_and_never_shows_the_inputs() {
        // Given v1 in force and v2 as a draft that raises the VIP threshold
        publish("account-tier", TIERS_V1, 1);
        caller.become("bank-a", "lan");
        engines.versionService().importVersion("account-tier", ImportRequest.yaml(TIERS_V2.getBytes(StandardCharsets.UTF_8)));
        caller.become("bank-a", "analyst", Set.of("rules:simulate", "rules:read"));
        SimulationResult result = engines.ruleEngine().simulate("account-tier", 2, List.of(
                DecisionInput.of(Map.of("balance", Money.ofMinor("VND", 55_000_000), "segment", "VIP",
                        "nationalId", "079123456789")),
                DecisionInput.of(Map.of("balance", Money.ofMinor("VND", 1_000_000), "segment", "MASS",
                        "nationalId", "079123456789"))));

        // When it is read
        RuleReadings.SimulationReadings readings = engines.versionService().readSimulation(result);

        // Then sample 0 changed: the baseline matched R1, the simulated version fell to R5
        assertThat(readings.baselineVersion()).isEqualTo(1);
        assertThat(readings.samples()).hasSize(2);
        RuleReadings.SampleReading first = readings.samples().get(0);
        assertThat(first.changed()).isTrue();
        assertThat(first.baseline().kind()).isEqualTo("MATCHED");
        assertThat(first.baseline().ruleIds()).containsExactly("R1");
        assertThat(first.baseline().readings().get("en").full())
                .isEqualTo("Rule R1 applies when Balance is at least 50,000,000 VND and segment is VIP."
                        + " Result: Tier is GOLD; fee is 0 VND.");
        assertThat(first.simulated().ruleIds()).containsExactly("R5");
        assertThat(first.simulated().readings().get("vi").shortForm()).isEqualTo("R5: Tier = BASIC; fee = 50.000 VND");
        // and no value of any sample is in the readings
        assertThat(readings.toString()).doesNotContain("079123456789").doesNotContain("55.000.000")
                .doesNotContain("55,000,000");
    }

    @Test
    void RLE_093_no_match_and_failure_read_as_sentences_and_a_result_with_no_baseline_reads_alone() {
        // Given a simulation result with a no-match, a failure and no baseline
        publish("account-tier", TIERS_V1, 1);
        SimulationResult result = new SimulationResult("account-tier", 1, VersionStatus.PUBLISHED, null, CLOCK, List.of(
                new SimulationResult.Sample(0, new DecisionResult.NoMatch(), null, true),
                new SimulationResult.Sample(1, new DecisionResult.Failed("EVALUATION_ERROR", "bad", List.of("R1")),
                        null, true)));

        // When it is read
        RuleReadings.SimulationReadings readings = engines.versionService().readSimulation(result);

        // Then each reads in both languages and the baseline is absent
        assertThat(readings.samples().get(0).baseline()).isNull();
        assertThat(readings.samples().get(0).simulated().readings().get("en").full())
                .isEqualTo("No rule of account-tier version 1 matched. No output was produced.");
        assertThat(readings.samples().get(0).simulated().readings().get("vi").shortForm())
                .isEqualTo("Không quy tắc nào khớp");
        assertThat(readings.samples().get(1).simulated().readings().get("en").shortForm())
                .isEqualTo("Failed: EVALUATION_ERROR");
        assertThat(readings.samples().get(1).simulated().kind()).isEqualTo("FAILED");
    }

    @Test
    void RLE_093_a_result_with_more_samples_than_one_request_reads_is_refused() {
        publish("account-tier", TIERS_V1, 1);
        List<SimulationResult.Sample> samples = java.util.stream.IntStream.range(0, EngineDefaults.READINGS_MAX + 1)
                .mapToObj(i -> new SimulationResult.Sample(i, new DecisionResult.NoMatch(), null, true)).toList();

        assertThatThrownBy(() -> engines.versionService().readSimulation(new SimulationResult("account-tier", 1,
                VersionStatus.PUBLISHED, null, CLOCK, samples)))
                .isInstanceOf(ReadingsTooLargeException.class).hasMessageContaining("samples");
    }
}
