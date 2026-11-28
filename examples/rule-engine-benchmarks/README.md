# Rule Engine benchmarks

JMH benchmarks of the Rule Engine evaluator. Built and run in CI on every build (job `benchmark`), never published.

| Benchmark | Requirement | Budget |
|---|---|---|
| `EvaluationBenchmark.evaluateFirstHit200Rows` | `TR-PERF-01`: one evaluation of a cached 200-row `first` table, input reaching the default row, no decision log write | p99 under 100 us, fails the build |
| `CompileBenchmark.readAndCompile1000Rows` | `TR-PERF-02`: reading and compiling a 1,000-row table from YAML | p99 under 500 ms, reported |
| `TracingOverheadBenchmark.*` | `TR-OBS-03`: the same evaluation, and one Process Engine step span, with tracing off, with the sampler off and with every span recorded | reported, no budget; figures in the wiki page Tracing |

## Run

From the repository root:

```bash
mvn -pl examples/rule-engine-benchmarks -am install -Dcockpit.bundle.skip=true
cd examples/rule-engine-benchmarks
mvn -q org.apache.maven.plugins:maven-dependency-plugin:3.11.0:build-classpath \
  -Dmdep.includeScope=runtime -Dmdep.outputFile=target/classpath.txt
java -cp "target/classes:$(cat target/classpath.txt)" org.openjdk.jmh.Main -rf json -rff target/jmh-result.json
python3 ../../tools/benchmark-report.py target/jmh-result.json
```

The run takes about 30 seconds. `tools/benchmark-report.py` prints a Markdown table with p50, p90, p99 and p99.9 per benchmark and exits 1 when a gating budget is missed.

JMH is GPL-2.0 with the Classpath Exception. It is a build tool here and never reaches a published artifact.
