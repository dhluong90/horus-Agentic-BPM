package io.github.dhluong90.bpm.rules.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.rules.api.DecisionInput;
import io.github.dhluong90.bpm.rules.api.DecisionResult;
import io.github.dhluong90.bpm.rules.api.ImportRequest;
import io.github.dhluong90.bpm.rules.api.InputInvalidException;
import io.github.dhluong90.bpm.rules.api.Money;
import io.github.dhluong90.bpm.rules.api.PermissionDeniedException;
import io.github.dhluong90.bpm.rules.api.SimulationResult;
import io.github.dhluong90.bpm.rules.api.VersionNotFoundException;
import io.github.dhluong90.bpm.rules.api.VersionStatus;
import io.github.dhluong90.bpm.rules.api.WriteOptions;
import io.github.dhluong90.bpm.rules.internal.json.EngineValues;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Simulation of a draft over sample inputs (RLE-016): the card fee table v1 is in force, and its draft v2 lowers the
 * default fee from 50,000 VND to 30,000 VND. Five customers are run through both.
 */
class SimulationTest {

    static final Set<String> SIMULATOR = Set.of("rules:simulate");

    final InMemoryStores stores = new InMemoryStores();
    final TestCaller caller = new TestCaller("bank-a", "alice");
    final RuleEngines engines = RuleEngines.create(
            new EngineContext(caller, caller, Clock.fixed(Instant.parse("2026-12-15T02:00:00Z"), ZoneOffset.UTC),
                    stores, stores, EngineSettings.defaults()),
            stores, stores, stores);

    /** The default fee goes down: a customer who falls through to R3 pays 30,000 VND instead of 50,000 VND. */
    static byte[] cardFeeV2() {
        return new String(CardFee.yaml(), StandardCharsets.UTF_8).replace("fee: \"50000 VND\"", "fee: \"30000 VND\"")
                .getBytes(StandardCharsets.UTF_8);
    }

    /** GOLD with 5 transactions, SILVER with 4, SILVER with 3, SILVER with 0 and STANDARD with 1. */
    static List<DecisionInput> fiveCustomers() {
        return List.of(
                DecisionInput.of(Map.of("tier", "GOLD", "txCount", 5)),
                DecisionInput.of(Map.of("tier", "SILVER", "txCount", 4)),
                DecisionInput.of(Map.of("tier", "SILVER", "txCount", 3)),
                DecisionInput.of(Map.of("tier", "SILVER", "txCount", 0)),
                DecisionInput.of(Map.of("tier", "STANDARD", "txCount", 1)));
    }

    @BeforeEach
    void cardFeeV1InForceAndV2Drafted() {
        caller.become("bank-a", "alice");
        engines.versionService().importRuleSet(ImportRequest.yaml(CardFee.yaml()));
        engines.versionService().requestPublish("card-fee", 1, WriteOptions.none());
        caller.become("bank-a", "bob");
        engines.versionService().approve("card-fee", 1, new WriteOptions(null, 1L, null));
        caller.become("bank-a", "alice");
        engines.versionService().importVersion("card-fee", ImportRequest.yaml(cardFeeV2()));
    }

    @Test
    void RLE_016_sample_simulation_leaves_decision_log_untouched() {
        // Given
        caller.become("bank-a", "pricing-approver", SIMULATOR);
        int outbox = stores.outbox.size();
        int history = stores.history.size();

        // When
        SimulationResult result = engines.ruleEngine().simulate("card-fee", 2, fiveCustomers());

        // Then
        assertThat(result.version()).isEqualTo(2);
        assertThat(result.status()).isEqualTo(VersionStatus.DRAFT);
        assertThat(result.baselineVersion()).isEqualTo(1);
        assertThat(result.evaluatedAt()).isEqualTo(Instant.parse("2026-12-15T02:00:00Z"));
        assertThat(result.samples()).extracting(SimulationResult.Sample::changed)
                .containsExactly(false, false, false, true, true);
        assertThat(result.changedCount()).isEqualTo(2);
        SimulationResult.Sample silverIdle = result.samples().get(3);
        assertThat(silverIdle.simulated()).isEqualTo(new DecisionResult.Matched(List.of("R3"),
                Map.of("fee", Money.ofMinor("VND", 30_000))));
        assertThat(silverIdle.baseline()).isEqualTo(new DecisionResult.Matched(List.of("R3"),
                Map.of("fee", Money.ofMinor("VND", 50_000))));
        assertThat(result.samples().get(0).simulated()).isEqualTo(new DecisionResult.Matched(List.of("R1"),
                Map.of("fee", Money.ofMinor("VND", 0))));
        assertThat(stores.decisions).isEmpty();
        assertThat(stores.idempotency).isEmpty();
        assertThat(stores.outbox).hasSize(outbox);
        assertThat(stores.history).hasSize(history);
    }

