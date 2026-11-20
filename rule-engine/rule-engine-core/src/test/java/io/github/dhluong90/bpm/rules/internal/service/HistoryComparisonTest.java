package io.github.dhluong90.bpm.rules.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.expr.api.Money;
import io.github.dhluong90.bpm.rules.api.ComparisonRequest;
import io.github.dhluong90.bpm.rules.api.ComparisonResult;
import io.github.dhluong90.bpm.rules.api.EvaluationErrorException;
import io.github.dhluong90.bpm.rules.api.HistoryTooLargeException;
import io.github.dhluong90.bpm.rules.api.ImportRequest;
import io.github.dhluong90.bpm.rules.api.PermissionDeniedException;
import io.github.dhluong90.bpm.rules.api.QueryInvalidException;
import io.github.dhluong90.bpm.rules.api.RuleEngine;
import io.github.dhluong90.bpm.rules.api.RuleSetNotFoundException;
import io.github.dhluong90.bpm.rules.api.VersionNotFoundException;
import io.github.dhluong90.bpm.rules.api.VersionService;
import io.github.dhluong90.bpm.rules.api.WriteOptions;
import io.github.dhluong90.bpm.rules.internal.json.EngineValues;
import io.github.dhluong90.bpm.rules.internal.json.Ulid;
import io.github.dhluong90.bpm.rules.internal.store.DecisionRecord;
import io.github.dhluong90.bpm.expr.internal.RegexLimits;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * What-if comparison of two versions over a past period (RLE-075 to RLE-077, PD-124), through the public engine on the
 * in-memory stores with a fixed clock. At bank-a, lan authors, minh approves and thu runs the comparison.
 */
class HistoryComparisonTest {

    static final Instant JAN_01 = Instant.parse("2027-01-01T00:00:00Z");
    static final Instant FEB_01 = Instant.parse("2027-02-01T00:00:00Z");
    static final Instant FEB_10 = Instant.parse("2027-02-10T00:00:00Z");
    static final Set<String> AUTHOR = Set.of("rules:read", "rules:author");
    static final Set<String> APPROVER = Set.of("rules:read", "rules:approve");
    static final Set<String> COMMITTEE = Set.of("rules:read", "rules:simulate", "rules:log:read");
    static final String FINGERPRINT = "hmac:k1:" + "9f2c41d07a5be3c8".repeat(4);

    final InMemoryStores stores = new InMemoryStores();
    final TestCaller caller = new TestCaller("bank-a", "lan");

    RuleEngines engines(EngineSettings settings) {
        RuleEngines engines = RuleEngines.create(new EngineContext(caller, caller, Clock.fixed(FEB_10, ZoneOffset.UTC),
                stores, stores, settings), stores, stores, stores);
        engines.cursorKeys().load();
        return engines;
    }

    /** lan imports and requests v1, minh approves it; lan then imports each further document as the next draft. */
    void versions(RuleEngines engines, String tenant, byte[]... documents) {
        VersionService versions = engines.versionService();
        caller.become(tenant, "lan", AUTHOR);
        for (int i = 0; i < documents.length; i++) {
            if (i == 0) {
                versions.importRuleSet(ImportRequest.yaml(documents[i]));
            } else {
                versions.importVersion(name(documents[i]), ImportRequest.yaml(documents[i]));
            }
        }
        String ruleSet = name(documents[0]);
        versions.requestPublish(ruleSet, 1, WriteOptions.none());
        caller.become(tenant, "minh", APPROVER);
        versions.approve(ruleSet, 1, new WriteOptions(null, 1L, null));
    }

    static String name(byte[] document) {
        String text = new String(document, StandardCharsets.UTF_8);
        return text.lines().filter(line -> line.startsWith("ruleSet:")).findFirst().orElseThrow()
                .substring("ruleSet:".length()).strip();
    }

    static byte[] cardFee(String r3Fee) {
        return new String(CardFee.yaml(), StandardCharsets.UTF_8)
                .replace("then: { fee: \"50000 VND\" }", "then: { fee: \"" + r3Fee + "\" }")
                .getBytes(StandardCharsets.UTF_8);
    }

    /** A logged decision at {@code at}, with its logged inputs and clock; the rest of the row is not read. */
    DecisionRecord logged(String tenant, String ruleSet, Instant at, String inputsJson) {
        return new DecisionRecord(tenant, Ulid.next(at), at, ruleSet, 1, "FIRST", "OK", null, inputsJson, "{}", "[]",
                "teller-01", null, at, 31);
    }

