package io.github.dhluong90.bpm.rules.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.rules.api.DecisionInput;
import io.github.dhluong90.bpm.rules.api.DecisionResult;
import io.github.dhluong90.bpm.rules.api.ImportRequest;
import io.github.dhluong90.bpm.rules.api.SimulationResult;
import io.github.dhluong90.bpm.expr.internal.RegexLimits;
import io.github.dhluong90.bpm.rules.internal.json.EngineValues;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The regular expression reads of one request of many decisions are bounded together
 * ({@code evaluation.regex-max-reads-per-request}, RLE-007, TR-SEC-02). The example is a compliance analyst
 * simulating a remittance screening draft over a batch of payment references: two rows each search a long reference,
 * so every long reference costs two rows of reads.
 */
class RequestRegexBudgetTest {

    static final Set<String> SIMULATOR = Set.of("rules:simulate");

    /** A short, ordinary reference: it reads a handful of characters and runs to the catch-all. */
    static final String SHORT_REFERENCE = "FT20270115";

    /** Characters one row's {@code matches} reads on the long reference. */
    static final long PER_ROW = RegexBoundsTest.readsOf("[0-9]*K1", RegexBoundsTest.LONG_REFERENCE);

    final InMemoryStores stores = new InMemoryStores();
    final TestCaller caller = new TestCaller("bank-a", "alice");

    /** Engines with the default per-match and per-decision budgets and the given per-request budget. */
    RuleEngines engines(long perRequest) {
        RegexLimits limits = new RegexLimits(1000, 4096, RegexLimits.MAX_READS, RegexLimits.MAX_READS_PER_DECISION,
                perRequest);
        RuleEngines engines = RuleEngines.create(new EngineContext(caller, caller,
                Clock.fixed(Instant.parse("2027-01-15T02:00:00Z"), ZoneOffset.UTC), stores, stores,
                EngineSettings.defaults().withRequireContentHash(false).withRegexLimits(limits)), stores, stores, stores);
        caller.become("bank-a", "alice");
        engines.versionService().importRuleSet(ImportRequest.yaml(
                RegexBoundsTest.screening(2).getBytes(StandardCharsets.UTF_8)));
        caller.become("bank-a", "compliance-analyst", SIMULATOR);
        return engines;
    }

    /** Long, long, long, short, long: the request budget below holds the first two and not the third. */
    static List<DecisionInput> batch() {
        return List.of(reference(RegexBoundsTest.LONG_REFERENCE), reference(RegexBoundsTest.LONG_REFERENCE),
                reference(RegexBoundsTest.LONG_REFERENCE), reference(SHORT_REFERENCE),
                reference(RegexBoundsTest.LONG_REFERENCE));
    }

    static DecisionInput reference(String reference) {
        return DecisionInput.of(Map.of("reference", reference));
    }

    @Test
    void RLE_007_a_request_stops_at_the_first_item_over_its_budget_and_runs_no_item_after_it() {
        // Given: 2 long references read 4 rows; the budget holds 5 rows, so the third long reference runs out.
        RuleEngines engines = engines(5 * PER_ROW);

        // When
        SimulationResult result = engines.ruleEngine().simulate("remittance-screening", 1, batch());

        // Then
        List<SimulationResult.Sample> samples = result.samples();
        assertThat(samples).hasSize(5);
        assertThat(samples.subList(0, 2)).allSatisfy(sample -> assertThat(sample.simulated())
                .isEqualTo(new DecisionResult.Matched(List.of("CLEAR"), Map.of("route", "CLEAR"))));
        assertThat(samples.get(2).simulated()).isInstanceOfSatisfying(DecisionResult.Failed.class, failed -> {
            assertThat(failed.code()).isEqualTo("EVALUATION_ERROR");
            assertThat(failed.detail()).startsWith("REGEX_TIMEOUT")
                    .contains("evaluation.regex-max-reads-per-request", String.valueOf(5 * PER_ROW))
                    .doesNotContain(RegexBoundsTest.LONG_REFERENCE);
        });
        // The short reference after it would cost almost nothing, and still does not run.
        assertThat(samples.subList(3, 5)).allSatisfy(sample -> assertThat(sample.simulated())
                .isInstanceOfSatisfying(DecisionResult.Failed.class, failed -> {
                    assertThat(failed.code()).isEqualTo("EVALUATION_ERROR");
                    assertThat(failed.detail()).startsWith("REGEX_TIMEOUT: not evaluated")
                            .contains("evaluation.regex-max-reads-per-request");
                }));
        assertThat(stores.decisions).isEmpty();
    }

    @Test
    void RLE_007_a_request_under_its_budget_gives_the_same_results_as_before() {
        // Given: the same batch under the default budget of 50,000,000 characters and under an exact fit.
        String underDefault = EngineValues.write(EngineValues.simulation(
                engines(RegexLimits.MAX_READS_PER_REQUEST).ruleEngine().simulate("remittance-screening", 1, batch())));
        long exact = 8 * PER_ROW + RegexBoundsTest.readsOf("[0-9]*K1", SHORT_REFERENCE)
                + RegexBoundsTest.readsOf("[0-9]*K2", SHORT_REFERENCE);

        // When
        SimulationResult atExactFit = new RequestRegexBudgetTest().engines(exact).ruleEngine()
                .simulate("remittance-screening", 1, batch());

        // Then
        assertThat(atExactFit.samples()).allSatisfy(sample -> assertThat(sample.simulated())
                .isEqualTo(new DecisionResult.Matched(List.of("CLEAR"), Map.of("route", "CLEAR"))));
        assertThat(EngineValues.write(EngineValues.simulation(atExactFit))).isEqualTo(underDefault);
    }

    @Test
    void RLE_007_the_same_request_stops_at_the_same_item_on_every_replay() {
        // Given
        RuleEngines engines = engines(5 * PER_ROW);

        // When
        List<String> runs = java.util.stream.IntStream.range(0, 5)
                .mapToObj(i -> EngineValues.write(EngineValues.simulation(
                        engines.ruleEngine().simulate("remittance-screening", 1, batch()))))
                .toList();

        // Then
        assertThat(runs).containsOnly(runs.getFirst());
        SimulationResult first = engines.ruleEngine().simulate("remittance-screening", 1, batch());
        assertThat(first.samples()).extracting(sample -> sample.simulated() instanceof DecisionResult.Failed)
                .containsExactly(false, false, true, true, true);
    }

    @Test
    void RLE_007_the_request_budget_is_1_to_five_billion_and_defaults_to_fifty_million() {
        assertThat(RegexLimits.defaults().maxReadsPerRequest()).isEqualTo(50_000_000L);
        assertThat(EngineSettings.defaults().withRequireContentHash(false).regexLimits().maxReadsPerRequest()).isEqualTo(50_000_000L);
        assertThat(new RegexLimits(50, 10, 1000, 1000, 5_000_000_000L).maxReadsPerRequest())
                .isEqualTo(5_000_000_000L);
        assertThatThrownBy(() -> new RegexLimits(50, 10, 1000, 1000, 0))
                .hasMessageContaining("regex-max-reads-per-request");
        assertThatThrownBy(() -> new RegexLimits(50, 10, 1000, 1000, 5_000_000_001L))
                .hasMessageContaining("regex-max-reads-per-request");
    }
}
