package io.github.dhluong90.bpm.examples.benchmarks;

import io.github.dhluong90.bpm.rules.api.SourceFormat;
import io.github.dhluong90.bpm.rules.internal.eval.CompiledRuleSet;
import io.github.dhluong90.bpm.rules.internal.read.RuleSetReader;
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
 * TR-PERF-02: reading and compiling a 1,000-row {@code first} table from YAML, as an import does (under 500 ms).
 * Reported, not gating.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 2, time = 2)
@Measurement(iterations = 3, time = 2)
@Fork(1)
public class CompileBenchmark {

    /** Rows of the TR-PERF-02 table. */
    public static final int ROWS = 1000;

    byte[] yaml;

    @Setup
    public void write() {
        yaml = FeeTables.firstHit("card-fee-large", ROWS);
    }

    @Benchmark
    public CompiledRuleSet readAndCompile1000Rows() {
        return CompiledRuleSet.compile(RuleSetReader.read(SourceFormat.YAML, yaml).model());
    }
}