    /** January 2027 of card-fee at bank-a: 2,000 GOLD (R1), 3,000 R2 and 5,000 R3 decisions, one every 4 minutes. */
    void januaryOfCardFee(int total) {
        for (int i = 0; i < total; i++) {
            Instant at = JAN_01.plus(Duration.ofMinutes(4L * i));
            String inputs = switch (i % 10) {
                case 0, 1 -> "{\"tier\":\"GOLD\",\"txCount\":5}";
                case 2, 3, 4 -> "{\"tier\":\"SILVER\",\"txCount\":5}";
                default -> "{\"tier\":\"SILVER\",\"txCount\":1}";
            };
            stores.append(logged("bank-a", "card-fee", at, inputs));
        }
    }

    ComparisonRequest january() {
        return ComparisonRequest.of(1, 2, JAN_01, FEB_01);
    }

    long writes() {
        return stores.decisions.size() + stores.outbox.size() + stores.history.size() + stores.idempotency.size();
    }

    @Test
    void RLE_075_two_versions_are_compared_over_a_period_with_totals_side_by_side() {
        // Given card-fee v1 published, v2 a draft equal except R3 at 30,000 VND, and 10,000 logged January decisions
        RuleEngines engines = engines(EngineSettings.defaults());
        versions(engines, "bank-a", CardFee.yaml(), cardFee("30000 VND"));
        januaryOfCardFee(10_000);
        long before = writes();
        caller.become("bank-a", "thu", COMMITTEE);
        RuleEngine engine = engines.ruleEngine();

        // When thu compares v1 with v2 over January, then repeats it
        ComparisonResult first = engine.compare("card-fee", january());
        ComparisonResult second = engine.compare("card-fee", january());

        // Then 10,000 are compared and 5,000 changed; the totals are side by side in minor units
        assertThat(first.decisionsCompared()).isEqualTo(10_000);
        assertThat(first.changedCount()).isEqualTo(5_000);
        assertThat(first.baseOutcomes()).containsEntry("MATCHED", 10_000L).containsEntry("FAILED", 0L);
        assertThat(first.candidateOutcomes()).containsEntry("MATCHED", 10_000L);
        assertThat(first.totals()).singleElement().satisfies(total -> {
            assertThat(total.output()).isEqualTo("fee");
            assertThat(total.currency()).isEqualTo("VND");
            assertThat(total.base()).isEqualByComparingTo(new BigDecimal("280000000"));
            assertThat(total.candidate()).isEqualByComparingTo(new BigDecimal("180000000"));
            assertThat(total.difference()).isEqualByComparingTo(new BigDecimal("-100000000"));
        });
        assertThat(first.transitions()).singleElement().satisfies(t -> {
            assertThat(t.output()).isEqualTo("fee");
            assertThat(t.from()).isEqualTo(Money.ofMinor("VND", 50000));
            assertThat(t.to()).isEqualTo(Money.ofMinor("VND", 30000));
            assertThat(t.count()).isEqualTo(5_000);
        });
        assertThat(first.skipped()).isEmpty();
        assertThat(first.from()).isEqualTo(JAN_01);
        assertThat(first.to()).isEqualTo(FEB_01);
        assertThat(first.baseCanonicalHash()).hasSize(64).isNotEqualTo(first.candidateCanonicalHash());

        // And nothing was written, the repeat is byte-identical, and the body holds no input or decision id
        assertThat(writes()).isEqualTo(before);
        String body = EngineValues.write(EngineValues.comparison(first));
        assertThat(EngineValues.write(EngineValues.comparison(second))).isEqualTo(body);
        assertThat(body).contains("\"decisionsCompared\":10000", "\"base\":\"280000000\"", "\"difference\":\"-100000000\"")
                .doesNotContain("tier", "txCount", "teller-01", stores.decisions.getFirst().id());
    }