    @Test
    void TR_DET_01_simulating_the_same_draft_twice_gives_identical_responses() {
        // Given
        caller.become("bank-a", "pricing-approver", SIMULATOR);

        // When
        String first = EngineValues.write(EngineValues.simulation(engines.ruleEngine().simulate("card-fee", 2, fiveCustomers())));
        String second = EngineValues.write(EngineValues.simulation(engines.ruleEngine().simulate("card-fee", 2, fiveCustomers())));

        // Then
        assertThat(second).isEqualTo(first);
        assertThat(first).contains("\"changedCount\":2", "\"baselineVersion\":1");
    }

    @Test
    void RLE_016_caller_without_rules_simulate_gets_403_and_nothing_is_evaluated() {
        // Given an author and approver who holds every permission but rules:simulate
        caller.become("bank-a", "alice");

        // When / Then
        assertThatThrownBy(() -> engines.ruleEngine().simulate("card-fee", 2, fiveCustomers()))
                .isInstanceOf(PermissionDeniedException.class)
                .extracting(e -> ((PermissionDeniedException) e).code()).isEqualTo("PERMISSION_DENIED");
        assertThat(stores.decisions).isEmpty();
    }

    @Test
    void RLE_016_a_version_of_another_tenant_is_404_version_not_found() {
        // Given card-fee v2 exists only in bank-a
        caller.become("tenant-b", "carol", SIMULATOR);

        // When / Then: not found, the same answer as for a version bank-a does not have
        assertThatThrownBy(() -> engines.ruleEngine().simulate("card-fee", 2, fiveCustomers()))
                .isInstanceOf(VersionNotFoundException.class)
                .extracting(e -> ((VersionNotFoundException) e).code()).isEqualTo("VERSION_NOT_FOUND");
        caller.become("bank-a", "pricing-approver", SIMULATOR);
        assertThatThrownBy(() -> engines.ruleEngine().simulate("card-fee", 9, fiveCustomers()))
                .isInstanceOf(VersionNotFoundException.class);
        assertThatThrownBy(() -> engines.ruleEngine().simulate("no-such-fee", 1, fiveCustomers()))
                .isInstanceOf(VersionNotFoundException.class);
    }

    @Test
    void RLE_016_no_samples_or_more_than_1000_are_input_invalid() {
        // Given
        caller.become("bank-a", "pricing-approver", SIMULATOR);
        List<DecisionInput> tooMany = new ArrayList<>(Collections.nCopies(EngineDefaults.SIMULATION_MAX_SAMPLES + 1,
                DecisionInput.of(Map.of("tier", "GOLD", "txCount", 1))));

        // When / Then
        assertThatThrownBy(() -> engines.ruleEngine().simulate("card-fee", 2, List.of()))
                .isInstanceOf(InputInvalidException.class).hasMessageContaining("1 to 1000 input sets");
        assertThatThrownBy(() -> engines.ruleEngine().simulate("card-fee", 2, tooMany))
                .isInstanceOf(InputInvalidException.class).hasMessageContaining("1 to 1000");
        assertThat(engines.ruleEngine().simulate("card-fee", 2, tooMany.subList(0, 1000)).samples()).hasSize(1000);
    }

    @Test
    void RLE_016_a_host_that_lowers_simulation_max_inputs_gets_that_bound() {
        // Given a host that allows 3 samples per simulation
        RuleEngines lowered = RuleEngines.create(
                new EngineContext(caller, caller, Clock.fixed(Instant.parse("2026-12-15T02:00:00Z"), ZoneOffset.UTC),
                        stores, stores, EngineSettings.defaults().withSimulationMaxInputs(3)),
                stores, stores, stores);
        caller.become("bank-a", "pricing-approver", SIMULATOR);

        // When / Then
        assertThatThrownBy(() -> lowered.ruleEngine().simulate("card-fee", 2, fiveCustomers()))
                .isInstanceOf(InputInvalidException.class).hasMessageContaining("1 to 3 input sets");
        assertThat(lowered.ruleEngine().simulate("card-fee", 2, fiveCustomers().subList(0, 3)).samples()).hasSize(3);
        assertThatThrownBy(() -> EngineSettings.defaults().withSimulationMaxInputs(1001))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("horus.bpm.rules.simulation.max-inputs");
    }

    @Test
    void RLE_016_an_undeclared_or_unbounded_input_names_its_sample() {
        // Given
        caller.become("bank-a", "pricing-approver", SIMULATOR);
        List<DecisionInput> undeclared = List.of(DecisionInput.of(Map.of("tier", "GOLD", "txCount", 1)),
                DecisionInput.of(Map.of("tier", "GOLD", "txCount", 1, "segment", "SME")));
        List<DecisionInput> unbounded = List.of(DecisionInput.of(Map.of("tier", "GOLD",
                "txCount", new BigDecimal("1E+999999"))));

        // When / Then
        assertThatThrownBy(() -> engines.ruleEngine().simulate("card-fee", 2, undeclared))
                .isInstanceOf(InputInvalidException.class).hasMessageStartingWith("samples[1]: ")
                .hasMessageContaining("segment");
        assertThatThrownBy(() -> engines.ruleEngine().simulate("card-fee", 2, unbounded))
                .isInstanceOf(InputInvalidException.class).hasMessageStartingWith("samples[0]: ");
    }

