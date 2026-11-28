package io.github.dhluong90.bpm.process.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dhluong90.bpm.process.api.ImportRequest;
import io.github.dhluong90.bpm.process.api.SourceFormat;
import io.github.dhluong90.bpm.process.api.StartRequest;
import io.github.dhluong90.bpm.process.api.StepCompletion;
import io.github.dhluong90.bpm.process.internal.service.AsyncSettings;
import io.github.dhluong90.bpm.process.internal.service.AsyncStepExecutor.Claim;
import io.github.dhluong90.bpm.process.spi.AdapterCall;
import io.github.dhluong90.bpm.process.spi.AdapterResult;
import io.github.dhluong90.bpm.process.spi.ProcessTracing;
import io.github.dhluong90.bpm.process.spi.ServiceAdapter;
import io.github.dhluong90.bpm.process.test.TestClock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Span links across an async step (PRE-530, TR-OBS-03, TR-SEC-05): the step that schedules a remote call and the step
 * that applies its answer run in different transactions, often on different threads, so they sit in different traces.
 * The engine keeps the scheduling span's reference in the job and hands it to the tracer when the answer is applied.
 */
class AsyncSpanLinkTest {

    private static final Instant T0 = Instant.parse("2027-03-01T02:00:00Z");
    private static final String BUREAU = "creditBureau.score";
    private static final AsyncSettings SETTINGS = new AsyncSettings(AsyncSettings.DEFAULT_LEASE_MARGIN,
            AsyncSettings.DEFAULT_TIMEOUT, AsyncSettings.DEFAULT_EXECUTOR_THREADS, Map.of());

    private final TestClock clock = TestClock.at(T0);
    private final List<TestEngine> engines = new ArrayList<>();
    /** Every span started, in order: kind, the link it was started from (or null), the link it names itself by. */
    private final List<String[]> spans = new CopyOnWriteArrayList<>();
    private int counter;

    @BeforeEach
    void clean() {
        TestEngine.reset();
    }

    @AfterEach
    void stop() {
        engines.forEach(TestEngine::close);
    }

    /** A tracer that gives each span an id and records what it was linked from. */
    private ProcessTracing tracing() {
        return new ProcessTracing() {
            @Override
            public Span startStep(String tenant, String key, int version, String kind) {
                return startStep(tenant, key, version, kind, null);
            }

            @Override
            public Span startStep(String tenant, String key, int version, String kind, String linkedFrom) {
                String own = "trace-1-span-" + (++counter);
                spans.add(new String[] {kind, linkedFrom, own});
                return new Span() {
                    @Override
                    public void end(String outcome, String errorCode, Throwable failure) {
                    }

                    @Override
                    public String link() {
                        return own;
                    }
                };
            }
        };
    }

    private TestEngine replica(ProcessTracing tracing) {
        ServiceAdapter bureau = new ServiceAdapter() {
            @Override
            public String name() {
                return BUREAU;
            }

            @Override
            public AdapterResult execute(AdapterCall call) {
                return AdapterResult.completed(Map.of("score", 712, "band", "A"));
            }
        };
        TestEngine engine = TestEngine.replica("pod-1", clock).async(SETTINGS).adapters(bureau).build();
        engines.add(engine);
        engine.identity.permissions = java.util.Set.of("processes:deploy", "processes:start", "processes:read",
                "processes:task:work", "processes:cancel", "processes:step:complete");
        engine.services.useTracing(tracing);
        engine.services.importDefinition(new ImportRequest(SourceFormat.BPMN_XML,
                TestEngine.resource("/bpmn/loan-bureau-check.bpmn"), null));
        return engine;
    }

    @Test
    void PRE_530_the_span_that_applies_an_async_result_is_linked_to_the_span_that_scheduled_the_call() {
        // Given a loan whose first step schedules the bureau call, in a trace of its own
        TestEngine engine = replica(tracing());
        String loan = engine.services.start(new StartRequest("loan-bureau-check", "LOAN-2027-0521",
                Map.of("nationalId", "079088001234", "amount", 250_000_000), null)).id();
        String scheduling = spans.stream().filter(s -> s[0].equals("START")).findFirst().orElseThrow()[2];
        assertThat(spans).noneMatch(s -> s[1] != null);

        // When the runner calls the bureau and records the answer in another transaction
        Claim claim = engine.async.claim(16).get(0);
        assertThat(engine.async.runNow(claim)).contains(StepCompletion.Outcome.ACCEPTED);

        // Then the span of the ASYNC_RESULT step names the scheduling span, and no other span carries a link
        List<String[]> results = spans.stream().filter(s -> s[0].equals("ASYNC_RESULT")).toList();
        assertThat(results).hasSize(1);
        assertThat(results.getFirst()[1]).isEqualTo(scheduling);
        assertThat(spans.stream().filter(s -> s[1] != null)).hasSize(1);
        assertThat(engine.services.instance(loan).variables()).containsKey("bureau");
    }

    @Test
    void PRE_530_the_link_is_ids_only_and_never_a_value_of_the_request() {
        // Given a loan started with a national id, and its scheduled job
        TestEngine engine = replica(tracing());
        String loan = engine.services.start(new StartRequest("loan-bureau-check", "LOAN-2027-0522",
                Map.of("nationalId", "079088001234", "amount", 250_000_000), null)).id();

        // Then the job payload holds the span reference beside the call's own fields, and the reference holds no value
        String payload = TestEngine.jdbc().sql("SELECT payload::text FROM pre_timer WHERE instance_id = ?").param(loan)
                .query(String.class).single();
        assertThat(payload).contains("\"traceLink\"");
        String reference = spans.stream().filter(s -> s[0].equals("START")).findFirst().orElseThrow()[2];
        assertThat(reference).doesNotContain("079088001234").doesNotContain("LOAN-2027-0522");
    }

    @Test
    void PRE_530_a_tracer_that_does_not_know_links_still_applies_the_result() {
        // Given a tracer written before links existed: only the four-argument method, a span with no link()
        ProcessTracing old = (tenant, key, version, kind) -> new ProcessTracing.Span() {
            @Override
            public void end(String outcome, String errorCode, Throwable failure) {
            }
        };
        TestEngine engine = replica(old);
        String loan = engine.services.start(new StartRequest("loan-bureau-check", "LOAN-2027-0523",
                Map.of("nationalId", "079088001234", "amount", 250_000_000), null)).id();

        // When the answer is applied
        assertThat(engine.async.runNow(engine.async.claim(16).get(0))).contains(StepCompletion.Outcome.ACCEPTED);

        // Then the loan moved on and the job carries no link
        assertThat(engine.services.instance(loan).variables()).containsKey("bureau");
        assertThat(TestEngine.jdbc().sql("SELECT payload::text FROM pre_timer WHERE instance_id = ?").param(loan)
                .query(String.class).single()).doesNotContain("traceLink");
    }
}