    @Test
    void RLE_075_a_draft_is_compared_with_the_version_in_force_and_transitions_are_the_twenty_most_common() {
        // Given a version 2 that changes R1 and R2 as well, so more than one output change is counted
        RuleEngines engines = engines(EngineSettings.defaults());
        String changed = new String(cardFee("30000 VND"), StandardCharsets.UTF_8)
                .replace("then: { fee: \"0 VND\" }", "then: { fee: \"5000 VND\" }")
                .replace("then: { fee: \"10000 VND\" }", "then: { fee: \"12000 VND\" }");
        versions(engines, "bank-a", CardFee.yaml(), changed.getBytes(StandardCharsets.UTF_8));
        januaryOfCardFee(1_000);
        caller.become("bank-a", "thu", COMMITTEE);

        // When
        ComparisonResult result = engines.ruleEngine().compare("card-fee", january());

        // Then three transitions, the biggest first (R3: 500, R2: 300, R1: 200), and a changed count of every decision
        assertThat(result.changedCount()).isEqualTo(1_000);
        assertThat(result.transitions()).extracting(ComparisonResult.Transition::count).containsExactly(500L, 300L, 200L);
        assertThat(result.transitions().getFirst().to()).isEqualTo(Money.ofMinor("VND", 30000));
        assertThat(result.totals().getFirst().base()).isEqualByComparingTo(new BigDecimal("28000000"));
        assertThat(result.totals().getFirst().candidate()).isEqualByComparingTo(new BigDecimal("19600000"));
        assertThat(result.totals().getFirst().difference()).isEqualByComparingTo(new BigDecimal("-8400000"));
    }

    static byte[] kycRoute(String conditions) {
        return ("""
                schemaVersion: 1
                ruleSet: kyc-route
                hitPolicy: first
                inputs:
                  - { name: country, type: string }
                  - { name: nationalId, type: string, mask: true }
                outputs:
                  - { name: route, type: string }
                rules:
                """ + conditions + """
                  - { id: R2, when: { country: "== 'VN'" }, then: { route: "'AUTO'" } }
                  - { id: R3, when: {}, then: { route: "'MANUAL'" } }
                """).getBytes(StandardCharsets.UTF_8);
    }

    /** v1 never reads nationalId; v2 reads it in its first row; v3 is v1 again, so two versions can be equal in content. */
    void kycRouteVersions(RuleEngines engines) {
        versions(engines, "bank-a", kycRoute(""),
                kycRoute("  - { id: R1, when: { nationalId: \"is not null\" }, then: { route: \"'REVIEW'\" } }\n"),
                kycRoute(""));
    }

    /** January of kyc-route: 900 decisions whose nationalId is only a fingerprint, 300 whose nationalId was null. */
    void januaryOfKycRoute() {
        for (int i = 0; i < 1_200; i++) {
            Instant at = JAN_01.plus(Duration.ofMinutes(20L * i));
            String nationalId = i < 900 ? "\"" + FINGERPRINT + "\"" : "null";
            stores.append(logged("bank-a", "kyc-route", at, "{\"country\":\"VN\",\"nationalId\":" + nationalId + "}"));
        }
    }

    @Test
    void RLE_076_decisions_that_cannot_be_reevaluated_are_counted_not_guessed() {
        // Given kyc-route v2 reads the masked nationalId and v1 does not; January holds 900 fingerprints and 300 nulls
        RuleEngines engines = engines(EngineSettings.defaults());
        kycRouteVersions(engines);
        januaryOfKycRoute();
        caller.become("bank-a", "thu", COMMITTEE);
        RuleEngine engine = engines.ruleEngine();

        // When thu compares v1 with v2, then v1 with v3, which has v1's content and also never reads nationalId
        ComparisonResult different = engine.compare("kyc-route", january());
        ComparisonResult same = engine.compare("kyc-route", ComparisonRequest.of(1, 3, JAN_01, FEB_01));

        // Then the 900 masked decisions are skipped and reported; the 300 logged nulls are compared and totals cover them
        assertThat(different.skipped()).containsExactly(Map.entry("MASKED_INPUT", 900L));
        assertThat(different.decisionsCompared()).isEqualTo(300);
        assertThat(different.changedCount()).isZero();
        assertThat(different.baseOutcomes()).containsEntry("MATCHED", 300L);

        // And a masked input that neither version reads does not prevent re-evaluation: all 1,200 are compared, none changed
        assertThat(same.decisionsCompared()).isEqualTo(1_200);
        assertThat(same.changedCount()).isZero();
        assertThat(same.skipped()).isEmpty();

        // And no plain value and no fingerprint is in the response
        String body = EngineValues.write(EngineValues.comparison(different));
        assertThat(body).doesNotContain(FINGERPRINT, "9f2c41d0", "nationalId").contains("\"skipped\":{\"MASKED_INPUT\":900}");
    }

