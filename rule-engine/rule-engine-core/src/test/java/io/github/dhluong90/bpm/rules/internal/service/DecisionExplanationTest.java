package io.github.dhluong90.bpm.rules.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.expr.api.Money;
import io.github.dhluong90.bpm.rules.api.DecisionExplanation;
import io.github.dhluong90.bpm.rules.api.DecisionExplanation.Condition;
import io.github.dhluong90.bpm.rules.api.DecisionExplanation.Reason;
import io.github.dhluong90.bpm.rules.api.DecisionInput;
import io.github.dhluong90.bpm.rules.api.DecisionNotFoundException;
import io.github.dhluong90.bpm.rules.api.ImportRequest;
import io.github.dhluong90.bpm.rules.api.MaskingKey;
import io.github.dhluong90.bpm.rules.api.PermissionDeniedException;
import io.github.dhluong90.bpm.rules.api.WriteOptions;
import io.github.dhluong90.bpm.rules.spi.MaskingPolicy;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The plain-language explanation of a logged decision (RLE-030, RLE-031, DESIGN section 4.7), through the public
 * services on the in-memory stores. Every explanation is built from the decision row and the stored version only.
 */
class DecisionExplanationTest {

    static final Instant CLOCK = Instant.parse("2026-11-20T10:00:00Z");
    static final String NATIONAL_ID = "079090001234";
    static final Set<String> LOG_READ = Set.of("rules:log:read");

    static final String CARD_FEE = """
            schemaVersion: 1
            ruleSet: card-fee
            hitPolicy: first
            inputs:
              - { name: tier, type: string }
              - { name: txCount, type: integer }
            outputs:
              - { name: fee, type: money }
            rules:
              - { id: R1, when: { tier: "== 'GOLD'" }, then: { fee: "0 VND" } }
              - { id: R2, when: { txCount: ">= 3" }, then: { fee: "10000 VND" } }
              - { id: R3, when: {}, then: { fee: "50000 VND" } }
            """;

    /** card-fee without the catch-all R3, for the no-match case of RLE-031. */
    static final String CARD_FEE_NO_DEFAULT = """
            schemaVersion: 1
            ruleSet: card-fee-strict
            hitPolicy: first
            inputs:
              - { name: tier, type: string }
              - { name: txCount, type: integer }
            outputs:
              - { name: fee, type: money }
            rules:
              - { id: R1, when: { tier: "== 'GOLD'" }, then: { fee: "0 VND" } }
              - { id: R2, when: { txCount: ">= 3" }, then: { fee: "10000 VND" } }
            """;

    static final String MINOR_REVIEW = MaskedInputsTest.MINOR_REVIEW;

    /** The null-check table of RLE-006. */
    static final String NULL_CHECK = """
            schemaVersion: 1
            ruleSet: null-check
            hitPolicy: collect
            inputs:
              - { name: income, type: money }
              - { name: tier, type: string }
            outputs:
              - { name: hits, type: integer, collect: count }
            rules:
              - { id: R1, when: { income: "< 5000000 VND" }, then: { hits: 1 } }
              - { id: R2, when: { income: "is null" }, then: { hits: 1 } }
              - { id: R3, when: { tier: "!= 'GOLD'" }, then: { hits: 1 } }
              - { id: R7, when: { any: [ { income: "< 5000000 VND" }, { tier: "is null" } ] }, then: { hits: 1 } }
            """;

    /** The overlapping loan-pricing table of RLE-002. */
    static final String LOAN_PRICING = """
            schemaVersion: 1
            ruleSet: loan-pricing
            hitPolicy: unique
            inputs:
              - { name: score, type: integer }
              - { name: income, type: money }
            outputs:
              - { name: rate, type: decimal }
            rules:
              - { id: R1, when: { score: ">= 700" }, then: { rate: 0.08 } }
              - { id: R2, when: { income: ">= 20000000 VND" }, then: { rate: 0.09 } }
            """;

