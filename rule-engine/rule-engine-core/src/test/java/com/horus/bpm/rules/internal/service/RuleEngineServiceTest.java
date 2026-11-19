package com.horus.bpm.rules.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.horus.bpm.rules.api.DecisionInput;
import com.horus.bpm.rules.api.DecisionRequest;
import com.horus.bpm.rules.api.DecisionResult;
import com.horus.bpm.rules.api.IdempotencyKeyReuseException;
import com.horus.bpm.rules.api.ImportRequest;
import com.horus.bpm.rules.api.InputInvalidException;
import com.horus.bpm.rules.api.Money;
import com.horus.bpm.rules.api.NoEffectiveVersionException;
import com.horus.bpm.rules.api.RuleSetNotFoundException;
import com.horus.bpm.rules.api.WriteOptions;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RuleEngineServiceTest {

    final InMemoryStores stores = new InMemoryStores();
    final TestCaller caller = new TestCaller("bank-a", "alice");
    final RuleEngines engines = RuleEngines.create(
            new EngineContext(caller, caller, Clock.fixed(Instant.parse("2026-12-15T02:00:00Z"), ZoneOffset.UTC),
                    stores, stores, EngineSettings.defaults()),
            stores, stores, stores);

    void publishCardFee() {
        caller.become("bank-a", "alice");
        engines.versionService().importRuleSet(ImportRequest.yaml(CardFee.yaml()));
        engines.versionService().requestPublish("card-fee", 1, WriteOptions.none());
        caller.become("bank-a", "bob");
        engines.versionService().approve("card-fee", 1, WriteOptions.none());
        caller.become("bank-a", "teller-01");
    }

    @Test
    void RLE_001_first_hit_policy_returns_top_matching_rule() {
        // Given
        publishCardFee();

        // When
        DecisionResult result = engines.ruleEngine().evaluate("card-fee", DecisionInput.of(Map.of("tier", "GOLD", "txCount", 5)));

        // Then
        assertThat(result.isError()).isFalse();
        assertThat(result.matchedRuleIds()).containsExactly("R1");
        assertThat(result.outputs()).containsEntry("fee", Money.ofMinor("VND", 0));
        assertThat(result.version()).isEqualTo(1);
        assertThat(stores.decisions).singleElement().satisfies(row -> {
            assertThat(row.tenantId()).isEqualTo("bank-a");
            assertThat(row.outcome()).isEqualTo("OK");
            assertThat(row.matchedRuleIdsJson()).isEqualTo("[\"R1\"]");
            assertThat(row.outputsJson()).isEqualTo("{\"fee\":{\"currency\":\"VND\",\"amount\":0}}");
            assertThat(row.callerId()).isEqualTo("teller-01");
            assertThat(row.id()).isEqualTo(result.decisionId());
        });
    }

    @Test
    void lower_rows_match_when_the_top_row_does_not() {
        publishCardFee();

        DecisionResult silver = engines.ruleEngine().evaluate("card-fee", DecisionInput.of(Map.of("tier", "SILVER", "txCount", 3)));
        DecisionResult idle = engines.ruleEngine().evaluate("card-fee", DecisionInput.of(Map.of("tier", "SILVER", "txCount", 0)));

        assertThat(silver.matchedRuleIds()).containsExactly("R2");
        assertThat(silver.outputs()).containsEntry("fee", Money.ofMinor("VND", 10_000));
        assertThat(idle.matchedRuleIds()).containsExactly("R3");
        assertThat(idle.outputs()).containsEntry("fee", Money.ofMinor("VND", 50_000));
    }

    @Test
    void RLE_014_evaluate_replays_the_logged_decision_for_the_same_key() {
        publishCardFee();
        DecisionRequest request = DecisionRequest.of("card-fee", DecisionInput.of(Map.of("tier", "GOLD", "txCount", 5)))
                .withIdempotencyKey("ev-1");

        DecisionResult first = engines.ruleEngine().evaluate(request);
        DecisionResult replay = engines.ruleEngine().evaluate(request);

        assertThat(replay).isEqualTo(first);
        assertThat(stores.decisions).hasSize(1);
        assertThatThrownBy(() -> engines.ruleEngine().evaluate(DecisionRequest.of("card-fee",
                DecisionInput.of(Map.of("tier", "CLASSIC", "txCount", 5))).withIdempotencyKey("ev-1")))
                .isInstanceOf(IdempotencyKeyReuseException.class);
    }

    @Test
    void unknown_rule_set_unpublished_rule_set_and_wrong_input_types_are_refused_without_a_decision_row() {
        assertThatThrownBy(() -> engines.ruleEngine().evaluate("card-fee", DecisionInput.of(Map.of())))
                .isInstanceOf(RuleSetNotFoundException.class);
        engines.versionService().importRuleSet(ImportRequest.yaml(CardFee.yaml()));
        assertThatThrownBy(() -> engines.ruleEngine().evaluate("card-fee", DecisionInput.of(Map.of())))
                .isInstanceOf(NoEffectiveVersionException.class);
        engines.versionService().requestPublish("card-fee", 1, WriteOptions.none());
        caller.become("bank-a", "bob");
        engines.versionService().approve("card-fee", 1, WriteOptions.none());
        assertThatThrownBy(() -> engines.ruleEngine().evaluate("card-fee", DecisionInput.of(Map.of("txCount", 2.5d))))
                .isInstanceOf(InputInvalidException.class);
        assertThat(stores.decisions).isEmpty();
    }

    @Test
    void another_tenant_cannot_evaluate_the_rule_set() {
        publishCardFee();
        caller.become("bank-b", "carol");

        assertThatThrownBy(() -> engines.ruleEngine().evaluate("card-fee", DecisionInput.of(Map.of("tier", "GOLD"))))
                .isInstanceOf(RuleSetNotFoundException.class);
    }
}
