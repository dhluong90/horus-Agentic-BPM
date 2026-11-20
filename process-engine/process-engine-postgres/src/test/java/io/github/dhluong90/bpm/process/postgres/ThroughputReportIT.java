package io.github.dhluong90.bpm.process.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import io.github.dhluong90.bpm.process.api.ImportRequest;
import io.github.dhluong90.bpm.process.api.SourceFormat;
import io.github.dhluong90.bpm.process.api.StartRequest;
import io.github.dhluong90.bpm.process.spi.HandlerResult;
import io.github.dhluong90.bpm.process.spi.ProcessEventPublisher;
import io.github.dhluong90.bpm.process.spi.ServiceTaskHandler;
import io.github.dhluong90.bpm.process.test.TestClock;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The throughput report (PRE-087, TR-PERF-04): one replica runs a workload of about ten transitions per instance
 * (an exclusive gateway, local service steps, a one-second timer, a parallel fork and join) on PostgreSQL with
 * history on, for a warm-up and a measured period, and writes {@code throughput-report.json} and
 * {@code throughput-report.md} with the sustained transitions per second, the 50th, 95th and 99th percentile of the
 * time of one engine call (one step transaction), the most pool connections in use, and the machine that ran it.
 *
 * <p>The report is information, never a gate (PD-026): a result below 1,000 transitions per second, or more than
 * 20 percent below the previous report, is a warning in the report and never fails the build. This test fails only
 * when it could not produce a report. {@code horus.throughput.warmup} and {@code horus.throughput.measure} (ISO-8601
 * durations, default 2 and 5 seconds, so an ordinary build stays short; CI sets 1 and 5 minutes),
 * {@code horus.throughput.workers} (default 8) and {@code horus.throughput.previous} (the previous report) are system
 * properties.
 */
class ThroughputReportIT {

    static final double TARGET_PER_SECOND = 1_000;
    static final double REGRESSION_LIMIT = 0.20;

    private static Duration duration(String property, Duration fallback) {
        String value = System.getProperty(property);
        return value == null || value.isBlank() ? fallback : Duration.parse(value);
    }

    @Test
    void PRE_087_throughput_report_is_written_and_never_gates() throws Exception {
        Duration warmup = duration("horus.throughput.warmup", Duration.ofSeconds(2));
        Duration measure = duration("horus.throughput.measure", Duration.ofSeconds(5));
        int workers = Integer.getInteger("horus.throughput.workers", 8);
        TestEngine.reset();
        LongAdder transitions = new LongAdder();
        ProcessEventPublisher counter = event -> {
            if (event.type().equals("processes.step.completed.v1")) {
                transitions.increment();
            }
        };
        ServiceTaskHandler step = new ServiceTaskHandler() {
            @Override
            public String name() {
                return "load.step";
            }

            @Override
            public HandlerResult handle(io.github.dhluong90.bpm.process.spi.ServiceCall call) {
                return HandlerResult.completed(Map.of("ok", true));
            }
        };
        int poolSize = 20;
        try (HikariDataSource pool = TestEngine.pool(poolSize, Duration.ofSeconds(30))) {
            TestClock clock = TestClock.at(Instant.parse("2026-11-20T09:00:00Z"));
            TestEngine engine = TestEngine.replica("pod-1", clock)
                    .dataSource(pool).publisher(counter).handlers(step).build();
            try {
                engine.identity.permissions = Set.of("processes:deploy", "processes:start", "processes:read");
                engine.services.importDefinition(new ImportRequest(SourceFormat.BPMN_XML,
                        TestEngine.resource("/bpmn/throughput-10-step.bpmn"), null));

                AtomicBoolean running = new AtomicBoolean(true);
                AtomicBoolean measuring = new AtomicBoolean(false);
                AtomicInteger maxInUse = new AtomicInteger();
                AtomicLong sequence = new AtomicLong();
                List<List<Long>> callNanos = new ArrayList<>();
                ExecutorService executor = Executors.newFixedThreadPool(workers + 2);
                for (int w = 0; w < workers; w++) {
                    List<Long> mine = new ArrayList<>();
                    callNanos.add(mine);
                    executor.submit(() -> {
                        while (running.get()) {
                            long begun = System.nanoTime();
                            engine.services.start(StartRequest.of("throughput-10-step")
                                    .withBusinessKey("LOAD-" + sequence.incrementAndGet())
                                    .withVariables(Map.of("score", 650)));
                            if (measuring.get()) {
                                mine.add(System.nanoTime() - begun);
                            }
                        }
                    });
                }
                List<Long> pollNanos = new ArrayList<>();
                callNanos.add(pollNanos);
                executor.submit(() -> {
                    while (running.get()) {
                        // The timer of the workload is due one second after its start: the injected clock moves.
                        clock.advance(Duration.ofSeconds(1));
                        long begun = System.nanoTime();
                        int fired = engine.jobs.pollOnce();
                        if (fired > 0 && measuring.get()) {
                            pollNanos.add(System.nanoTime() - begun);
                        }
                    }
                });
                executor.submit(() -> {
                    while (running.get()) {
                        maxInUse.accumulateAndGet(pool.getHikariPoolMXBean().getActiveConnections(), Math::max);
                        try {
                            Thread.sleep(25);
                        } catch (InterruptedException e) {
                            return;
                        }
                    }
                });

                waitFor(warmup);
                long startCount = transitions.sum();
                long startedAt = System.nanoTime();
                measuring.set(true);
                waitFor(measure);
                measuring.set(false);
                long measuredTransitions = transitions.sum() - startCount;
                double seconds = (System.nanoTime() - startedAt) / 1e9;
                running.set(false);
                executor.shutdown();
                assertThat(executor.awaitTermination(60, TimeUnit.SECONDS)).isTrue();

                long[] sorted = callNanos.stream().flatMap(List::stream).mapToLong(Long::longValue).sorted().toArray();
                Report report = new Report(measuredTransitions / seconds, measuredTransitions, seconds,
                        percentile(sorted, 0.50), percentile(sorted, 0.95), percentile(sorted, 0.99), maxInUse.get(),
                        poolSize, workers, Runtime.getRuntime().availableProcessors(),
                        Runtime.getRuntime().maxMemory() / (1024 * 1024), postgresVersion(), warmup, measure);
                write(report, previous());

                assertThat(measuredTransitions).as("the workload ran").isPositive();
                assertThat(Files.exists(Path.of("target/throughput-report/throughput-report.json"))).isTrue();
            } finally {
                engine.close();
            }
        }
    }

