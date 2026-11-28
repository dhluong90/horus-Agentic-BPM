package io.github.dhluong90.bpm.process.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.process.api.DefinitionIntegrityException;
import io.github.dhluong90.bpm.process.api.IdempotencyInFlightException;
import io.github.dhluong90.bpm.process.api.ImportRequest;
import io.github.dhluong90.bpm.process.api.ProcessInstance;
import io.github.dhluong90.bpm.process.api.SourceFormat;
import io.github.dhluong90.bpm.process.api.StartRequest;
import io.github.dhluong90.bpm.process.spi.HandlerResult;
import io.github.dhluong90.bpm.process.spi.ServiceCall;
import io.github.dhluong90.bpm.process.spi.ServiceTaskHandler;
import io.github.dhluong90.bpm.process.test.TestClock;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The wait of a duplicate request on an idempotency key, and the approved hashes of a definition (#172). A duplicate
 * waits for the first request, for at most the lock timeout; it then replays the first answer or, when the first is
 * still running, is refused as {@code IDEMPOTENCY_IN_FLIGHT}. It never runs the work a second time.
 */
class IdempotencyWaitPostgresIT {

    private static final String[] PIPELINE = {"processes:deploy", "processes:start", "processes:read"};
    private static final String SCREENING = """
            <?xml version="1.0" encoding="UTF-8"?>
            <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                              xmlns:horus="urn:horus-bpm:bpmn:extensions:v1"
                              id="Definitions_payment-screening" targetNamespace="urn:horus-bpm:examples"
                              expressionLanguage="urn:horus-bpm:expr:v1">
              <bpmn:process id="payment-screening" isExecutable="true">
                <bpmn:startEvent id="start"/>
                <bpmn:serviceTask id="screen" horus:handler="screen-payment"/>
                <bpmn:endEvent id="end"/>
                <bpmn:sequenceFlow id="a" sourceRef="start" targetRef="screen"/>
                <bpmn:sequenceFlow id="b" sourceRef="screen" targetRef="end"/>
              </bpmn:process>
            </bpmn:definitions>
            """;

    private final TestClock clock = TestClock.at("2027-03-01T02:00:00Z");
    private final CountDownLatch inside = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);
    private final AtomicInteger screenings = new AtomicInteger();
    private TestEngine engine;
    private JdbcClient jdbc;

    private void engine(Duration lockTimeout) {
        TestEngine.reset();
        engine = TestEngine.replica("pod-1", clock).idempotencyLockTimeout(lockTimeout).handlers(
                new ServiceTaskHandler() {
                    @Override
                    public String name() {
                        return "screen-payment";
                    }

                    @Override
                    public HandlerResult handle(ServiceCall call) {
                        screenings.incrementAndGet();
                        inside.countDown();
                        try {
                            release.await(10, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        return new HandlerResult.Completed(Map.of("screened", true));
                    }
                }).build();
        jdbc = TestEngine.jdbc();
        engine.identity.as("bank-a", "pipeline", PIPELINE);
        engine.services.importDefinition(new ImportRequest(SourceFormat.BPMN_XML,
                SCREENING.getBytes(StandardCharsets.UTF_8), null));
    }

    @AfterEach
    void stop() {
        release.countDown();
        engine.close();
    }

    private ProcessInstance start(String businessKey, String idempotencyKey) {
        return engine.services.start(new StartRequest("payment-screening", businessKey, Map.of(), idempotencyKey));
    }

    private long instances() {
        return jdbc.sql("SELECT count(*) FROM pre_instance").query(Long.class).single();
    }

    @Test
    void PRE_377_a_duplicate_of_a_request_still_running_is_refused_within_the_lock_timeout_and_a_later_retry_replays() throws Exception {
        // Given a payment screening that holds its transaction open, started with Idempotency-Key PAY-2027-0412
        engine(Duration.ofMillis(300));
        CompletableFuture<ProcessInstance> first = CompletableFuture.supplyAsync(() -> start("PAY-2027-0412",
                "PAY-2027-0412"));
        assertThat(inside.await(10, TimeUnit.SECONDS)).isTrue();

        // When the client retries with the same key while the first is still running
        long began = System.nanoTime();
        assertThatThrownBy(() -> start("PAY-2027-0412", "PAY-2027-0412")).isInstanceOfSatisfying(
                IdempotencyInFlightException.class, e -> assertThat(e.code()).isEqualTo("IDEMPOTENCY_IN_FLIGHT"));

        // Then it gives up within the timeout (plus the round trip), and the screening ran once
        assertThat(Duration.ofNanos(System.nanoTime() - began)).isLessThan(Duration.ofSeconds(2));
        assertThat(screenings).hasValue(1);

        // And once the first commits, a further retry replays its answer: one instance, one screening
        release.countDown();
        ProcessInstance answered = first.get(10, TimeUnit.SECONDS);
        ProcessInstance replay = start("PAY-2027-0412", "PAY-2027-0412");
        assertThat(replay.id()).isEqualTo(answered.id());
        assertThat(instances()).isEqualTo(1);
        assertThat(screenings).hasValue(1);
    }

    @Test
    void PRE_378_a_duplicate_that_waits_for_the_first_within_the_timeout_returns_the_same_result_and_runs_nothing() throws Exception {
        // Given a first request that finishes after 400 ms and a lock timeout of 2 seconds
        engine(Duration.ofSeconds(2));
        CompletableFuture<ProcessInstance> first = CompletableFuture.supplyAsync(() -> start("PAY-2027-0413",
                "PAY-2027-0413"));
        assertThat(inside.await(10, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<ProcessInstance> duplicate = CompletableFuture.supplyAsync(() -> start("PAY-2027-0413",
                "PAY-2027-0413"));
        Thread.sleep(400);
        assertThat(duplicate).isNotDone();

        // When the first commits
        release.countDown();

        // Then the duplicate returns the first's instance and no second one exists
        ProcessInstance answered = first.get(10, TimeUnit.SECONDS);
        assertThat(duplicate.get(10, TimeUnit.SECONDS).id()).isEqualTo(answered.id());
        assertThat(instances()).isEqualTo(1);
        assertThat(screenings).hasValue(1);
    }

    @Test
    void PRE_379_a_version_whose_source_and_both_hashes_were_rewritten_is_refused_because_the_approval_record_disagrees() {
        // Given a version approved on its imported source, and a writer of pre_definition who swaps the screening
        // step and rewrites the source hash and the definition hash of the row to match: the values are taken from
        // a clean import of the swapped source under another tenant, so the row is consistent in itself
        engine(Duration.ofSeconds(1));
        release.countDown();
        engine.identity.as("bank-tmp", "pipeline", PIPELINE);
        engine.services.importDefinition(new ImportRequest(SourceFormat.BPMN_XML,
                SCREENING.replace("<bpmn:endEvent id=\"end\"/>", "<bpmn:endEvent id=\"end\" name=\"Skipped\"/>").getBytes(StandardCharsets.UTF_8), null));
        jdbc.sql("UPDATE pre_definition t SET source_document = s.source_document, source_hash = s.source_hash, "
                + "definition_hash = s.definition_hash, definition_json = s.definition_json FROM pre_definition s "
                + "WHERE t.tenant_id = 'bank-a' AND s.tenant_id = 'bank-tmp'").update();
        engine.identity.as("bank-a", "officer-lan", PIPELINE);

        // When the version is started
        // Then it is refused for the approval, not for the row, and nothing starts
        assertThatThrownBy(() -> start("PAY-2027-0414", null)).isInstanceOf(DefinitionIntegrityException.class)
                .hasMessageContaining("hashes approved");
        assertThat(instances()).isZero();
    }

    @Test
    void PRE_380_the_approval_record_is_written_with_the_approval_and_cannot_be_changed() {
        engine(Duration.ofSeconds(1));
        release.countDown();

        assertThat(jdbc.sql("SELECT count(*) FROM pre_definition_approval p JOIN pre_definition d ON d.id = "
                + "p.definition_id AND d.tenant_id = p.tenant_id WHERE p.source_hash = d.source_hash AND "
                + "p.definition_hash = d.definition_hash").query(Long.class).single()).isEqualTo(1);
        for (String statement : new String[] {"UPDATE pre_definition_approval SET source_hash = repeat('b', 64)",
                "DELETE FROM pre_definition_approval", "TRUNCATE pre_definition_approval"}) {
            assertThatThrownBy(() -> jdbc.sql(statement).update()).hasMessageContaining("append-only");
        }
    }
}