    final InMemoryStores stores = new InMemoryStores();
    final TestCaller caller = new TestCaller("bank-a", "lan");

    RuleEngines engines() {
        return engines(CatalogueExplainer.bundled());
    }

    RuleEngines engines(io.github.dhluong90.bpm.rules.spi.DecisionExplainer explainer) {
        MaskingPolicy masking = MaskingPolicy.withKey(MaskingKey.of(MaskedInputsTest.KEY_A));
        return RuleEngines.create(new EngineContext(caller, caller, Clock.fixed(CLOCK, ZoneOffset.UTC), stores, stores,
                EngineSettings.defaults().withRequireContentHash(false), masking), stores, stores, stores, DecisionQuerySettings.defaults(), explainer);
    }

    void publish(RuleEngines engines, String yaml, String name, int version) {
        caller.become("bank-a", "lan");
        byte[] source = yaml.getBytes(StandardCharsets.UTF_8);
        if (version == 1) {
            engines.versionService().importRuleSet(ImportRequest.yaml(source));
        } else {
            engines.versionService().importVersion(name, ImportRequest.yaml(source));
        }
        long lock = engines.versionService().requestPublish(name, version, WriteOptions.none()).lockVersion();
        caller.become("bank-a", "minh");
        engines.versionService().approve(name, version, new WriteOptions(null, lock, null));
    }

    String decide(RuleEngines engines, String ruleSet, Map<String, Object> inputs) {
        caller.become("bank-a", "teller", Set.of("rules:evaluate"));
        return engines.ruleEngine().evaluate(ruleSet, DecisionInput.of(inputs)).decisionId();
    }

    DecisionExplanation explain(RuleEngines engines, String id, String... languages) {
        caller.become("bank-a", "dan", LOG_READ);
        return engines.decisionLog().explain(id, List.of(languages));
    }

    @Test
    void RLE_030_explanation_is_generated_from_log_entry_and_version() {
        RuleEngines engines = engines();
        publish(engines, CARD_FEE, "card-fee", 1);
        String d7 = decide(engines, "card-fee", Map.of("tier", "SILVER", "txCount", 5));
        publish(engines, new String(CardFee.resource("/rules/risk-flags.yaml"), StandardCharsets.UTF_8), "risk-flags", 1);
        String d8 = decide(engines, "risk-flags", Map.of("txPerHour", 12, "country", "VN", "newDevice", true));
        // v2 of card-fee is published after both decisions; it changes nothing in their explanation
        publish(engines, CARD_FEE.replace("10000 VND", "20000 VND"), "card-fee", 2);
        int decisionsBefore = stores.decisions.size();

        DecisionExplanation first = explain(engines, d7);
        DecisionExplanation again = explain(engines, d7);
        DecisionExplanation collect = explain(engines, d8);

        assertThat(first.ruleSet()).isEqualTo("card-fee");
        assertThat(first.version()).isEqualTo(1);
        assertThat(first.hitPolicy()).isEqualTo("FIRST");
        assertThat(first.outcome()).isEqualTo("OK");
        assertThat(first.clock()).isEqualTo(CLOCK);
        assertThat(first.matched()).hasSize(1);
        assertThat(first.matched().getFirst().ruleId()).isEqualTo("R2");
        assertThat(first.matched().getFirst().position()).isEqualTo(2);
        assertThat(first.matched().getFirst().conditions())
                .containsExactly(new Condition("txCount", "/when/txCount", ">= 3", "5", false, false, true));
        assertThat(first.matched().getFirst().outputsJson()).isEqualTo("{\"fee\":{\"currency\":\"VND\",\"amount\":10000}}");
        assertThat(first.reasons()).containsExactly(new Reason("RULE_MATCHED", Map.of("ruleId", "R2", "version", 1)));
        assertThat(first.language()).isEqualTo("en");
        assertThat(first.text()).isEqualTo(
                "Rule R2 of card-fee version 1 matched because txCount is 5, which is at least 3. Result: fee 10000 VND.");
        assertThat(again).isEqualTo(first);
        assertThat(stores.decisions).hasSize(decisionsBefore);

        assertThat(collect.matched()).extracting(DecisionExplanation.MatchedRow::ruleId).containsExactly("R1", "R3");
        assertThat(collect.matched().get(0).outputsJson()).isEqualTo("{\"flags\":\"HIGH_VELOCITY\"}");
        assertThat(collect.matched().get(1).outputsJson()).isEqualTo("{\"flags\":\"NEW_DEVICE\"}");
        assertThat(collect.outputsJson()).contains("\"flags\":[\"HIGH_VELOCITY\",\"NEW_DEVICE\"]").contains("\"penalty\":15");
        assertThat(collect.text()).isEqualTo("Rule R1 of risk-flags version 1 matched because txPerHour is 12, which is"
                + " at least 10. Rule R3 of risk-flags version 1 matched because newDevice is true, which equals true."
                + " Result: flags [HIGH_VELOCITY, NEW_DEVICE]; penalty 15.");

        // An unknown id and a caller of another tenant get DECISION_NOT_FOUND
        assertThatThrownBy(() -> explain(engines, "01KF2Q8Z0RQ6V2Y8M1C4N5P7T9"))
                .isInstanceOf(DecisionNotFoundException.class);
        caller.become("bank-b", "dan", LOG_READ);
        assertThatThrownBy(() -> engines.decisionLog().explain(d7, List.of()))
                .isInstanceOf(DecisionNotFoundException.class);
    }

