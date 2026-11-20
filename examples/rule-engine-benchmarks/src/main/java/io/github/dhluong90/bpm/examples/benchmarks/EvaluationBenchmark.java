package io.github.dhluong90.bpm.examples.benchmarks;

import io.github.dhluong90.bpm.rules.api.DecisionInput;
import io.github.dhluong90.bpm.rules.api.DecisionResult;
import io.github.dhluong90.bpm.rules.api.SourceFormat;
import io.github.dhluong90.bpm.rules.internal.eval.CompiledRuleSet;
import io.github.dhluong90.bpm.rules.internal.read.RuleSetReader;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * TR-PERF-01: one in-process evaluation of a cached 200-row {@code first} table, excluding the decision log write.
 * The input matches only the default row, so every row is read: the slowest path of the table. Sampled per call, so
 * the report has the p99 the requirement bounds (under 100 us after warm-up). Warm-up runs far more than the 10,000
 * evaluations of the published conditions (TR-PERF-13).
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(1)
public class EvaluationBenchmark {

    /** Rows of the TR-PERF-01 table. */
    public static final int ROWS = 200;

    CompiledRuleSet table;
    DecisionInput classicNoTransactions;

    @Setup
    public void compile() {
        table = compileFirstHit(ROWS);
        classicNoTransactions = DecisionInput.of(Map.of("tier", "CLASSIC", "txCount", 0));
    }

    @Benchmark
    public DecisionResult.Outcome evaluateFirstHit200Rows() {
        return table.evaluate(classicNoTransactions);
    }

    /**
     * Reads and compiles a {@link FeeTables#firstHit} table, as an import does.
     *
     * @param rows the number of rows
     * @return the compiled table
     */
    public static CompiledRuleSet compileFirstHit(int rows) {
        return CompiledRuleSet.compile(RuleSetReader.read(SourceFormat.YAML, FeeTables.firstHit("card-fee", rows))
                .model());
    }
}
