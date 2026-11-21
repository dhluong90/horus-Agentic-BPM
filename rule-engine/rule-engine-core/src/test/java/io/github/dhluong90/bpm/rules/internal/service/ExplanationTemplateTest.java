package io.github.dhluong90.bpm.rules.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.expr.api.Money;
import io.github.dhluong90.bpm.rules.api.DecisionExplanation;
import io.github.dhluong90.bpm.rules.api.DecisionInput;
import io.github.dhluong90.bpm.rules.api.ImportRequest;
import io.github.dhluong90.bpm.rules.api.MaskingKey;
import io.github.dhluong90.bpm.rules.api.SourceFormat;
import io.github.dhluong90.bpm.rules.api.ValidationFailedException;
import io.github.dhluong90.bpm.rules.api.VersionDiff;
import io.github.dhluong90.bpm.rules.api.Violation;
import io.github.dhluong90.bpm.rules.api.WriteOptions;
import io.github.dhluong90.bpm.rules.internal.read.RuleSetReader;
import io.github.dhluong90.bpm.rules.spi.MaskingPolicy;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Author-written explanation sentences (RLE-072 to RLE-074, PD-123) and the readable wording of generated ones
 * (RLE-030, #392), through the public services on the in-memory stores.
 */
class ExplanationTemplateTest {

    static final String CARD_FEE_V1 = """
            schemaVersion: 1
            ruleSet: card-fee
            hitPolicy: first
            explanations:
              noMatch:
                en: "No card fee applies to this account."
                vi: "Tài khoản này không thuộc biểu phí thẻ nào."
            inputs:
              - { name: tier, type: string }
              - { name: txCount, type: integer }
            outputs:
              - { name: fee, type: money }
            rules:
              - { id: R1, when: { tier: "== 'GOLD'" }, then: { fee: "0 VND" } }
              - id: R2
                when: { txCount: ">= 3" }
                then: { fee: "10000 VND" }
                explain:
                  en: "Your card fee is {output.fee} because you made {input.txCount} transactions this month."
                  vi: "Phí thẻ của bạn là {output.fee} vì bạn đã thực hiện {input.txCount} giao dịch trong tháng."
            """;

    final InMemoryStores stores = new InMemoryStores();
    final TestCaller caller = new TestCaller("bank-a", "lan");

    RuleEngines engines(boolean maskByDefault) {
        MaskingPolicy masking = new MaskingPolicy() {
            @Override
            public Optional<MaskingKey> hmacKey(String tenantId) {
                return Optional.of(MaskingKey.of(MaskedInputsTest.KEY_A));
            }

            @Override
            public boolean maskByDefault(String tenantId) {
                return maskByDefault;
            }
        };
        return RuleEngines.create(new EngineContext(caller, caller, Clock.fixed(DecisionExplanationTest.CLOCK,
                ZoneOffset.UTC), stores, stores, EngineSettings.defaults(), masking), stores, stores, stores,
                DecisionQuerySettings.defaults(), CatalogueExplainer.bundled());
    }

    RuleEngines engines() {
        return engines(false);
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
        caller.become("bank-a", "dan", DecisionExplanationTest.LOG_READ);
        return engines.decisionLog().explain(id, List.of(languages));
    }

    Violation refused(RuleEngines engines, String yaml) {
        caller.become("bank-a", "lan");
        assertThatThrownBy(() -> engines.versionService().importVersion("card-fee",
                ImportRequest.yaml(yaml.getBytes(StandardCharsets.UTF_8)))).isInstanceOf(ValidationFailedException.class);
        try {
            engines.versionService().importVersion("card-fee", ImportRequest.yaml(yaml.getBytes(StandardCharsets.UTF_8)));
        } catch (ValidationFailedException e) {
            return e.violations().getFirst();
        }
        throw new AssertionError("expected a refusal");
    }

    @Test
    void RLE_072_decision_is_explained_with_the_authors_template() {
        // Given card-fee v1 with R2 templated in en and vi, R1 without a template, and a sentence for no match
        RuleEngines engines = engines();
        publish(engines, CARD_FEE_V1, "card-fee", 1);
        String d7 = decide(engines, "card-fee", Map.of("tier", "SILVER", "txCount", 5));
        String d8 = decide(engines, "card-fee", Map.of("tier", "GOLD", "txCount", 1));
        String d9 = decide(engines, "card-fee", Map.of("tier", "SILVER", "txCount", 1));
        // And v2 changes the R2 wording after the decisions
        publish(engines, CARD_FEE_V1.replace("Your card fee is", "The card fee is"), "card-fee", 2);
        int decisionsBefore = stores.decisions.size();

        // Then D7 reads the author's sentence of the requested language, with money grouped for it
        DecisionExplanation vi = explain(engines, d7, "vi");
        assertThat(vi.text()).isEqualTo("Phí thẻ của bạn là 10.000 VND vì bạn đã thực hiện 5 giao dịch trong tháng.");
        assertThat(vi.textSource()).isEqualTo("TEMPLATE");
        DecisionExplanation en = explain(engines, d7, "en");
        assertThat(en.text()).isEqualTo("Your card fee is 10,000 VND because you made 5 transactions this month.");
        assertThat(en.textSource()).isEqualTo("TEMPLATE");
        // And a language with no template is the generated sentence in the default language, never another's template
        DecisionExplanation th = explain(engines, d7, "th");
        assertThat(th.language()).isEqualTo("en");
        assertThat(th.textSource()).isEqualTo("GENERATED");
        assertThat(th.text()).isEqualTo("Rule R2 of card-fee version 1 matched because tx count is 5, which is at"
                + " least 3. Result: fee 10,000 VND.");
        // And a row without a template falls back; no match uses the sentence of no match
        assertThat(explain(engines, d8, "en").textSource()).isEqualTo("GENERATED");
        assertThat(explain(engines, d8, "en").text()).startsWith("Rule R1 of card-fee version 1 matched");
        DecisionExplanation noMatch = explain(engines, d9, "en");
        assertThat(noMatch.text()).isEqualTo("No card fee applies to this account.");
        assertThat(noMatch.textSource()).isEqualTo("TEMPLATE");
        // And the contract is the same with or without templates, v2 changes nothing, and nothing is written
        assertThat(en.reasons()).isEqualTo(th.reasons());
        assertThat(en.matched()).isEqualTo(th.matched());
        assertThat(explain(engines, d7, "en")).isEqualTo(en);
        assertThat(stores.decisions).hasSize(decisionsBefore);
    }

    @Test
    void RLE_073_template_is_checked_at_import_and_changes_the_approved_content() {
        RuleEngines engines = engines();
        publish(engines, CARD_FEE_V1.replace("mask: true", ""), "card-fee", 1);
        String nationalId = CARD_FEE_V1.replace("- { name: tier, type: string }",
                "- { name: tier, type: string }\n  - { name: nationalId, type: string, mask: true }");
        String sentence = "Your card fee is {output.fee} because you made {input.txCount} transactions this month.";

        Violation undeclared = refused(engines, CARD_FEE_V1.replace("{input.txCount}", "{input.txCnt}"));
        assertThat(undeclared.ruleId()).isEqualTo("R2");
        assertThat(undeclared.column()).isEqualTo("explain");
        assertThat(undeclared.code()).isEqualTo("UNKNOWN_PLACEHOLDER");
        assertThat(refused(engines, CARD_FEE_V1.replace("{output.fee}", "{output.fee | upper}")).code())
                .isEqualTo("UNKNOWN_PLACEHOLDER");
        assertThat(refused(engines, nationalId.replace("{input.txCount}", "{input.nationalId}")).code())
                .isEqualTo("MASKED_INPUT_IN_TEMPLATE");
        assertThat(refused(engines, CARD_FEE_V1.replace("    en: \"" + sentence, "    english: \"" + sentence)).code())
                .isEqualTo("INPUT_INVALID");
        assertThat(refused(engines, CARD_FEE_V1.replace(sentence, "x".repeat(501))).code())
                .isEqualTo("TEMPLATE_TOO_LONG");
        assertThat(stores.versions).hasSize(1); // nothing was stored by the refusals

        // And a valid v2 with other wording imports with a different hash, listed as a change of /explain/en
        publish(engines, CARD_FEE_V1.replace("Your card fee is", "The card fee is"), "card-fee", 2);
        caller.become("bank-a", "dan", Set.of("rules:read"));
        VersionDiff diff = engines.versionService().diff("card-fee", 2, 1);
        assertThat(diff.baseCanonicalHash()).isNotEqualTo(diff.canonicalHash());
        assertThat(diff.rows()).singleElement().satisfies(row -> {
            assertThat(row.ruleId()).isEqualTo("R2");
            assertThat(row.fields()).extracting(VersionDiff.FieldChange::path).containsExactly("/explain/en");
        });

        // And export to DMN keeps the templates and the round trip keeps the hash
        String exported = engines.versionService().export("card-fee", 2, SourceFormat.DMN_XML);
        assertThat(exported).contains("urn:horus-bpm:dmn:extensions:v1").contains("The card fee is {output.fee}");
        var original = RuleSetReader.read(SourceFormat.YAML, CARD_FEE_V1.replace("Your card fee is", "The card fee is")
                .getBytes(StandardCharsets.UTF_8));
        var again = RuleSetReader.read(SourceFormat.DMN_XML, exported.getBytes(StandardCharsets.UTF_8));
        assertThat(again.canonicalHash()).isEqualTo(original.canonicalHash());
    }

    @Test
    void RLE_074_templates_keep_masked_values_masked_and_fall_back_for_failures() {
        RuleEngines engines = engines(true);
        publish(engines, """
                schemaVersion: 1
                ruleSet: minor-review
                hitPolicy: first
                inputs:
                  - { name: nationalId, type: string }
                  - { name: age, type: integer, mask: false }
                outputs:
                  - { name: route, type: string }
                rules:
                  - id: R1
                    when: { age: "< 18" }
                    then: { route: "'MANUAL'" }
                    explain: { en: "Sent to manual review: applicant {input.nationalId} is {input.age}." }
                  - id: R2
                    when: {}
                    then: { route: "'AUTO'" }
                    explain: { en: "Automatic route, age: {input.age}." }
                """, "minor-review", 1);
        publish(engines, DecisionExplanationTest.LOAN_PRICING.replace("{ id: R1,", "{ id: R1, explain: { en: \"Never shown\" },"),
                "loan-pricing", 1);
        publish(engines, new String(CardFee.resource("/rules/risk-flags.yaml"), StandardCharsets.UTF_8)
                .replace("then: { flags: \"'HIGH_VELOCITY'\", penalty: 10 }",
                        "then: { flags: \"'HIGH_VELOCITY'\", penalty: 10 }\n    explain: { en: \"Fast.\" }")
                .replace("then: { flags: \"'NEW_DEVICE'\", penalty: 5 }",
                        "then: { flags: \"'NEW_DEVICE'\", penalty: 5 }\n    explain: { en: \"New device.\" }"),
                "risk-flags", 1);
        String d11 = decide(engines, "minor-review", Map.of("nationalId", "079090001234", "age", 16));
        java.util.Map<String, Object> noAge = new java.util.HashMap<>();
        noAge.put("nationalId", "079090001234");
        noAge.put("age", null);
        String d13 = decide(engines, "minor-review", noAge);
        String d15 = decide(engines, "risk-flags", Map.of("txPerHour", 12, "country", "VN", "newDevice", true));
        caller.become("bank-a", "teller", Set.of("rules:evaluate"));
        String d14 = engines.ruleEngine().evaluate("loan-pricing", DecisionInput.of(Map.of("score", 750,
                "income", Money.ofMinor("VND", 25_000_000)))).decisionId();

        DecisionExplanation masked = explain(engines, d11, "en");
        assertThat(masked.text()).isEqualTo("Sent to manual review: applicant (masked) is 16.");
        assertThat(masked.textSource()).isEqualTo("TEMPLATE");
        assertThat(masked.toString()).doesNotContain("079090001234").doesNotContain("Never shown");
        // A null input reads as the catalogue's no value
        assertThat(explain(engines, d13, "en").text()).isEqualTo("Automatic route, age: no value.");
        // Both matched rows are templated: their sentences, in rule order, joined by one space
        assertThat(explain(engines, d15, "en").text()).isEqualTo("Fast. New device.");
        // A failure is never templated
        DecisionExplanation failed = explain(engines, d14, "en");
        assertThat(failed.textSource()).isEqualTo("GENERATED");
        assertThat(failed.text()).startsWith("Rules R1 and R2 of loan-pricing version 1 both matched");
    }

    @Test
    void RLE_030_wording_uses_labels_grouped_amounts_and_yes_no() {
        RuleEngines engines = engines();
        publish(engines, """
                schemaVersion: 1
                ruleSet: fee-waiver
                hitPolicy: first
                inputs:
                  - { name: tier, type: string, label: "Customer tier" }
                  - { name: balance, type: money }
                  - { name: isNewCustomer, type: boolean }
                outputs:
                  - { name: waived, type: boolean, label: "Fee waived" }
                rules:
                  - id: R1
                    when: { tier: "== 'GOLD'", balance: ">= 50000000 VND", isNewCustomer: "== true" }
                    then: { waived: true }
                """, "fee-waiver", 1);
        String id = decide(engines, "fee-waiver", Map.of("tier", "GOLD", "balance", Money.ofMinor("VND", 60_000_000),
                "isNewCustomer", true));

        assertThat(explain(engines, id, "en").text()).isEqualTo("Rule R1 of fee-waiver version 1 matched because"
                + " Customer tier is GOLD, which equals GOLD, and balance is 60,000,000 VND, which is at least"
                + " 50,000,000 VND, and is new customer is Yes, which equals Yes. Result: Fee waived Yes.");
        assertThat(explain(engines, id, "vi").text()).isEqualTo("Quy tắc R1 của fee-waiver phiên bản 1 khớp vì"
                + " Customer tier là GOLD, tức là bằng GOLD, và balance là 60.000.000 VND, tức là ít nhất"
                + " 50.000.000 VND, và is new customer là Có, tức là bằng Có. Kết quả: Fee waived Có.");
    }
}
