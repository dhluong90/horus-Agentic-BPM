package io.github.dhluong90.bpm.rules.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.expr.internal.EvaluationLimits;
import io.github.dhluong90.bpm.rules.api.DecisionInput;
import io.github.dhluong90.bpm.rules.api.DecisionResult;
import io.github.dhluong90.bpm.rules.api.ImportRequest;
import io.github.dhluong90.bpm.rules.api.ValidationFailedException;
import io.github.dhluong90.bpm.rules.api.WriteOptions;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The host's evaluation step budget (RLE-082, #350): one engine serves a heavy decision under the default budget and
 * under {@code evaluation.step-budget: 10}. The starter reads the properties; here the settings they produce are put
 * in front of the engine, on the in-memory stores and a fixed clock.
 */
class EvaluationStepBudgetSettingTest {

    static final Instant NOW = Instant.parse("2027-01-31T22:00:00Z");

    /** A loyalty score of twelve one-point terms: about 23 expression nodes in one output cell. */
    static final String LOYALTY_SCORE = """
            schemaVersion: 1
            ruleSet: loyalty-score
            hitPolicy: first
            inputs:
              - { name: tier, type: string, mask: false }
            outputs:
              - { name: points, type: integer }
            rules:
              - { id: R1, when: {}, then: { points: "1 + 1 + 1 + 1 + 1 + 1 + 1 + 1 + 1 + 1 + 1 + 1" } }
            """;

    final InMemoryStores stores = new InMemoryStores();
    final TestCaller caller = new TestCaller("bank-a", "alice");

    RuleEngines engines(EngineSettings settings) {
        return RuleEngines.create(new EngineContext(caller, caller, Clock.fixed(NOW, ZoneOffset.UTC), stores, stores,
                settings), stores, stores, stores);
    }

    void publishWithDefaults() {
        RuleEngines authoring = engines(EngineSettings.defaults().withRequireContentHash(false));
        caller.become("bank-a", "alice");
        authoring.versionService().importRuleSet(ImportRequest.yaml(LOYALTY_SCORE.getBytes(StandardCharsets.UTF_8)));
        authoring.versionService().requestPublish("loyalty-score", 1, WriteOptions.none());
        caller.become("bank-a", "bob");
        authoring.versionService().approve("loyalty-score", 1, new WriteOptions(null, 1L, null));
        caller.become("bank-a", "svc", Set.of("rules:evaluate"));
    }

    @Test
    void RLE_082_a_host_with_step_budget_10_fails_a_heavy_decision_and_the_default_passes_it() {
        // Given loyalty-score v1 approved under the default budget
        publishWithDefaults();
        DecisionInput input = DecisionInput.of(Map.of("tier", "GOLD"));

        // When the host serves it with evaluation.step-budget 10, and with the default
        DecisionResult tight = engines(EngineSettings.defaults().withRequireContentHash(false).withEvaluationLimits(new EvaluationLimits(10, 10_000)))
                .ruleEngine().evaluate("loyalty-score", input);
        DecisionResult roomy = engines(EngineSettings.defaults().withRequireContentHash(false)).ruleEngine().evaluate("loyalty-score", input);

        // Then the tight host fails with EVALUATION_ERROR, reason EVALUATION_BUDGET_EXCEEDED on STEPS, logged with
        // that error_code, and the default host passes with 12 points
        assertThat(tight.outcome()).isInstanceOfSatisfying(DecisionResult.Failed.class, failed -> {
            assertThat(failed.code()).isEqualTo("EVALUATION_ERROR");
            assertThat(failed.detail()).startsWith("EVALUATION_BUDGET_EXCEEDED").contains("10");
            assertThat(failed.limit()).isEqualTo("STEPS");
        });
        assertThat(stores.decisions).hasSize(2);
        assertThat(stores.decisions.get(0).outcome()).isEqualTo("EVALUATION_ERROR");
        assertThat(stores.decisions.get(0).errorCode()).isEqualTo("EVALUATION_BUDGET_EXCEEDED");
        // RLE-229: the row keeps the limit the decision reached, and the read of it returns it; the passing one has none
        assertThat(stores.decisions.get(0).budgetLimit()).isEqualTo("STEPS");
        assertThat(stores.decisions.get(1).budgetLimit()).isNull();
        assertThat(DefaultDecisionLogService.view(stores.decisions.get(0)).limit()).isEqualTo("STEPS");
        assertThat(DefaultDecisionLogService.view(stores.decisions.get(1)).limit()).isNull();
        assertThat(roomy.outcome()).isInstanceOfSatisfying(DecisionResult.Matched.class,
                matched -> assertThat(matched.outputs().get("points")).hasToString("12"));
    }

    @Test
    void RLE_082_a_host_that_lowers_the_budget_also_refuses_such_a_table_at_import() {
        // Given a host with evaluation.step-budget 10
        RuleEngines tight = engines(EngineSettings.defaults().withRequireContentHash(false).withEvaluationLimits(new EvaluationLimits(10, 10_000)));
        caller.become("bank-a", "alice");

        // When alice imports the same table
        // Then the worst case is over the budget and the import is refused with the code
        assertThatThrownBy(() -> tight.versionService().importRuleSet(
                ImportRequest.yaml(LOYALTY_SCORE.getBytes(StandardCharsets.UTF_8))))
                .isInstanceOfSatisfying(ValidationFailedException.class, e -> assertThat(e.violations())
                        .anySatisfy(v -> assertThat(v.code()).isEqualTo("EVALUATION_BUDGET_EXCEEDED")));
    }

    @Test
    void RLE_083_the_limits_name_their_property_when_they_are_out_of_range() {
        assertThatThrownBy(() -> new EvaluationLimits(0, 10_000))
                .hasMessageContaining("horus.bpm.rules.evaluation.step-budget").hasMessageContaining("1000000");
        assertThatThrownBy(() -> new EvaluationLimits(1_000_001, 10_000))
                .hasMessageContaining("horus.bpm.rules.evaluation.step-budget");
        assertThatThrownBy(() -> new EvaluationLimits(100_000, 0))
                .hasMessageContaining("horus.bpm.rules.evaluation.timeout-ms").hasMessageContaining("30000");
        assertThatThrownBy(() -> new EvaluationLimits(100_000, 30_001))
                .hasMessageContaining("horus.bpm.rules.evaluation.timeout-ms");
        assertThat(new EvaluationLimits(1_000_000, 30_000)).isEqualTo(new EvaluationLimits(1_000_000, 30_000));
    }
}
