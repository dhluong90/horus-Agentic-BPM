package io.github.dhluong90.bpm.rules.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.rules.api.DecisionInput;
import io.github.dhluong90.bpm.rules.api.DecisionRequest;
import io.github.dhluong90.bpm.rules.api.DecisionResult;
import io.github.dhluong90.bpm.rules.api.ImportRequest;
import io.github.dhluong90.bpm.rules.api.Money;
import io.github.dhluong90.bpm.rules.api.RuleSetNotFoundException;
import io.github.dhluong90.bpm.rules.api.RuleEngine;
import io.github.dhluong90.bpm.rules.api.WriteOptions;
import io.github.dhluong90.bpm.rules.embedded.TenantScopedRuleEngine;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The tenant-scoped entry point an embedded engine uses for its own stored work (ADR-0027): the tenant and the
 * system caller are explicit, and the host's security context is not consulted.
 */
class TenantScopedEvaluationTest {

    final InMemoryStores stores = new InMemoryStores();
    final TestCaller caller = new TestCaller("bank-a", "alice");
    final RuleEngines engines = RuleEngines.create(
            new EngineContext(caller, caller, Clock.fixed(Instant.parse("2026-12-15T02:00:00Z"), ZoneOffset.UTC),
                    stores, stores, EngineSettings.defaults()),
            stores, stores, stores);
    /**
     * Claimed as the starter's claimant would, through a grant that registers this test as the claimant and skips the
     * sealing and version check, which {@code EmbeddedEngineClaimantVerificationTest} covers.
     */
    final TenantScopedRuleEngine scoped = new OneTimeEmbeddedEngineGrant((DefaultRuleEngine) engines.ruleEngine(),
            Map.of("system:process-engine", TenantScopedEvaluationTest.class.getName()), type -> Optional.empty())
            .claim("system:process-engine", this);
    final DecisionRequest gold = DecisionRequest.of("card-fee", DecisionInput.of(Map.of("tier", "GOLD", "txCount", 5)))
            .withCorrelationId("01JD2X5K9T1Q4N8V2M7B3C6D0E");

    void publishCardFee(String tenant) {
        caller.become(tenant, "alice");
        engines.versionService().importRuleSet(ImportRequest.yaml(CardFee.yaml()));
        long requested = engines.versionService().requestPublish("card-fee", 1, WriteOptions.none()).lockVersion();
        caller.become(tenant, "bob");
        engines.versionService().approve("card-fee", 1, new WriteOptions(null, requested, null));
    }

    @Test
    void TR_TEN_01_tenant_scoped_evaluation_uses_the_given_tenant_without_a_caller() {
        // Given card-fee in bank-a only, and a thread whose security context is empty
        publishCardFee("bank-a");
        caller.become(null, null, Set.of());

        // When
        DecisionResult result = scoped.evaluate("bank-a", gold);

        // Then
        assertThat(result.outputs()).containsEntry("fee", Money.ofMinor("VND", 0));
        assertThat(stores.decisions).singleElement().satisfies(row -> {
            assertThat(row.tenantId()).isEqualTo("bank-a");
            assertThat(row.callerId()).isEqualTo("system:process-engine");
            assertThat(row.correlationId()).isEqualTo("01JD2X5K9T1Q4N8V2M7B3C6D0E");
        });
    }

    @Test
    void TR_TEN_01_tenant_scoped_evaluation_ignores_the_context_tenant() {
        // Given card-fee in bank-a only, and a context that says bank-a
        publishCardFee("bank-a");
        caller.become("bank-a", "teller-01");

        // When bank-b is asked for, then bank-b's (missing) rule set is the one looked up
        assertThatThrownBy(() -> scoped.evaluate("bank-b", gold))
                .isInstanceOf(RuleSetNotFoundException.class);

        // Then
        assertThat(stores.decisions).isEmpty();
    }

    @Test
    void TR_TEN_01_tenant_scoped_evaluation_refuses_a_blank_tenant() {
        // Given
        publishCardFee("bank-a");

        // When, then
        assertThatThrownBy(() -> scoped.evaluate(" ", gold)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> scoped.evaluate(null, gold)).isInstanceOf(IllegalArgumentException.class);
        assertThat(stores.decisions).isEmpty();
    }

    @Test
    void TR_TEN_01_only_a_registered_engine_identity_gets_the_entry_point() {
        // Given the one registered engine identity
        assertThat(TenantScopedRuleEngine.REGISTERED_ENGINE_IDS).containsExactly("system:process-engine");
        assertThat(scoped.engineId()).isEqualTo("system:process-engine");

        // When a host picks another identity, reserved or invented, then it is refused
        for (String forged : new String[] {"system:timer", "system:dev-auto-approve", "system:effective-date",
                "system:anything", "system:", "tuan", " "}) {
            assertThatThrownBy(() -> new OneTimeEmbeddedEngineGrant((DefaultRuleEngine) engines.ruleEngine())
                    .claim(forged, this))
                    .as(forged).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new OneTimeEmbeddedEngineGrant((DefaultRuleEngine) engines.ruleEngine())
                .claim(null, this))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void TR_TEN_01_the_caller_written_to_the_log_is_the_identity_fixed_at_build_time() {
        // Given card-fee in bank-a, and a thread signed in as a teller
        publishCardFee("bank-a");
        caller.become("bank-a", "teller-01");

        // When the process engine's entry point evaluates
        scoped.evaluate("bank-a", gold);

        // Then the decision is logged as the engine, not as the thread's caller
        assertThat(stores.decisions).singleElement()
                .satisfies(row -> assertThat(row.callerId()).isEqualTo("system:process-engine"));
    }

    @Test
    void TR_TEN_01_the_host_facing_rule_engine_is_not_the_tenant_scoped_entry_point() {
        RuleEngine hostFacing = engines.ruleEngine();
        assertThat(hostFacing).isNotInstanceOf(TenantScopedRuleEngine.class);
        assertThat(scoped).isNotInstanceOf(RuleEngine.class);
    }

    @Test
    void TR_TEN_01_engine_work_refuses_a_pinned_version_or_a_chosen_clock() {
        // Given card-fee in bank-a, and a thread whose security context is empty
        publishCardFee("bank-a");
        caller.become(null, null, Set.of());

        // When engine work pins a version or chooses a clock, then it is refused: those need a caller's rules:pin or
        // rules:simulate
        assertThatThrownBy(() -> scoped.evaluate("bank-a", gold.withVersion(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> scoped.evaluate("bank-a", gold.withClock(Instant.parse("2026-12-15T02:00:00Z"))))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