    private static void waitFor(Duration period) throws InterruptedException {
        long until = System.nanoTime() + period.toNanos();
        while (System.nanoTime() < until) {
            Thread.sleep(Math.min(50, Math.max(1, (until - System.nanoTime()) / 1_000_000)));
        }
    }

    private static double percentile(long[] sortedNanos, double q) {
        if (sortedNanos.length == 0) {
            return Double.NaN;
        }
        return sortedNanos[Math.max(0, (int) Math.ceil(q * sortedNanos.length) - 1)] / 1e6;
    }

    private static String postgresVersion() {
        return JdbcClient.create(TestEngine.dataSource()).sql("SHOW server_version").query(String.class).single();
    }

    private static Double previous() throws IOException {
        String path = System.getProperty("horus.throughput.previous");
        if (path == null || path.isBlank() || !Files.exists(Path.of(path))) {
            return null;
        }
        var tree = io.github.dhluong90.bpm.process.internal.json.EngineJson.JSON.readTree(Files.readString(Path.of(path)));
        return tree.path("transitionsPerSecond").isNumber() ? tree.path("transitionsPerSecond").asDouble() : null;
    }

    private record Report(double transitionsPerSecond, long transitions, double measuredSeconds, double p50Ms,
            double p95Ms, double p99Ms, int maxPoolConnectionsInUse, int poolSize, int workers, int vcpus, long memoryMb,
            String postgresVersion, Duration warmup, Duration measure) {
    }

    private static void write(Report r, Double previous) throws IOException {
        List<String> warnings = new ArrayList<>();
        if (r.transitionsPerSecond() < TARGET_PER_SECOND) {
            warnings.add(String.format("Below the target of %,.0f transitions per second.", TARGET_PER_SECOND));
        }
        if (previous != null && r.transitionsPerSecond() < previous * (1 - REGRESSION_LIMIT)) {
            warnings.add(String.format("More than 20 percent below the previous report (%.0f per second).", previous));
        }
        Path directory = Path.of("target/throughput-report");
        Files.createDirectories(directory);
        var json = io.github.dhluong90.bpm.process.internal.json.EngineJson.NODES.objectNode();
        json.put("workload", "10-step definition on PostgreSQL, history on, 1 replica");
        json.put("transitionsPerSecond", Math.round(r.transitionsPerSecond() * 10) / 10.0);
        json.put("transitions", r.transitions());
        json.put("measuredSeconds", Math.round(r.measuredSeconds() * 10) / 10.0);
        json.put("warmup", r.warmup().toString());
        json.put("measure", r.measure().toString());
        json.put("callMsP50", Math.round(r.p50Ms() * 100) / 100.0);
        json.put("callMsP95", Math.round(r.p95Ms() * 100) / 100.0);
        json.put("callMsP99", Math.round(r.p99Ms() * 100) / 100.0);
        json.put("maxPoolConnectionsInUse", r.maxPoolConnectionsInUse());
        json.put("poolSize", r.poolSize());
        json.put("workers", r.workers());
        json.put("vcpus", r.vcpus());
        json.put("memoryMb", r.memoryMb());
        json.put("postgresVersion", r.postgresVersion());
        json.put("targetTransitionsPerSecond", TARGET_PER_SECOND);
        var list = json.putArray("warnings");
        warnings.forEach(list::add);
        Files.writeString(directory.resolve("throughput-report.json"),
                io.github.dhluong90.bpm.process.internal.json.EngineJson.JSON.writeValueAsString(json) + "\n");
        StringBuilder md = new StringBuilder("# Throughput report\n\n");
        md.append(String.format("%,.0f transitions per second sustained over %.0f s (target %,.0f, reported, never a gate).%n%n",
                r.transitionsPerSecond(), r.measuredSeconds(), TARGET_PER_SECOND));
        md.append("| Measure | Value |\n|---|---|\n");
        md.append(String.format("| Time of one engine call, p50 / p95 / p99 | %.1f / %.1f / %.1f ms |%n", r.p50Ms(), r.p95Ms(),
                r.p99Ms()));
        md.append(String.format("| Most pool connections in use | %d of %d |%n", r.maxPoolConnectionsInUse(), r.poolSize()));
        md.append(String.format("| Runner | %d vCPU, %d MB heap limit |%n", r.vcpus(), r.memoryMb()));
        md.append(String.format("| PostgreSQL | %s |%n", r.postgresVersion()));
        md.append(String.format("| Warm-up / measured | %s / %s, %d workers |%n%n", r.warmup(), r.measure(), r.workers()));
        warnings.forEach(w -> md.append("**Warning:** ").append(w).append("\n\n"));
        Files.writeString(directory.resolve("throughput-report.md"), md.toString());
    }
}