    @Test
    void RLE_016_with_nothing_effective_every_sample_is_changed_and_has_no_baseline() {
        // Given a new rule set with only a draft
        caller.become("bank-a", "alice");
        engines.versionService().importRuleSet(ImportRequest.yaml(new String(cardFeeV2(), StandardCharsets.UTF_8)
                .replace("ruleSet: card-fee", "ruleSet: card-fee-new").getBytes(StandardCharsets.UTF_8)));
        caller.become("bank-a", "pricing-approver", SIMULATOR);

        // When
        SimulationResult result = engines.ruleEngine().simulate("card-fee-new", 1, fiveCustomers());

        // Then
        assertThat(result.baselineVersion()).isNull();
        assertThat(result.samples()).allSatisfy(sample -> {
            assertThat(sample.baseline()).isNull();
            assertThat(sample.changed()).isTrue();
        });
    }

    @Test
    void RLE_016_an_approver_simulates_the_version_pending_approval() {
        // Given alice asks for v2 to be published
        engines.versionService().requestPublish("card-fee", 2, WriteOptions.none());
        caller.become("bank-a", "bob", Set.of("rules:approve", "rules:simulate"));

        // When
        SimulationResult result = engines.ruleEngine().simulate("card-fee", 2, fiveCustomers());

        // Then
        assertThat(result.status()).isEqualTo(VersionStatus.PENDING_APPROVAL);
        assertThat(result.changedCount()).isEqualTo(2);
        assertThat(stores.decisions).isEmpty();
    }

    @Test
    void RLE_016_after_v2_is_approved_v1_is_retired_and_still_simulates_against_v2() {
        // Given bob approves v2 at once, which retires v1 as superseded (RLE-027)
        engines.versionService().requestPublish("card-fee", 2, WriteOptions.none());
        caller.become("bank-a", "bob");
        engines.versionService().approve("card-fee", 2, new WriteOptions(null, 1L, null));
        caller.become("bank-a", "bob", SIMULATOR);

        // When the retired v1 is simulated
        SimulationResult result = engines.ruleEngine().simulate("card-fee", 1, fiveCustomers());

        // Then the baseline is v2, and the two customers on the default fee go back from 30,000 to 50,000 VND
        assertThat(result.status()).isEqualTo(VersionStatus.RETIRED);
        assertThat(result.baselineVersion()).isEqualTo(2);
        assertThat(result.samples()).extracting(SimulationResult.Sample::changed)
                .containsExactly(false, false, false, true, true);
        assertThat(result.samples().get(3).simulated()).isEqualTo(new DecisionResult.Matched(List.of("R3"),
                Map.of("fee", Money.ofMinor("VND", 50_000))));
        assertThat(stores.decisions).isEmpty();
    }

    @Test
    void RLE_016_equal_values_written_differently_are_not_a_change_and_a_different_row_alone_is_not_either() {
        // Given
        DecisionResult.Outcome whole = new DecisionResult.Matched(List.of("R2"), Map.of("rate", new BigDecimal("7.5")));
        DecisionResult.Outcome padded = new DecisionResult.Matched(List.of("R4"), Map.of("rate", new BigDecimal("7.50")));
        DecisionResult.Outcome higher = new DecisionResult.Matched(List.of("R2"), Map.of("rate", new BigDecimal("7.75")));
        DecisionResult.Outcome none = new DecisionResult.NoMatch(Map.of());
        DecisionResult.Outcome overlap = new DecisionResult.Failed("UNIQUE_HIT_VIOLATION", "R2 and R4", List.of("R2", "R4"));
        DecisionResult.Outcome error = new DecisionResult.Failed("EVALUATION_ERROR", "division by zero");

        // When / Then
        assertThat(DefaultRuleEngine.changed(whole, padded)).isFalse();
        assertThat(DefaultRuleEngine.changed(whole, higher)).isTrue();
        assertThat(DefaultRuleEngine.changed(whole, none)).isTrue();
        assertThat(DefaultRuleEngine.changed(none, new DecisionResult.NoMatch(Map.of()))).isFalse();
        assertThat(DefaultRuleEngine.changed(overlap, error)).isTrue();
        assertThat(DefaultRuleEngine.changed(error, new DecisionResult.Failed("EVALUATION_ERROR", "other text"))).isFalse();
    }
}
