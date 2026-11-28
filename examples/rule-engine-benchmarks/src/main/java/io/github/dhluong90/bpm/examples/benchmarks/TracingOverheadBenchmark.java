package io.github.dhluong90.bpm.examples.benchmarks;

import io.github.dhluong90.bpm.process.spi.ProcessTracing;
import io.github.dhluong90.bpm.rules.api.DecisionInput;
import io.github.dhluong90.bpm.rules.api.DecisionResult;
import io.github.dhluong90.bpm.rules.internal.eval.CompiledRuleSet;
import io.github.dhluong90.bpm.rules.internal.service.EngineTracing;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.otel.bridge.OtelCurrentTraceContext;
import io.micrometer.tracing.otel.bridge.OtelTracer;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import io.opentelemetry.sdk.common.CompletableResultCode;
import java.lang.reflect.Constructor;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
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
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

/**
 * What tracing costs (#114, TR-OBS-03): the same work with tracing off and on, on a real OpenTelemetry SDK tracer.
 * <ul>
 *   <li>{@code *Off}: no tracer bound. The engines call their no-op port, which is what a host without a tracer runs.</li>
 *   <li>{@code *Unsampled}: a tracer with the sampler off. This is the bulk of the traffic of a host at the default
 *       sampling of 10 percent: spans exist for parenting and links but nothing is recorded.</li>
 *   <li>{@code *Sampled}: every span recorded and queued for export, with an exporter that discards the batch, so the
 *       figure is the cost in the request thread and no network. Export is off by default in the example hosts.</li>
 * </ul>
 * {@code evaluate*} is one rule evaluation of the TR-PERF-01 table ({@link EvaluationBenchmark}) with the Rule Engine's
 * evaluation span around it, the way the engine wraps it. {@code step*} is one Process Engine step span: started,
 * tagged and ended, which is all a step adds to its own work. The two bindings are package-private classes of the
 * starters and are built by reflection, so the benchmark measures the shipped code.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(1)
public class TracingOverheadBenchmark {

    CompiledRuleSet table;
    DecisionInput input;
    DecisionResult result;

    EngineTracing.Hook unsampledRules;
    EngineTracing.Hook sampledRules;
    ProcessTracing unsampledSteps;
    ProcessTracing sampledSteps;
    ProcessTracing offSteps = ProcessTracing.NONE;

    private SdkTracerProvider unsampledProvider;
    private SdkTracerProvider sampledProvider;

    @Setup
    public void setUp() throws Exception {
        table = EvaluationBenchmark.compileFirstHit(EvaluationBenchmark.ROWS);
        input = DecisionInput.of(Map.of("tier", "CLASSIC", "txCount", 0));
        result = new DecisionResult("01KDQ0000000000000000BENCH", "card-fee", 1, Instant.parse("2027-01-26T00:00:00Z"),
                Instant.parse("2027-01-26T00:00:00Z"), table.evaluate(input));

        unsampledProvider = SdkTracerProvider.builder().setSampler(Sampler.alwaysOff()).build();
        sampledProvider = SdkTracerProvider.builder().setSampler(Sampler.alwaysOn())
                .addSpanProcessor(BatchSpanProcessor.builder(new DiscardingExporter()).setMaxQueueSize(8192)
                        .setMaxExportBatchSize(2048).build())
                .build();
        Tracer unsampled = tracer(unsampledProvider);
        Tracer sampled = tracer(sampledProvider);
        unsampledRules = (EngineTracing.Hook) construct(
                "io.github.dhluong90.bpm.rules.autoconfigure.RuleEngineTracing", unsampled);
        sampledRules = (EngineTracing.Hook) construct(
                "io.github.dhluong90.bpm.rules.autoconfigure.RuleEngineTracing", sampled);
        unsampledSteps = (ProcessTracing) construct(
                "io.github.dhluong90.bpm.process.autoconfigure.ProcessEngineTracing", unsampled);
        sampledSteps = (ProcessTracing) construct(
                "io.github.dhluong90.bpm.process.autoconfigure.ProcessEngineTracing", sampled);
    }

    @TearDown
    public void tearDown() {
        unsampledProvider.close();
        sampledProvider.close();
    }

    @Benchmark
    public DecisionResult.Outcome evaluateOff() {
        return table.evaluate(input);
    }

    @Benchmark
    public DecisionResult.Outcome evaluateUnsampled() {
        return traced(unsampledRules);
    }

    @Benchmark
    public DecisionResult.Outcome evaluateSampled() {
        return traced(sampledRules);
    }

    @Benchmark
    public Object stepOff() {
        return step(offSteps);
    }

    @Benchmark
    public Object stepUnsampled() {
        return step(unsampledSteps);
    }

    @Benchmark
    public Object stepSampled() {
        return step(sampledSteps);
    }

    private DecisionResult.Outcome traced(EngineTracing.Hook hook) {
        EngineTracing.Span span = hook.start();
        DecisionResult.Outcome outcome = table.evaluate(input);
        span.ended("bank-a", result);
        return outcome;
    }

    private static Object step(ProcessTracing tracing) {
        ProcessTracing.Span span = tracing.startStep("bank-a", "loan-disbursement", 3, "ASYNC_RESULT", "0123456789abcdef0123456789abcdef-0123456789abcdef");
        span.end("OK", null, null);
        return span;
    }

    private static Tracer tracer(SdkTracerProvider provider) {
        return new OtelTracer(provider.get("tracing-overhead-benchmark"), new OtelCurrentTraceContext(), event -> {
        });
    }

    private static Object construct(String className, Tracer tracer) throws Exception {
        Constructor<?> constructor = Class.forName(className).getDeclaredConstructor(Tracer.class, boolean.class);
        constructor.setAccessible(true);
        return constructor.newInstance(tracer, true);
    }

    /** Takes every finished span and drops it: the cost of recording and queueing, with no network. */
    private static final class DiscardingExporter implements SpanExporter {
        @Override
        public CompletableResultCode export(Collection<SpanData> spans) {
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode flush() {
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode shutdown() {
            return CompletableResultCode.ofSuccess();
        }
    }
}
