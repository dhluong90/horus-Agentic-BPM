package io.github.dhluong90.bpm.rules.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.rules.api.DecisionInput;
import io.github.dhluong90.bpm.rules.api.DecisionResult;
import io.github.dhluong90.bpm.rules.api.ImportRequest;
import io.github.dhluong90.bpm.rules.api.VersionNotFoundException;
import io.github.dhluong90.bpm.rules.api.WriteOptions;
import io.github.dhluong90.bpm.rules.embedded.RuleSetSignature;
import io.github.dhluong90.bpm.rules.embedded.TenantScopedRuleEngine;
import io.github.dhluong90.bpm.rules.embedded.TimedInput;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The simulation path of the tenant-scoped entry point (RLE-015), as the Process Engine's offline replay of agent
 * answers uses it (#98): a stored version in any status over recorded cases, each at its own evaluation time, writing
 * nothing. A KYC guardrail: v1 published refuses a confidence below 0.90 or an expired document; v2, a draft, lowers
 * the bar to 0.85.
 */
class TenantScopedSimulationTest {

    private static final String V1 = """
            schemaVersion: 1
            ruleSet: kyc-document-guardrails
            hitPolicy: first
            inputs:
              - { name: confidence, type: decimal }
              - { name: expiryDate, type: date }
            outputs:
              - { name: allow, type: boolean }
            rules:
              - id: R2
                when: { expiryDate: "< today()" }
                then: { allow: "false" }
              - id: R5
                when: { confidence: "< 0.90" }
                then: { allow: "false" }
              - id: R9
                when: {}
                then: { allow: "true" }
            """;

    final InMemoryStores stores = new InMemoryStores();
    final TestCaller caller = new TestCaller("bank-a", "alice");
    final RuleEngines engines = RuleEngines.create(
            new EngineContext(caller, caller, Clock.fixed(Instant.parse("2027-01-20T03:00:00Z"), ZoneOffset.UTC),
                    stores, stores, EngineSettings.defaults().withRequireContentHash(false)),
            stores, stores, stores);
    final TenantScopedRuleEngine scoped = new OneTimeEmbeddedEngineGrant((DefaultRuleEngine) engines.ruleEngine(),
            Map.of("system:process-engine", TenantScopedSimulationTest.class.getName()), type -> Optional.empty())
            .claim("system:process-engine", this);

    private void publishAndDraft() {
        caller.become("bank-a", "lan");
        engines.versionService().importRuleSet(ImportRequest.yaml(V1.getBytes(StandardCharsets.UTF_8)));
        long requested = engines.versionService().requestPublish("kyc-document-guardrails", 1, WriteOptions.none())
                .lockVersion();
        caller.become("bank-a", "minh");
        engines.versionService().approve("kyc-document-guardrails", 1, new WriteOptions(null, requested, null));
        caller.become("bank-a", "lan");
        engines.versionService().importVersion("kyc-document-guardrails", ImportRequest.yaml(
                V1.replace("< 0.90", "< 0.85").getBytes(StandardCharsets.UTF_8)));
        caller.become(null, null);
    }

    private static TimedInput at(String confidence, String expiry, String instant) {
        return new TimedInput(DecisionInput.of(Map.of("confidence", new BigDecimal(confidence), "expiryDate", expiry)),
                Instant.parse(instant));
    }

    @Test
    void a_draft_simulated_over_recorded_cases_at_their_own_time_writes_no_decision() {
        // Given v1 in force and v2 a draft, and no one signed in
        publishAndDraft();
        int logged = stores.decisions.size();

        // When the draft runs over three cases of November, on 20 January 2027
        List<DecisionResult.Outcome> outcomes = scoped.simulate("bank-a", "kyc-document-guardrails", 2, List.of(
                at("0.87", "2031-06-30", "2026-11-03T01:00:00Z"),
                at("0.96", "2026-11-10", "2026-11-03T01:00:00Z"),
                at("0.80", "2031-06-30", "2026-11-04T01:00:00Z")));

        // Then 0.87 passes the draft's bar, the passport valid on 3 November is not expired, 0.80 is refused,
        // and no decision row was written
        assertThat(outcomes).hasSize(3);
        assertThat(outcomes.get(0)).isInstanceOfSatisfying(DecisionResult.Matched.class,
                m -> assertThat(m.ruleIds()).containsExactly("R9"));
        assertThat(outcomes.get(1)).isInstanceOfSatisfying(DecisionResult.Matched.class,
                m -> assertThat(m.ruleIds()).containsExactly("R9"));
        assertThat(outcomes.get(2)).isInstanceOfSatisfying(DecisionResult.Matched.class, m -> {
            assertThat(m.ruleIds()).containsExactly("R5");
            assertThat(m.outputs()).containsEntry("allow", false);
        });
        assertThat(stores.decisions).hasSize(logged);
    }

    @Test
    void a_case_the_version_cannot_evaluate_fails_alone() {
        publishAndDraft();

        List<DecisionResult.Outcome> outcomes = scoped.simulate("bank-a", "kyc-document-guardrails", 2, List.of(
                new TimedInput(DecisionInput.of(Map.of("confidence", "high")), Instant.parse("2026-11-03T01:00:00Z")),
                at("0.95", "2031-06-30", "2026-11-03T01:00:00Z")));

        assertThat(outcomes.get(0)).isInstanceOfSatisfying(DecisionResult.Failed.class,
                f -> assertThat(f.code()).isEqualTo("INPUT_INVALID"));
        assertThat(outcomes.get(1)).isInstanceOf(DecisionResult.Matched.class);
    }

    @Test
    void a_stored_version_is_described_and_simulated_in_its_own_tenant_only() {
        publishAndDraft();

        RuleSetSignature draft = scoped.signature("bank-a", "kyc-document-guardrails", 2);

        assertThat(draft.version()).isEqualTo(2);
        assertThat(draft.outputs()).containsEntry("allow", "boolean");
        assertThat(draft.inputs()).containsOnlyKeys("confidence", "expiryDate");
        assertThatThrownBy(() -> scoped.signature("bank-b", "kyc-document-guardrails", 2))
                .isInstanceOf(VersionNotFoundException.class);
        assertThatThrownBy(() -> scoped.simulate("bank-b", "kyc-document-guardrails", 2,
                List.of(at("0.95", "2031-06-30", "2026-11-03T01:00:00Z"))))
                .isInstanceOf(VersionNotFoundException.class);
        assertThatThrownBy(() -> scoped.simulate(" ", "kyc-document-guardrails", 2,
                List.of(at("0.95", "2031-06-30", "2026-11-03T01:00:00Z"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> scoped.simulate("bank-a", "kyc-document-guardrails", 2, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