    @Test
    void RLE_076_an_input_a_version_reads_that_an_older_decision_does_not_hold_is_counted_as_missing() {
        // Given kyc-route v2 reads nationalId, and 200 logged decisions that never held it (the input was added later)
        RuleEngines engines = engines(EngineSettings.defaults());
        kycRouteVersions(engines);
        for (int i = 0; i < 200; i++) {
            stores.append(logged("bank-a", "kyc-route", JAN_01.plus(Duration.ofHours(i)), "{\"country\":\"VN\"}"));
        }
        caller.become("bank-a", "thu", COMMITTEE);

        // When thu compares v1 with v2, then v1 with v3
        ComparisonResult withRead = engines.ruleEngine().compare("kyc-route", january());
        ComparisonResult without = engines.ruleEngine().compare("kyc-route", ComparisonRequest.of(1, 3, JAN_01, FEB_01));

        // Then the decisions are counted under MISSING_INPUT for v2 and compared for v3
        assertThat(withRead.skipped()).containsExactly(Map.entry("MISSING_INPUT", 200L));
        assertThat(withRead.decisionsCompared()).isZero();
        assertThat(without.decisionsCompared()).isEqualTo(200);
        assertThat(without.changedCount()).isZero();
    }

    @Test
    void RLE_077_comparison_is_bounded_permission_checked_and_tenant_scoped() {
        // Given card-fee v1 and v2 at bank-a, 10,000 January decisions, and a host that lowered the cap to 5,000
        RuleEngines engines = engines(EngineSettings.defaults().withSimulationMaxHistoryDecisions(5_000));
        versions(engines, "bank-a", CardFee.yaml(), cardFee("30000 VND"));
        januaryOfCardFee(10_000);
        long before = writes();
        caller.become("bank-a", "thu", COMMITTEE);
        RuleEngine engine = engines.ruleEngine();

        // The over-cap period is 422 HISTORY_TOO_LARGE naming the 10,000 decisions and the setting, with no sample
        assertThatThrownBy(() -> engine.compare("card-fee", january())).isInstanceOfSatisfying(
                HistoryTooLargeException.class, e -> {
                    assertThat(e.code()).isEqualTo("HISTORY_TOO_LARGE");
                    assertThat(e.found()).isEqualTo(10_000);
                    assertThat(e.atLeast()).isFalse();
                    assertThat(e.getMessage()).contains("10000 logged decisions", "simulation.max-history-decisions",
                            "never takes a sample");
                });
        // A period under the cap is answered whole
        ComparisonResult firstDays = engine.compare("card-fee",
                ComparisonRequest.of(1, 2, JAN_01, JAN_01.plus(Duration.ofDays(8))));
        assertThat(firstDays.decisionsCompared()).isEqualTo(2_880);

        // A 32-day period and equal versions are 400 QUERY_INVALID, naming the setting and the member
        assertThatThrownBy(() -> engine.compare("card-fee",
                ComparisonRequest.of(1, 2, JAN_01, JAN_01.plus(Duration.ofDays(32)))))
                .isInstanceOf(QueryInvalidException.class).hasMessageContaining("query.max-range");
        assertThatThrownBy(() -> engine.compare("card-fee", ComparisonRequest.of(2, 2, JAN_01, FEB_01)))
                .isInstanceOf(QueryInvalidException.class).hasMessageContaining("candidate");
        assertThatThrownBy(() -> engine.compare("card-fee", ComparisonRequest.of(1, 2, FEB_01, JAN_01)))
                .isInstanceOf(QueryInvalidException.class).hasMessageContaining("to must be after from");

        // Version 9 is 404 VERSION_NOT_FOUND
        assertThatThrownBy(() -> engine.compare("card-fee", ComparisonRequest.of(1, 9, JAN_01, FEB_01)))
                .isInstanceOf(VersionNotFoundException.class);

        // A bank-b caller gets 404 RULE_SET_NOT_FOUND for bank-a's rule set
        caller.become("bank-b", "thu", COMMITTEE);
        assertThatThrownBy(() -> engine.compare("card-fee", january())).isInstanceOf(RuleSetNotFoundException.class);

        // dan holds rules:log:read only, and a caller with rules:simulate only fares the same: 403 PERMISSION_DENIED
        caller.become("bank-a", "dan", Set.of("rules:read", "rules:log:read"));
        assertThatThrownBy(() -> engine.compare("card-fee", january())).isInstanceOf(PermissionDeniedException.class);
        caller.become("bank-a", "dan", Set.of("rules:read", "rules:simulate"));
        assertThatThrownBy(() -> engine.compare("card-fee", january())).isInstanceOf(PermissionDeniedException.class);

        // And nothing was written in any case
        assertThat(writes()).isEqualTo(before);
    }

