package io.github.dhluong90.bpm.rules.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.rules.api.DecisionInput;
import io.github.dhluong90.bpm.rules.api.DecisionRequest;
import io.github.dhluong90.bpm.rules.api.ImportRequest;
import io.github.dhluong90.bpm.rules.api.WriteOptions;
import io.github.dhluong90.bpm.rules.embedded.EmbeddedEngineGrant;
import io.github.dhluong90.bpm.rules.embedded.TenantScopedRuleEngine;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The one-time hand-off of the tenant-scoped entry point (ADR-0027, #336): host code that holds the grant, or builds
 * its own {@link RuleEngines}, cannot get an entry point that evaluates in a tenant of its choosing.
 */
class EmbeddedEngineGrantTest {

    /** Stands in for the Process Engine's decision port, registered as the claimant of the grant under test. */
    static final class RegisteredClaimant {
    }

    /** A host controller that takes the tenant from a path parameter. */
    static final class HostController {
    }

    final InMemoryStores stores = new InMemoryStores();
    final TestCaller caller = new TestCaller("bank-a", "alice");
    final RuleEngines engines = RuleEngines.create(
            new EngineContext(caller, caller, Clock.fixed(Instant.parse("2026-12-15T02:00:00Z"), ZoneOffset.UTC),
                    stores, stores, EngineSettings.defaults()),
            stores, stores, stores);

    OneTimeEmbeddedEngineGrant grantFor(Class<?> claimant) {
        return new OneTimeEmbeddedEngineGrant((DefaultRuleEngine) engines.ruleEngine(),
                Map.of("system:process-engine", claimant.getName()));
    }

    @Test
    void TR_TEN_01_the_registered_claimant_is_the_process_engines_decision_port() {
        assertThat(EmbeddedEngineGrant.REGISTERED_CLAIMANTS).containsExactly(Map.entry("system:process-engine",
                "io.github.dhluong90.bpm.process.internal.service.RuleEngineDecisionPort"));
        assertThat(TenantScopedRuleEngine.REGISTERED_ENGINE_IDS).containsExactly("system:process-engine");
    }

    @Test
    void TR_TEN_01_host_code_holding_the_grant_is_refused() {
        // Given the grant of a Rule Engine, as host code that imports internals could reach it
        EmbeddedEngineGrant grant = engines.embeddedEngines();

        // When a host class claims the process engine's entry point, then it is refused and nothing is spent
        assertThatThrownBy(() -> grant.claim("system:process-engine", new HostController()))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("RuleEngineDecisionPort");
        assertThatThrownBy(() -> grant.claim("system:process-engine", null)).isInstanceOf(SecurityException.class);
        assertThat(grant.isClaimed("system:process-engine")).isFalse();
    }

    @Test
    void TR_TEN_01_a_host_that_builds_its_own_rule_engine_is_refused_too() {
        // Given a second RuleEngines built by host code over the same adapters
        RuleEngines own = RuleEngines.create(new EngineContext(caller, caller, Clock.systemUTC(), stores, stores,
                EngineSettings.defaults()), stores, stores, stores);

        // When, then
        assertThatThrownBy(() -> own.embeddedEngines().claim("system:process-engine", this))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void TR_TEN_01_the_entry_point_is_claimed_once() {
        // Given
        OneTimeEmbeddedEngineGrant grant = grantFor(RegisteredClaimant.class);

        // When the registered claimant claims
        TenantScopedRuleEngine scoped = grant.claim("system:process-engine", new RegisteredClaimant());

        // Then it gets the entry point bound to its identity, and a second claim, even by the same class, fails
        assertThat(scoped.engineId()).isEqualTo("system:process-engine");
        assertThat(grant.isClaimed("system:process-engine")).isTrue();
        assertThatThrownBy(() -> grant.claim("system:process-engine", new RegisteredClaimant()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void TR_TEN_01_an_unregistered_identity_is_refused_before_the_claimant_is_looked_at() {
        OneTimeEmbeddedEngineGrant grant = grantFor(RegisteredClaimant.class);
        for (String forged : new String[] {"system:timer", "system:dev-auto-approve", "system:", "tuan", " "}) {
            assertThatThrownBy(() -> grant.claim(forged, new RegisteredClaimant()))
                    .as(forged).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> grant.claim(null, new RegisteredClaimant()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(grant.isClaimed(null)).isFalse();
    }

    @Test
    void TR_TEN_01_a_refused_host_writes_no_decision_in_another_tenant() {
        // Given card-fee in bank-b, and a bank-a user whose host code tries to reach bank-b through the grant
        caller.become("bank-b", "alice");
        engines.versionService().importRuleSet(ImportRequest.yaml(CardFee.yaml()));
        long requested = engines.versionService().requestPublish("card-fee", 1, WriteOptions.none()).lockVersion();
        caller.become("bank-b", "bob");
        engines.versionService().approve("card-fee", 1, new WriteOptions(null, requested, null));
        caller.become("bank-a", "teller-01", Set.of("rules:evaluate"));

        // When
        assertThatThrownBy(() -> engines.embeddedEngines().claim("system:process-engine", new HostController())
                .evaluate("bank-b", DecisionRequest.of("card-fee",
                        DecisionInput.of(Map.of("tier", "GOLD", "txCount", 5))))).isInstanceOf(SecurityException.class);

        // Then
        assertThat(stores.decisions).isEmpty();
    }
}