    @Test
    void RLE_030_text_follows_the_requested_language_and_reasons_never_change() {
        RuleEngines engines = engines();
        publish(engines, CARD_FEE, "card-fee", 1);
        String d7 = decide(engines, "card-fee", Map.of("tier", "SILVER", "txCount", 5));

        DecisionExplanation english = explain(engines, d7);
        DecisionExplanation vietnamese = explain(engines, d7, "vi", "en");
        DecisionExplanation unknown = explain(engines, d7, "fr");

        assertThat(vietnamese.language()).isEqualTo("vi");
        assertThat(vietnamese.text()).isEqualTo(
                "Quy tắc R2 của card-fee phiên bản 1 khớp vì txCount là 5, tức là ít nhất 3. Kết quả: fee 10000 VND.");
        assertThat(vietnamese.reasons()).isEqualTo(english.reasons());
        assertThat(vietnamese.matched()).isEqualTo(english.matched());
        assertThat(unknown.language()).isEqualTo("en");
        assertThat(unknown.text()).isEqualTo(english.text());
    }

    @Test
    void RLE_030_a_host_catalogue_overrides_an_entry_and_adds_a_language_without_code() {
        RuleEngines engines = engines(CatalogueExplainer.of("vi", Map.of(
                "en", Map.of("result", "Charged: {outputs}."),
                "fr", Map.of("row.because", "La règle {ruleId} de {ruleSet} version {version} s'applique car {conditions}."))));
        publish(engines, CARD_FEE, "card-fee", 1);
        String d7 = decide(engines, "card-fee", Map.of("tier", "SILVER", "txCount", 5));

        assertThat(explain(engines, d7, "en").text()).isEqualTo(
                "Rule R2 of card-fee version 1 matched because txCount is 5, which is at least 3. Charged: fee 10000 VND.");
        // A host language falls back to English entry by entry
        assertThat(explain(engines, d7, "fr").text()).isEqualTo("La règle R2 de card-fee version 1 s'applique car"
                + " txCount is 5, which is at least 3. Charged: fee 10000 VND.");
        // No preference: the configured default language
        assertThat(explain(engines, d7).language()).isEqualTo("vi");
        assertThatThrownBy(() -> CatalogueExplainer.of("de", Map.of()))
                .hasMessageContaining("horus.bpm.rules.explain.default-language");
        assertThatThrownBy(() -> CatalogueExplainer.of("en", Map.of("EN-us", Map.of())))
                .hasMessageContaining("two or three lower-case letters");
    }

