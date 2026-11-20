package io.github.dhluong90.bpm.rules.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.rules.api.DecisionInput;
import io.github.dhluong90.bpm.rules.api.DecisionRequest;
import io.github.dhluong90.bpm.rules.api.DecisionResult;
import io.github.dhluong90.bpm.rules.api.ImportRequest;
import io.github.dhluong90.bpm.rules.api.Money;
import io.github.dhluong90.bpm.rules.api.RuleSetNotFoundException;
import io.github.dhluong90.bpm.rules.api.TenantScopedRuleEngine;
import io.github.dhluong90.bpm.rules.api.WriteOptions;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
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
    final TenantScopedRuleEngine scoped = (TenantScopedRuleEngine) engines.ruleEngine();
    final DecisionRequest gold = DecisionRequest.of("card-fee", DecisionInput.of(Map.of("tier", "GOLD", "txCount", 5)))
            .withCorrelationId("01JD2X5K9T1Q4N8V2M7B3C6D0E");

    void publishCardFee(String tenant) {
        caller.become(tenant, "alice");
        engines.versionService().importRuleSet(ImportRequest.yaml(CardFee.yaml()));
        engines.versionService().requestPublish("card-fee", 1, WriteOptions.none());
        caller.become(tenant, "bob");
        engines.versionService().approve("card-fee", 1, WriteOptions.none());
    }

    @Test
    void TR_TEN_01_tenant_scoped_evaluation_uses_the_given_tenant_without_a_caller() {
        // Given card-fee in bank-a only, and a thread whose security context is empty
        publishCardFee("bank-a");
        caller.become(null, null, Set.of());

        // When
        DecisionResult result = scoped.evaluate("bank-a", "system:process-engine", gold);

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
        assertThatThrownBy(() -> scoped.evaluate("bank-b", "system:process-engine", gold))
                .isInstanceOf(RuleSetNotFoundException.class);

        // Then
        assertThat(stores.decisions).isEmpty();
    }

    @Test
    void TR_TEN_01_tenant_scoped_evaluation_refuses_a_blank_tenant_or_a_person_as_caller() {
        // Given
        publishCardFee("bank-a");

        // When, then
        assertThatThrownBy(() -> scoped.evaluate(" ", "system:process-engine", gold))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> scoped.evaluate(null, "system:process-engine", gold))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> scoped.evaluate("bank-a", "tuan", gold)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> scoped.evaluate("bank-a", "system:", gold)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> scoped.evaluate("bank-a", null, gold)).isInstanceOf(IllegalArgumentException.class);
        assertThat(stores.decisions).isEmpty();
    }
}
