package io.github.dhluong90.bpm.rules.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.expr.api.Money;
import io.github.dhluong90.bpm.rules.api.DecisionInput;
import io.github.dhluong90.bpm.rules.api.DecisionRequest;
import io.github.dhluong90.bpm.rules.api.DecisionResult;
import io.github.dhluong90.bpm.rules.api.ImportRequest;
import io.github.dhluong90.bpm.rules.api.InputInvalidException;
import io.github.dhluong90.bpm.rules.api.RuleSetNotFoundException;
import io.github.dhluong90.bpm.rules.api.WriteOptions;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * A request names the decision it evaluates (DN-08, #38): a rule set holds one decision, named after it, so only that
 * id is accepted until rule sets hold several decisions, and naming it changes nothing (RLE-097).
 */
class DecisionIdRequestTest {

    final InMemoryStores stores = new InMemoryStores();
    final TestCaller caller = new TestCaller("bank-a", "alice");
    final RuleEngines engines = RuleEngines.create(
            new EngineContext(caller, caller, Clock.fixed(Instant.parse("2026-12-15T02:00:00Z"), ZoneOffset.UTC),
                    stores, stores, EngineSettings.defaults().withRequireContentHash(false)),
            stores, stores, stores);
    final DecisionRequest gold = DecisionRequest.of("card-fee", DecisionInput.of(Map.of("tier", "GOLD", "txCount", 5)));

    void publishCardFee(String tenant) {
        caller.become(tenant, "alice");
        engines.versionService().importRuleSet(ImportRequest.yaml(CardFee.yaml()));
        long requested = engines.versionService().requestPublish("card-fee", 1, WriteOptions.none()).lockVersion();
        caller.become(tenant, "bob");
        engines.versionService().approve("card-fee", 1, new WriteOptions(null, requested, null));
        caller.become(tenant, "teller-01");
    }

    @Test
    void RLE_097_naming_the_rule_sets_own_decision_gives_the_same_result_as_leaving_it_out() {
        // Given card-fee v1 published in bank-a
        publishCardFee("bank-a");

        // When a Gold customer is evaluated without a decision id and with decision card-fee
        DecisionResult plain = engines.ruleEngine().evaluate(gold);
        DecisionResult named = engines.ruleEngine().evaluate(gold.withDecision("card-fee"));

        // Then both match R1 with a 0 VND fee, each logged once
        assertThat(named.matchedRuleIds()).isEqualTo(plain.matchedRuleIds()).containsExactly("R1");
        assertThat(named.outputs()).isEqualTo(plain.outputs()).containsEntry("fee", Money.ofMinor("VND", 0));
        assertThat(stores.decisions).hasSize(2);
    }

    @Test
    void RLE_097_the_decision_id_is_outside_the_idempotency_hash() {
        // Given card-fee v1 published, and a decision logged under key k-097 without a decision id
        publishCardFee("bank-a");
        DecisionResult first = engines.ruleEngine().evaluate(gold.withIdempotencyKey("k-097"));

        // When the same key is sent again naming decision card-fee
        DecisionResult replay = engines.ruleEngine().evaluate(gold.withIdempotencyKey("k-097").withDecision("card-fee"));

        // Then it is the same request: the logged decision is returned, no second row
        assertThat(replay.decisionId()).isEqualTo(first.decisionId());
        assertThat(stores.decisions).hasSize(1);
    }

    @Test
    void RLE_097_another_decision_id_is_input_invalid_naming_decision_and_logs_nothing() {
        // Given card-fee v1 published in bank-a
        publishCardFee("bank-a");

        // When the request names decision risk-adj, which card-fee does not hold
        assertThatThrownBy(() -> engines.ruleEngine().evaluate(gold.withDecision("risk-adj")))
                .isInstanceOf(InputInvalidException.class)
                .hasMessageContaining("decision must be the rule set's name or left out")
                .hasMessageContaining("stage S1")
                .hasMessageNotContaining("risk-adj");

        // Then no decision row is written
        assertThat(stores.decisions).isEmpty();
    }

    @Test
    void RLE_097_another_tenants_rule_set_is_not_found_whatever_the_decision_id_and_nothing_is_written() {
        // Given card-fee published in bank-a only, and a teller of bank-b
        publishCardFee("bank-a");
        caller.become("bank-b", "teller-02");

        // When bank-b evaluates card-fee naming its own decision
        assertThatThrownBy(() -> engines.ruleEngine().evaluate(gold.withDecision("card-fee")))
                .isInstanceOf(RuleSetNotFoundException.class);

        // Then nothing is written
        assertThat(stores.decisions).isEmpty();
    }

    @Test
    void RLE_097_the_older_constructors_leave_the_decision_out() {
        DecisionRequest four = new DecisionRequest("card-fee", gold.input(), "k-1", "c-1");
        DecisionRequest six = new DecisionRequest("card-fee", gold.input(), "k-1", "c-1", null, 2);

        assertThat(four.decision()).isNull();
        assertThat(six.decision()).isNull();
        assertThat(six.withDecision("card-fee").version()).isEqualTo(2);
        assertThat(six.withDecision("card-fee").withVersion(3).decision()).isEqualTo("card-fee");
    }
}
