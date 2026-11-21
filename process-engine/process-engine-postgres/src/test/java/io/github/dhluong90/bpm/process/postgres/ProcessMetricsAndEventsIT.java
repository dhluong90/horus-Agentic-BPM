package io.github.dhluong90.bpm.process.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import io.github.dhluong90.bpm.process.api.ImportRequest;
import io.github.dhluong90.bpm.process.internal.json.EngineJson;
import tools.jackson.databind.JsonNode;
import io.github.dhluong90.bpm.process.api.ProcessInstance;
import io.github.dhluong90.bpm.process.api.SourceFormat;
import io.github.dhluong90.bpm.process.api.StartRequest;
import io.github.dhluong90.bpm.process.spi.EventScope;
import io.github.dhluong90.bpm.process.spi.HandlerResult;
import io.github.dhluong90.bpm.process.spi.ProcessEventPublisher;
import io.github.dhluong90.bpm.process.spi.ProcessMetrics;
import io.github.dhluong90.bpm.process.spi.ServiceCall;
import io.github.dhluong90.bpm.process.spi.ServiceTaskHandler;
import io.github.dhluong90.bpm.process.test.TestClock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Metrics, events and their payloads on PostgreSQL (#86, PRE-084 to PRE-086). The engine counts only what committed,
 * hands every state change to the publisher in the standard envelope inside its transaction, and carries ids and
 * codes, never a variable. Time is the injected clock; nothing sleeps.
 */
class ProcessMetricsAndEventsIT {

    private static final Instant T0 = Instant.parse("2026-11-20T09:00:00Z");
    private static final Path EVENT_SCHEMAS = Path.of("../api/events");

    private static final String DISBURSEMENT = """
            <?xml version="1.0" encoding="UTF-8"?>
            <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                              xmlns:horus="urn:horus-bpm:bpmn:extensions:v1" id="Definitions_loan-disbursement"
                              targetNamespace="urn:horus-bpm:examples" expressionLanguage="urn:horus-bpm:expr:v1">
              <bpmn:process id="loan-disbursement" name="Loan disbursement" isExecutable="true">
                <bpmn:startEvent id="start"/>
                <bpmn:serviceTask id="check-limit" horus:handler="limits.check" horus:resultVariable="limit">
                  <bpmn:extensionElements>
                    <horus:input name="accountId" value="${instance.accountId}"/>
                  </bpmn:extensionElements>
                </bpmn:serviceTask>
                <bpmn:userTask id="disburse" horus:assignee="role:LOAN_OFFICER" horus:resultVariable="disbursal"/>
                <bpmn:endEvent id="end"/>
                <bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="check-limit"/>
                <bpmn:sequenceFlow id="f2" sourceRef="check-limit" targetRef="disburse"/>
                <bpmn:sequenceFlow id="f3" sourceRef="disburse" targetRef="end"/>
              </bpmn:process>
            </bpmn:definitions>
            """;

    /** Counts what the engine reports, as the starter's meters would. */
    static final class Sink implements ProcessMetrics {
        final List<String> started = new CopyOnWriteArrayList<>();
        final List<String> completed = new CopyOnWriteArrayList<>();
        final List<String> incidents = new CopyOnWriteArrayList<>();
        final List<String> steps = new CopyOnWriteArrayList<>();
        final List<String> attempts = new CopyOnWriteArrayList<>();
        final List<Duration> timerLag = new CopyOnWriteArrayList<>();

        @Override
        public void instanceStarted(String definitionKey) {
            started.add(definitionKey);
        }

        @Override
        public void instanceCompleted(String definitionKey) {
            completed.add(definitionKey);
        }

        @Override
        public void incidentRaised(String errorCode) {
            incidents.add(errorCode);
        }

        @Override
        public void stepApplied(String stepKind, String outcome, Duration elapsed) {
            steps.add(stepKind + ":" + outcome);
        }

        @Override
        public void asyncAttempt(String kind, String outcome) {
            attempts.add(kind + ":" + outcome);
        }

        @Override
        public void timerLag(Duration lag) {
            timerLag.add(lag);
        }
    }

    private final TestClock clock = TestClock.at(T0);
    private final Sink sink = new Sink();
    /** Events of transactions that committed; a rolled-back transaction leaves nothing here. */
    private final List<ProcessEventPublisher.Event> committed = new CopyOnWriteArrayList<>();
    /** Events as handed over, inside the transaction. */
    private final List<ProcessEventPublisher.Event> handedOver = new CopyOnWriteArrayList<>();
    private TestEngine engine;

    @BeforeEach
    void engine() {
        TestEngine.reset();
        ProcessEventPublisher publisher = event -> {
            handedOver.add(event);
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        committed.add(event);
                    }
                });
            } else {
                committed.add(event);
            }
        };
        engine = TestEngine.replica("pod-1", clock).publisher(publisher).handlers(handler("limits.check", call -> {
            String account = String.valueOf(call.inputs().get("accountId"));
            if (account.startsWith("ACC-ROLLBACK")) {
                throw new IllegalStateException("The limits service is down");
            }
            if (account.startsWith("ACC-LIMIT")) {
                return new HandlerResult.BusinessError("LIMIT_EXCEEDED", "Daily limit reached for " + account);
            }
            return HandlerResult.completed(Map.of("ok", true));
        })).build();
        engine.services.useMetrics(sink);
        engine.identity.permissions = Set.of("processes:deploy", "processes:start", "processes:read",
                "processes:task:work", "processes:cancel");
        engine.identity.roles = Set.of("LOAN_OFFICER");
        engine.services.importDefinition(new ImportRequest(SourceFormat.BPMN_XML,
                DISBURSEMENT.strip().getBytes(StandardCharsets.UTF_8), null));
    }

    @AfterEach
    void stop() {
        engine.close();
    }

    private static ServiceTaskHandler handler(String name, java.util.function.Function<ServiceCall, HandlerResult> body) {
        return new ServiceTaskHandler() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public HandlerResult handle(ServiceCall call) {
                return body.apply(call);
            }
        };
    }

    private ProcessInstance start(String account) {
        return engine.services.start(new StartRequest("loan-disbursement", "DISB-" + account,
                Map.of("accountId", account, "nationalId", "079088001234"), null));
    }

    private void complete(ProcessInstance instance) {
        ProcessInstance.Task task = instance.openTasks().get(0);
        long lockVersion = engine.services.claim(task.id(), task.lockVersion(), "claim-" + instance.id()).lockVersion();
        engine.services.complete(task.id(), Map.of("done", true), lockVersion, "complete-" + instance.id());
    }

    // ---- PRE-084 ---------------------------------------------------------------------------------------------

    @Test
    void PRE_084_metrics_count_only_committed_work_and_carry_only_engine_values() {
        // Given six starts of loan-disbursement are attempted and one rolls back because its local handler throws
        List<ProcessInstance> ok = new ArrayList<>();
        for (int i = 1; i <= 4; i++) {
            ok.add(start("ACC-000" + i));
        }
        ProcessInstance atIncident = start("ACC-LIMIT-1");
        try {
            start("ACC-ROLLBACK-1");
        } catch (RuntimeException expected) {
            // the start rolled back
        }
        // and three of the five that started are completed, one waits at its user task, one has an open incident
        complete(ok.get(0));
        complete(ok.get(1));
        complete(ok.get(2));

        // Then the counters moved only for committed work: the rolled-back start is not counted
        assertThat(sink.started).as("started").hasSize(5).containsOnly("loan-disbursement");
        assertThat(sink.completed).as("completed").hasSize(3);
        assertThat(sink.incidents).containsExactly("UNHANDLED_BUSINESS_ERROR");
        // and steps are reported by command kind only: the failed one as FAILED at once, the committed ones as OK
        // (the rolled-back start's own START was applied, but its transaction never committed, so only the five
        // committed starts are counted; the handler's failure is the one failed step)
        assertThat(sink.steps).contains("START:OK", "ASYNC_RESULT:FAILED", "COMPLETE_TASK:OK");
        assertThat(sink.steps.stream().filter("START:OK"::equals)).hasSize(5);
        assertThat(sink.steps.stream().filter("ASYNC_RESULT:FAILED"::equals)).hasSize(1);
        // and the gauges read from the database: two instances still running, one open incident
        io.github.dhluong90.bpm.process.internal.store.GaugeStore.Snapshot gauges =
                new JdbcGaugeStore(TestEngine.dataSource()).read();
        assertThat(gauges.activeInstances()).isEqualTo(2);
        assertThat(gauges.openIncidents()).isEqualTo(1);
        assertThat(atIncident.status()).isEqualTo("RUNNING");
        // and no value handed to the sink is a tenant, a business key, an instance id or a variable
        assertThat(allSinkValues()).noneMatch(v -> v.contains("bank-a") || v.contains("DISB-") || v.contains("079088001234")
                || v.contains(ok.get(0).id()));
    }

    private List<String> allSinkValues() {
        List<String> values = new ArrayList<>();
        values.addAll(sink.started);
        values.addAll(sink.completed);
        values.addAll(sink.incidents);
        values.addAll(sink.steps);
        values.addAll(sink.attempts);
        return values;
    }

    @Test
    void PRE_084_a_timer_that_fires_four_seconds_late_records_four_seconds_of_lag() {
        // Given a card activation reminder whose timer is due three days after the start
        engine.close();
        engine = TestEngine.replica("pod-1", clock).publisher(committedPublisher())
                .handlers(handler("cardReminders.send", call -> HandlerResult.completed(Map.of("sent", true)))).build();
        engine.services.useMetrics(sink);
        engine.identity.permissions = Set.of("processes:deploy", "processes:start", "processes:read");
        engine.services.importDefinition(new ImportRequest(SourceFormat.BPMN_XML,
                TestEngine.resource("/bpmn/card-activation-reminder.bpmn"), null));
        engine.services.start(new StartRequest("card-activation-reminder", "CARD-4111", Map.of("cardId", "CARD-4111"), null));

        // When the engine polls 4 seconds after the due time
        clock.set(T0.plus(Duration.ofDays(3)).plusSeconds(4));
        int fired = engine.jobs.pollOnce();

        // Then one timer fired and the lag is 4 seconds
        assertThat(fired).isEqualTo(1);
        assertThat(sink.timerLag).containsExactly(Duration.ofSeconds(4));
        assertThat(sink.steps).contains("JOB_DUE:OK");
    }

    private ProcessEventPublisher committedPublisher() {
        return event -> {
            handedOver.add(event);
            committed.add(event);
        };
    }

    // ---- PRE-085 ---------------------------------------------------------------------------------------------

    @Test
    void PRE_085_every_state_change_hands_one_enveloped_event_to_the_host_inside_its_transaction() throws Exception {
        // Given an instance that starts, passes its check, and completes; a second that is cancelled with an
        // Idempotency-Key; a third at an incident; and a fourth whose start rolls back
        ProcessInstance done = start("ACC-0001");
        complete(done);
        ProcessInstance cancelled = start("ACC-0002");
        try (EventScope ignored = EventScope.open("REQ-7781", "cancel-DISB-0002")) {
            engine.services.cancel(cancelled.id(), cancelled.lockVersion(), "customer withdrew", "cancel-DISB-0002");
        }
        ProcessInstance incident = start("ACC-LIMIT-1");
        int before = committed.size();
        try {
            start("ACC-ROLLBACK-1");
        } catch (RuntimeException expected) {
            // rolled back
        }

        // Then the publisher received the types of the slice, each in the transaction of its change
        Set<String> types = committed.stream().map(ProcessEventPublisher.Event::type).collect(Collectors.toSet());
        assertThat(types).contains("processes.instance.started.v1", "processes.step.completed.v1",
                "processes.incident.raised.v1", "processes.instance.completed.v1", "processes.instance.cancelled.v1",
                "processes.task.created.v1", "processes.task.completed.v1", "processes.task.claimed.v1");
        // and the rolled-back start handed events over but committed none
        assertThat(handedOver.size()).isGreaterThan(committed.size());
        assertThat(committed).hasSize(before);
        assertThat(committed.stream().filter(e -> e.type().equals("processes.instance.started.v1")))
                .as("one started event per committed start").hasSize(3);

        // and each event has the envelope, which validates against its schema file
        for (ProcessEventPublisher.Event event : committed) {
            JsonNode envelope = EngineJson.JSON.valueToTree(event.envelope());
            assertThat(event.eventId()).matches("[0-9A-HJKMNP-TV-Z]{26}");
            assertThat(envelope.get("producer").asString()).isEqualTo("horus-process-engine");
            assertThat(envelope.get("schemaVersion").asInt()).isEqualTo(1);
            assertThat(event.tenantId()).isEqualTo("bank-a");
            assertThat(event.occurredAt()).isEqualTo(T0);
            assertThat(validate(event.type(), envelope)).as("%s against its schema", event.type()).isEmpty();
        }

        // and the correlation is the request's and the cause is the command, for the cancel; otherwise the chain
        // starts at the event and nothing is recorded as its cause
        ProcessEventPublisher.Event cancel = committed.stream()
                .filter(e -> e.type().equals("processes.instance.cancelled.v1")).findFirst().orElseThrow();
        assertThat(cancel.correlationId()).isEqualTo("REQ-7781");
        assertThat(cancel.causationId()).isEqualTo("cancel-DISB-0002");
        assertThat(cancel.payload()).containsEntry("cancelledBy", "pipeline").containsEntry("reason", "customer withdrew");
        ProcessEventPublisher.Event first = committed.get(0);
        assertThat(first.correlationId()).isEqualTo(first.eventId());
        assertThat(first.causationId()).isNull();
        assertThat(incident.status()).isEqualTo("RUNNING");
    }

    @Test
    void PRE_085_a_step_completed_event_carries_the_time_the_token_waited_at_the_node() throws Exception {
        // Given an instance waiting at its user task for 90 seconds
        ProcessInstance loan = start("ACC-0001");
        clock.advance(Duration.ofSeconds(90));

        // When the task is completed
        complete(loan);

        // Then the step completed event of the user task has a duration of 90,000 ms
        ProcessEventPublisher.Event step = committed.stream()
                .filter(e -> e.type().equals("processes.step.completed.v1") && "disburse".equals(e.payload().get("nodeId")))
                .findFirst().orElseThrow();
        assertThat(step.payload()).containsEntry("durationMs", 90_000L).containsEntry("instanceId", loan.id());
    }

    // ---- PRE-086 ---------------------------------------------------------------------------------------------

    @Test
    void PRE_086_event_payloads_carry_ids_and_codes_never_variables_or_masked_values() throws Exception {
        // Given an instance with variable nationalId and business key DISB-ACC-0001, taken through its life
        ProcessInstance loan = start("ACC-0001");
        complete(loan);
        start("ACC-LIMIT-9");

        // When every event is serialised
        String all = EngineJson.JSON.writeValueAsString(committed.stream().map(ProcessEventPublisher.Event::envelope).toList());

        // Then no event holds a variable value, a task result or the handler's message
        assertThat(all).doesNotContain("079088001234").doesNotContain("Daily limit reached").doesNotContain("\"done\"");
        // and the started event is ids, keys and the version only, with the business key where DESIGN lists it
        ProcessEventPublisher.Event started = committed.stream()
                .filter(e -> e.type().equals("processes.instance.started.v1")).findFirst().orElseThrow();
        assertThat(new TreeMap<>(started.payload()).keySet()).contains("instanceId", "definitionKey", "version",
                "businessKey").doesNotContain("variables", "nationalId", "accountId");
        assertThat(started.payload()).containsEntry("businessKey", "DISB-ACC-0001");
        // and the incident event holds the code and the business code, not the message
        assertThat(committed.stream().filter(e -> e.type().equals("processes.incident.raised.v1")).findFirst().orElseThrow()
                .payload()).containsEntry("errorCode", "UNHANDLED_BUSINESS_ERROR")
                .containsEntry("businessCode", "LIMIT_EXCEEDED");
    }

    @Test
    void PRE_085_an_idempotent_replay_publishes_no_event() {
        // Given a start with an idempotency key
        engine.services.start(new StartRequest("loan-disbursement", "DISB-REPLAY", Map.of("accountId", "ACC-0001"),
                "start-replay-1"));
        int published = committed.size();

        // When the same start is sent again
        engine.services.start(new StartRequest("loan-disbursement", "DISB-REPLAY", Map.of("accountId", "ACC-0001"),
                "start-replay-1"));

        // Then the stored answer is returned and no event is published
        assertThat(committed).hasSize(published);
    }

    // ---- helpers ---------------------------------------------------------------------------------------------

    /** @return the validation messages, empty when the envelope conforms to the type's schema file */
    static List<String> validate(String type, JsonNode envelope) throws Exception {
        Path file = EVENT_SCHEMAS.resolve(type + ".schema.json");
        assertThat(Files.exists(file)).as("schema file for %s", type).isTrue();
        Schema schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                .getSchema(Files.readString(file));
        return schema.validate(envelope.toString(), InputFormat.JSON).stream().map(Object::toString).toList();
    }
}