    @Test
    void RLE_031_explanation_masks_inputs_and_covers_no_match_null_and_failure() {
        RuleEngines engines = engines();
        publish(engines, MINOR_REVIEW, "minor-review", 1);
        publish(engines, CARD_FEE_NO_DEFAULT, "card-fee-strict", 1);
        publish(engines, NULL_CHECK, "null-check", 1);
        publish(engines, LOAN_PRICING, "loan-pricing", 1);
        String d11 = decide(engines, "minor-review", Map.of("nationalId", NATIONAL_ID, "age", 16));
        String d12 = decide(engines, "card-fee-strict", Map.of("tier", "SILVER", "txCount", 1));
        Map<String, Object> nulls = new HashMap<>();
        nulls.put("income", null);
        nulls.put("tier", null);
        String d13 = decide(engines, "null-check", nulls);
        caller.become("bank-a", "teller", Set.of("rules:evaluate"));
        String d14 = engines.ruleEngine().evaluate("loan-pricing", DecisionInput.of(Map.of("score", 750,
                "income", Money.ofMinor("VND", 25_000_000)))).decisionId();
        int decisionsBefore = stores.decisions.size();

        DecisionExplanation masked = explain(engines, d11);
        DecisionExplanation noMatch = explain(engines, d12);
        DecisionExplanation missing = explain(engines, d13);
        DecisionExplanation unique = explain(engines, d14);

        // D11: the masked input stays its fingerprint, everywhere
        List<Condition> d11Conditions = masked.matched().getFirst().conditions();
        assertThat(d11Conditions.get(0).input()).isEqualTo("nationalId");
        assertThat(d11Conditions.get(0).masked()).isTrue();
        assertThat(d11Conditions.get(0).valueJson()).matches("\"hmac:k1:[0-9a-f]{64}\"");
        assertThat(d11Conditions.get(1)).isEqualTo(new Condition("age", "/when/age", "< 18", "16", false, false, true));
        assertThat(masked.reasons()).contains(new Reason("VALUE_MASKED", Map.of("ruleId", "R1", "input", "nationalId")));
        assertThat(masked.text()).isEqualTo("Rule R1 of minor-review version 1 matched because nationalId is masked,"
                + " which has a value, and age is 16, which is less than 18. Result: route MANUAL.");
        assertThat(masked.toString()).doesNotContain(NATIONAL_ID);
        assertThat(explain(engines, d11, "vi").text()).contains("nationalId đã được che").doesNotContain(NATIONAL_ID);

        // D12: no match
        assertThat(noMatch.matched()).isEmpty();
        assertThat(noMatch.reasons()).containsExactly(new Reason("NO_RULE_MATCHED",
                Map.of("ruleSet", "card-fee-strict", "version", 1)));
        assertThat(noMatch.text()).isEqualTo("No rule of card-fee-strict version 1 matched. No output was produced.");

        // D13: null inputs; a condition in an any group has no result, because the log does not say which held
        assertThat(missing.matched()).extracting(DecisionExplanation.MatchedRow::ruleId).containsExactly("R2", "R3", "R7");
        Condition incomeIsNull = missing.matched().getFirst().conditions().getFirst();
        assertThat(incomeIsNull).isEqualTo(new Condition("income", "/when/income", "is null", "null", false, true, true));
        assertThat(missing.reasons()).contains(new Reason("VALUE_MISSING", Map.of("ruleId", "R2", "input", "income")));
        assertThat(missing.matched().get(2).conditions()).extracting(Condition::path, Condition::result)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("/when/any/0/income", null),
                        org.assertj.core.groups.Tuple.tuple("/when/any/1/tier", null));
        assertThat(missing.text()).contains("Rule R2 of null-check version 1 matched because income has no value.")
                .contains("Rule R7 of null-check version 1 matched because at least one of these held: income has no"
                        + " value or tier has no value.");
        assertThat(missing.matched()).flatExtracting(DecisionExplanation.MatchedRow::conditions)
                .allSatisfy(c -> assertThat(c.result()).isNotEqualTo(Boolean.FALSE));