    @Test
    void RLE_077_a_version_that_no_longer_matches_its_hash_is_refused_before_any_decision_is_read() {
        // Given v2's stored canonical document altered after it was imported
        RuleEngines engines = engines(EngineSettings.defaults());
        versions(engines, "bank-a", CardFee.yaml(), cardFee("30000 VND"));
        januaryOfCardFee(100);
        var stored = stores.versions.get(1);
        stores.versions.set(1, new io.github.dhluong90.bpm.rules.internal.store.StoredVersion(stored.tenantId(), stored.id(),
                stored.ruleSetId(), stored.ruleSet(), stored.version(), stored.status(), stored.hitPolicy(),
                stored.effectiveFrom(), stored.canonicalJson().replace("\"30000 VND\"", "\"1 VND\""),
                stored.canonicalHash(), stored.sourceFormat(), stored.sourceDocument(), stored.sourceHash(),
                stored.lockVersion(), stored.requestedBy(), stored.requestedAt(), stored.approvedBy(), stored.approvedAt(),
                stored.approvalComment(), stored.createdBy(), stored.createdAt()));
        caller.become("bank-a", "thu", COMMITTEE);
        long before = writes();

        // When thu compares, then 422 VALIDATION_FAILED and nothing is written
        assertThatThrownBy(() -> engines.ruleEngine().compare("card-fee", january()))
                .isInstanceOf(io.github.dhluong90.bpm.rules.api.ValidationFailedException.class)
                .hasMessageContaining("Integrity check failed");
        assertThat(writes()).isEqualTo(before);
    }

    @Test
    void RLE_077_a_run_that_spends_the_regex_budget_is_refused_whole_with_no_partial_totals() {
        // Given remittance-screening v1 (2 rows) and v2 (1 row), each row reading a 500-digit reference, 100 logged
        // decisions, and a request budget that runs out part-way through them
        long perRow = RegexBoundsTest.readsOf("[0-9]*K1", RegexBoundsTest.LONG_REFERENCE);
        RegexLimits limits = new RegexLimits(1000, 4096, RegexLimits.MAX_READS, RegexLimits.MAX_READS_PER_DECISION,
                40 * 3 * perRow + perRow);
        RuleEngines engines = engines(EngineSettings.defaults().withRegexLimits(limits));
        versions(engines, "bank-a", RegexBoundsTest.screening(2).getBytes(StandardCharsets.UTF_8),
                RegexBoundsTest.screening(1).getBytes(StandardCharsets.UTF_8));
        for (int i = 0; i < 100; i++) {
            stores.append(logged("bank-a", "remittance-screening", JAN_01.plus(Duration.ofHours(i)),
                    "{\"reference\":\"" + RegexBoundsTest.LONG_REFERENCE + "\"}"));
        }
        caller.become("bank-a", "thu", COMMITTEE);
        long before = writes();

        // When thu compares, then 422 EVALUATION_ERROR with REGEX_TIMEOUT naming the setting, and no result at all
        assertThatThrownBy(() -> engines.ruleEngine().compare("remittance-screening", january()))
                .isInstanceOfSatisfying(EvaluationErrorException.class, e -> {
                    assertThat(e.code()).isEqualTo("EVALUATION_ERROR");
                    assertThat(e.getMessage()).startsWith("REGEX_TIMEOUT").contains("evaluation.regex-max-reads-per-request");
                });
        assertThat(writes()).isEqualTo(before);
    }

    @Test
    void RLE_077_the_cap_is_a_setting_from_one_to_200000_that_may_be_raised_and_lowered() {
        assertThat(EngineSettings.defaults().simulationMaxHistoryDecisions()).isEqualTo(50_000);
        assertThat(EngineSettings.defaults().withSimulationMaxHistoryDecisions(200_000).simulationMaxHistoryDecisions())
                .isEqualTo(200_000);
        assertThat(EngineSettings.defaults().withSimulationMaxHistoryDecisions(1).simulationMaxHistoryDecisions()).isEqualTo(1);
        assertThatThrownBy(() -> EngineSettings.defaults().withSimulationMaxHistoryDecisions(0))
                .hasMessageContaining("horus.bpm.rules.simulation.max-history-decisions must be 1 to 200000, was 0");
        assertThatThrownBy(() -> EngineSettings.defaults().withSimulationMaxHistoryDecisions(200_001))
                .hasMessageContaining("horus.bpm.rules.simulation.max-history-decisions must be 1 to 200000, was 200001");
        // The settings survive every other wither
        assertThat(EngineSettings.defaults().withSimulationMaxHistoryDecisions(7).withMaxBatch(10).withDraftAuthoring(true)
                .simulationMaxHistoryDecisions()).isEqualTo(7);
    }
}
