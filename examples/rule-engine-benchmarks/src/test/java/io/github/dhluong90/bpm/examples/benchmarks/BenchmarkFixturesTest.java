package io.github.dhluong90.bpm.examples.benchmarks;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dhluong90.bpm.expr.api.Money;
import io.github.dhluong90.bpm.rules.api.DecisionResult;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The benchmarks measure what the requirements state: a 200-row {@code first} table whose measured input reads every
 * row (TR-PERF-01), and a 1,000-row table that compiles (TR-PERF-02). Timing is the benchmark job's; these tests
 * only keep the fixtures honest.
 */
class BenchmarkFixturesTest {

    @Test
    void TR_PERF_01_the_benchmark_table_has_200_first_hit_rows_and_its_input_reaches_the_default_row() {
        // Given the benchmark state
        EvaluationBenchmark benchmark = new EvaluationBenchmark();
        benchmark.compile();

        // When the measured call runs once
        DecisionResult.Outcome outcome = benchmark.evaluateFirstHit200Rows();

        // Then a Classic card with no transaction falls through all 200 rows to the 50,000 VND default
        assertThat(rows(FeeTables.firstHit("card-fee", EvaluationBenchmark.ROWS))).isEqualTo(EvaluationBenchmark.ROWS);
        assertThat(outcome).isInstanceOf(DecisionResult.Matched.class);
        DecisionResult.Matched matched = (DecisionResult.Matched) outcome;
        assertThat(matched.ruleIds()).containsExactly("R200");
        assertThat(matched.outputs()).isEqualTo(Map.of("fee", Money.ofMinor("VND", 50000)));
    }

    @Test
    void TR_PERF_01_a_gold_card_with_150_transactions_stops_at_the_first_matching_row() {
        // Given the 200-row table
        EvaluationBenchmark benchmark = new EvaluationBenchmark();
        benchmark.compile();

        // When a Gold card with 150 transactions is priced
        DecisionResult.Outcome outcome = benchmark.table.evaluate(
                io.github.dhluong90.bpm.rules.api.DecisionInput.of(Map.of("tier", "GOLD", "txCount", 150)));

        // Then row 50 (at least 150 transactions) charges 5,000 VND
        assertThat(((DecisionResult.Matched) outcome).ruleIds()).containsExactly("R50");
        assertThat(outcome).isEqualTo(new DecisionResult.Matched(java.util.List.of("R50"),
                Map.of("fee", Money.ofMinor("VND", 5000))));
    }

    @Test
    void TR_PERF_02_the_1000_row_table_reads_and_compiles() {
        // Given the compile benchmark state
        CompileBenchmark benchmark = new CompileBenchmark();
        benchmark.write();

        // When the measured call runs once
        var compiled = benchmark.readAndCompile1000Rows();

        // Then the table of 1,000 rows compiled as a first-hit table
        assertThat(compiled.hitPolicy().name()).isEqualTo("FIRST");
        assertThat(rows(benchmark.yaml)).isEqualTo(CompileBenchmark.ROWS);
    }

    private static int rows(byte[] yaml) {
        return io.github.dhluong90.bpm.rules.internal.read.RuleSetReader.read(
                io.github.dhluong90.bpm.rules.api.SourceFormat.YAML, yaml).model().decision().table().rules().size();
    }
}
