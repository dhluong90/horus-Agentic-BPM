package io.github.dhluong90.bpm.rules.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dhluong90.bpm.expr.internal.EvaluationLimits;
import io.github.dhluong90.bpm.rules.api.DecisionInput;
import io.github.dhluong90.bpm.rules.api.DecisionResult;
import io.github.dhluong90.bpm.rules.api.ImportRequest;
import io.github.dhluong90.bpm.rules.api.WriteOptions;
import io.github.dhluong90.bpm.rules.internal.store.DecisionRecord;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * RLE-233: a decision logs the limits it ran with and whether it hit one, and a replay uses those stored values and
 * the stored outcome, never the limits of the replaying host and never its wall clock.
 */
class ReplayStoredBudgetTest {

    static final Instant NOW = Instant.parse("2027-01-31T22:00:00Z");
    static final Set<String> TELLER = Set.of("rules:evaluate", "rules:read", "rules:log:read");

    final InMemoryStores stores = new InMemoryStores();
    final TestCaller caller = new TestCaller("bank-a", "alice");

    RuleEngines engines(EngineSettings settings) {
        return RuleEngines.create(new EngineContext(caller, caller, Clock.fixed(NOW, ZoneOffset.UTC), stores, stores,
                settings), stores, stores, stores);
    }

    EngineSettings withLimits(EvaluationLimits limits) {
        return EngineSettings.defaults().withRequireContentHash(false).withEvaluationLimits(limits);
    }

    void publishLoyaltyScore() {
        RuleEngines authoring = engines(withLimits(EvaluationLimits.defaults()));
        caller.become("bank-a", "alice");
        authoring.versionService().importRuleSet(ImportRequest.yaml(
                EvaluationStepBudgetSettingTest.LOYALTY_SCORE.getBytes(StandardCharsets.UTF_8)));
        authoring.versionService().requestPublish("loyalty-score", 1, WriteOptions.none());
        caller.become("bank-a", "bob");
        authoring.versionService().approve("loyalty-score", 1, new WriteOptions(null, 1L, null));
        caller.become("bank-a", "svc", TELLER);
    }

    @Test
    void RLE_233_a_decision_logs_the_step_budget_and_time_backstop_it_ran_with() {
        publishLoyaltyScore();

        DecisionResult result = engines(withLimits(new EvaluationLimits(500, 7_000))).ruleEngine()
                .evaluate("loyalty-score", DecisionInput.of(Map.of("tier", "GOLD")));

        DecisionRecord row = stores.decisions.get(0);
        assertThat(result.outcome()).isInstanceOf(DecisionResult.Matched.class);
        assertThat(row.stepBudget()).isEqualTo(500);
        assertThat(row.timeoutMillis()).isEqualTo(7_000);
        assertThat(row.budgetLimit()).isNull();
    }

    @Test
    void RLE_233_a_step_failure_replays_as_a_step_failure_on_a_host_with_a_larger_budget() {
        // Given a decision that failed on a step budget of 10
        publishLoyaltyScore();
        DecisionResult failed = engines(withLimits(new EvaluationLimits(10, 10_000))).ruleEngine()
                .evaluate("loyalty-score", DecisionInput.of(Map.of("tier", "GOLD")));

        // When it is replayed on a host whose budget would let it pass
        DecisionResult replayed = engines(withLimits(EvaluationLimits.defaults())).ruleEngine()
                .replay(failed.decisionId());

        // Then the stored budget of 10 decides: the same failure on STEPS
        assertThat(replayed.outcome()).isInstanceOfSatisfying(DecisionResult.Failed.class, f -> {
            assertThat(f.detail()).startsWith("EVALUATION_BUDGET_EXCEEDED").contains("10");
            assertThat(f.limit()).isEqualTo("STEPS");
        });
    }

    @Test
    void RLE_233_a_decision_that_passed_replays_as_passed_on_a_host_with_a_smaller_budget() {
        publishLoyaltyScore();
        DecisionResult passed = engines(withLimits(EvaluationLimits.defaults())).ruleEngine()
                .evaluate("loyalty-score", DecisionInput.of(Map.of("tier", "GOLD")));

        DecisionResult replayed = engines(withLimits(new EvaluationLimits(10, 10_000))).ruleEngine()
                .replay(passed.decisionId());

        assertThat(replayed.outcome()).isInstanceOfSatisfying(DecisionResult.Matched.class,
                m -> assertThat(m.outputs().get("points")).hasToString("12"));
    }

    @Test
    void RLE_233_a_decision_the_clock_stopped_replays_its_stored_failure_not_the_replaying_machines_clock() {
        // Given a decision the time backstop stopped: the row says TIME and the 5 ms it ran with, although the table
        // is small enough to pass on any machine
        publishLoyaltyScore();
        DecisionResult first = engines(withLimits(EvaluationLimits.defaults())).ruleEngine()
                .evaluate("loyalty-score", DecisionInput.of(Map.of("tier", "GOLD")));
        DecisionRecord ran = stores.decisions.get(0);
        stores.decisions.set(0, new DecisionRecord(ran.tenantId(), ran.id(), ran.createdAt(), ran.ruleSet(),
                ran.version(), ran.hitPolicy(), "EVALUATION_ERROR", "EVALUATION_BUDGET_EXCEEDED", ran.maskedInputsJson(),
                null, "[]", ran.callerId(), ran.correlationId(), ran.evalClock(), ran.durationMicros(), ran.pinned(),
                ran.onBehalfOf(), ran.functionVersions(), "TIME", 100_000, 5));

        // When it is replayed, on a host with any limits
        DecisionResult replayed = engines(withLimits(EvaluationLimits.defaults())).ruleEngine()
                .replay(first.decisionId());

        // Then the stored outcome comes back: TIME and the stored 5 ms, the same on every replay
        assertThat(replayed.outcome()).isInstanceOfSatisfying(DecisionResult.Failed.class, f -> {
            assertThat(f.detail()).isEqualTo("EVALUATION_BUDGET_EXCEEDED: the decision ran longer than 5 ms");
            assertThat(f.limit()).isEqualTo("TIME");
        });
        assertThat(engines(withLimits(EvaluationLimits.defaults())).ruleEngine().replay(first.decisionId()).outcome())
                .isEqualTo(replayed.outcome());
    }

    @Test
    void RLE_233_a_replay_never_checks_the_clock_even_with_the_smallest_backstop() {
        // The recorded backstop is 1 ms; a replay of a decision that passed must still pass: the check is off
        publishLoyaltyScore();
        DecisionResult passed = engines(withLimits(new EvaluationLimits(100_000, 1))).ruleEngine()
                .evaluate("loyalty-score", DecisionInput.of(Map.of("tier", "GOLD")));
        assertThat(stores.decisions.get(0).timeoutMillis()).isEqualTo(1);

        assertThat(engines(withLimits(EvaluationLimits.defaults())).ruleEngine().replay(passed.decisionId()).outcome())
                .isInstanceOf(DecisionResult.Matched.class);
        assertThat(new EvaluationLimits(10, 1).deterministic().timed()).isFalse();
    }
}