        // D14: the unique hit violation lists both stored rows
        assertThat(unique.outcome()).isEqualTo("UNIQUE_HIT_VIOLATION");
        assertThat(unique.matched()).extracting(DecisionExplanation.MatchedRow::ruleId).containsExactly("R1", "R2");
        assertThat(unique.reasons().getFirst()).isEqualTo(new Reason("UNIQUE_HIT_VIOLATION",
                Map.of("ruleIds", List.of("R1", "R2"), "version", 1)));
        assertThat(unique.text()).isEqualTo("Rules R1 and R2 of loan-pricing version 1 both matched; this table allows"
                + " one. No output was produced.");

        // Nothing is evaluated or logged by an explanation
        assertThat(stores.decisions).hasSize(decisionsBefore);
    }

    @Test
    void RLE_031_explain_needs_rules_log_read_and_refuses_an_impossible_id_without_a_query() {
        RuleEngines engines = engines();
        publish(engines, CARD_FEE, "card-fee", 1);
        String d7 = decide(engines, "card-fee", Map.of("tier", "GOLD", "txCount", 0));

        caller.become("bank-a", "teller", Set.of("rules:evaluate", "rules:read"));
        assertThatThrownBy(() -> engines.decisionLog().explain(d7, List.of()))
                .isInstanceOf(PermissionDeniedException.class);
        assertThatThrownBy(() -> explain(engines, "not-an-id")).isInstanceOf(DecisionNotFoundException.class);
        assertThat(explain(engines, d7).text()).isEqualTo("Rule R1 of card-fee version 1 matched because tier is GOLD,"
                + " which equals GOLD. Result: fee 0 VND.");
    }

    @Test
    void numbers_dates_lists_and_other_tests_are_worded_locale_neutral() {
        CatalogueExplainer explainer = CatalogueExplainer.bundled();
        DecisionExplanation e = new DecisionExplanation("D1", "pricing", 3, "FIRST", "OK", null, CLOCK,
                List.of(new DecisionExplanation.MatchedRow("R9", 9, List.of(
                        new Condition("amount", "/when/amount", ">= 50000000 VND",
                                "{\"currency\":\"USD\",\"amount\":1250}", false, false, true),
                        new Condition("country", "/when/country", "in ['VN', 'SG']", "\"SG\"", false, false, true),
                        new Condition("opened", "/when/opened", "between date('2026-01-01') and date('2026-12-31')",
                                "\"2026-06-01\"", false, false, true),
                        new Condition("code", "/when/code", "matches(x, '^VIP-[0-9]+')", "\"VIP-7\"", false, false,
                                true),
                        new Condition("name", "/when/name", "== 'it\\'s'", "\"it's\"", false, false, true)),
                        "{\"rate\":0.015}")),
                "{\"rate\":0.015,\"limit\":{\"currency\":\"VND\",\"amount\":10000000}}", List.of(), null, null);

        assertThat(explainer.text(e, "en")).isEqualTo("Rule R9 of pricing version 3 matched because amount is 12.50 USD,"
                + " which is at least 50000000 VND, and country is SG, which is one of VN, SG, and opened is 2026-06-01,"
                + " which is between 2026-01-01 and 2026-12-31, and code is VIP-7, which satisfies"
                + " matches(x, '^VIP-[0-9]+'), and name is it's, which equals it's."
                + " Result: rate 0.015; limit 10000000 VND.");
    }
}
