package io.github.dhluong90.bpm.process.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.process.api.ImportRequest;
import io.github.dhluong90.bpm.process.api.MessageService.MessageDelivery;
import io.github.dhluong90.bpm.process.api.PayloadTooLargeException;
import io.github.dhluong90.bpm.process.api.ProcessInstance;
import io.github.dhluong90.bpm.process.api.SourceFormat;
import io.github.dhluong90.bpm.process.api.StartRequest;
import io.github.dhluong90.bpm.process.api.TaskNotFoundException;
import io.github.dhluong90.bpm.process.internal.service.ProcessServices;
import io.github.dhluong90.bpm.process.internal.service.StartArrivalSettings;
import io.github.dhluong90.bpm.process.spi.FormRegistry;
import io.github.dhluong90.bpm.process.test.TestClock;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Regression tests of the pre-1.0 audit findings (#572) on PostgreSQL: the answer of a start with an existing business
 * key (AUD-01), masked form fields in the instance view (AUD-04), who may read a task and its form (AUD-05), the
 * append-only history (AUD-03) and the bounds of held start arrivals (AUD-10).
 */
class AuditFindingsPostgresIT {

    private static final String[] OFFICER = {"processes:start", "processes:read", "processes:task:work",
            "processes:deploy"};
    private static final String ID_NUMBER = "079012345678";

    private final TestClock clock = TestClock.at("2027-03-01T02:00:00Z");
    private TestEngine engine;
    private JdbcClient jdbc;

    private static FormRegistry registry() {
        Map<String, Object> schema = Map.of("type", "object", "properties", Map.of(
                "decision", Map.of("enum", List.of("APPROVE", "REJECT")),
                "reason", Map.of("type", "string"),
                "idNumber", Map.of("type", "string", "mask", true)));
        return (tenant, reference) -> "forms/approve.schema.json".equals(reference)
                ? Optional.of(new FormRegistry.FormDefinition(schema, Map.of()))
                : Optional.empty();
    }

    @BeforeEach
    void setUp() {
        TestEngine.reset();
        engine = TestEngine.replica("pod-1", clock).forms(registry()).build();
        jdbc = TestEngine.jdbc();
        engine.identity.as("bank-a", "pipeline", "processes:deploy");
        engine.services.importDefinition(new ImportRequest(SourceFormat.BPMN_XML,
                TestEngine.resource("/bpmn/loan-review-form.bpmn"), null));
    }

    @AfterEach
    void stop() {
        engine.close();
    }

    private void as(String caller, Set<String> roles, String... permissions) {
        engine.identity.as("bank-a", caller, permissions).roles = roles;
    }

    private ProcessInstance startLoan(String businessKey, String idempotencyKey) {
        as("an", Set.of("credit-officer"), OFFICER);
        return engine.services.start(new StartRequest("loan-review-form", businessKey,
                Map.of("idNumber", ID_NUMBER, "decision", "APPROVE"), idempotencyKey));
    }

    private ProcessInstance.Task approve(ProcessInstance instance) {
        return instance.tasks().stream().filter(t -> t.nodeId().equals("approve")).findFirst().orElseThrow();
    }

    @Test
    void PRE_356_control_a_start_caller_without_read_gets_only_id_and_status_for_an_existing_business_key() {
        ProcessInstance first = startLoan("APP-1001", null);

        as("hoa", Set.of(), "processes:start");
        ProcessServices.Started again = engine.services.startDetailed(new StartRequest("loan-review-form", "APP-1001",
                Map.of(), "hoa-1"));

        assertThat(again.created()).isFalse();
        assertThat(again.summaryOnly()).isTrue();
        assertThat(again.instance().id()).isEqualTo(first.id());
        assertThat(again.instance().status()).isEqualTo("RUNNING");
        assertThat(again.instance().variables()).isEmpty();
        assertThat(again.instance().tokens()).isEmpty();
        assertThat(again.instance().tasks()).isEmpty();
        // the stored answer of the same Idempotency-Key is the short one too
        ProcessServices.Started replay = engine.services.startDetailed(new StartRequest("loan-review-form", "APP-1001",
                Map.of(), "hoa-1"));
        assertThat(replay.summaryOnly()).isTrue();
        assertThat(replay.instance().variables()).isEmpty();

        // a caller with processes:read still gets the instance
        as("an", Set.of("credit-officer"), OFFICER);
        ProcessServices.Started reader = engine.services.startDetailed(new StartRequest("loan-review-form",
                "APP-1001", Map.of(), "an-2"));
        assertThat(reader.created()).isFalse();
        assertThat(reader.summaryOnly()).isFalse();
        assertThat(reader.instance().tasks()).isNotEmpty();
    }

    @Test
    void PRE_357_control_a_variable_the_form_masks_is_masked_in_the_instance_and_in_the_idempotent_replay() {
        ProcessInstance started = startLoan("APP-1002", "an-start-1");

        assertThat(started.variables()).containsEntry("idNumber", "***").containsEntry("decision", "APPROVE");
        ProcessInstance read = engine.services.instance(started.id());
        assertThat(read.variables()).containsEntry("idNumber", "***").containsEntry("decision", "APPROVE");
        ProcessInstance replay = startLoan("APP-1002", "an-start-1");
        assertThat(replay.variables()).containsEntry("idNumber", "***");
        // the engine keeps the clear value for the process itself
        assertThat(jdbc.sql("SELECT variables::text FROM pre_instance WHERE id = ?").param(started.id())
                .query(String.class).single()).contains(ID_NUMBER);
    }

    @Test
    void PRE_358_control_a_task_and_its_form_are_returned_to_an_assignee_a_claimer_or_a_task_manager_and_to_nobody_else() {
        ProcessInstance started = startLoan("APP-1003", null);
        ProcessInstance.Task task = approve(started);

        as("an", Set.of("credit-officer"), OFFICER);
        assertThat(engine.services.task(task.id()).id()).isEqualTo(task.id());
        assertThat(engine.services.form(task.id()).taskId()).isEqualTo(task.id());

        as("ngoc", Set.of(), "processes:task:work", "processes:task:manage");
        assertThat(engine.services.task(task.id()).id()).isEqualTo(task.id());
        assertThat(engine.services.form(task.id()).taskId()).isEqualTo(task.id());

        as("kien", Set.of("kyc-analyst"), "processes:task:work");
        assertThatThrownBy(() -> engine.services.task(task.id())).isInstanceOf(TaskNotFoundException.class);
        assertThatThrownBy(() -> engine.services.form(task.id())).isInstanceOf(TaskNotFoundException.class);

        // the person who claimed it still reads it after the role no longer reaches it
        as("an", Set.of("credit-officer"), OFFICER);
        engine.services.claim(task.id(), task.lockVersion(), "an-claim-1");
        as("an", Set.of(), OFFICER);
        assertThat(engine.services.task(task.id()).id()).isEqualTo(task.id());
    }

    @Test
    void PRE_359_control_pre_history_refuses_update_delete_and_truncate() {
        startLoan("APP-1004", null);
        long rows = jdbc.sql("SELECT count(*) FROM pre_history").query(Long.class).single();
        assertThat(rows).isPositive();

        assertThatThrownBy(() -> jdbc.sql("UPDATE pre_history SET actor_id = 'mallory'").update())
                .hasMessageContaining("pre_history is append-only");
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM pre_history").update())
                .hasMessageContaining("pre_history is append-only");
        assertThatThrownBy(() -> jdbc.sql("TRUNCATE pre_history").update())
                .hasMessageContaining("pre_history is append-only");

        assertThat(jdbc.sql("SELECT count(*) FROM pre_history").query(Long.class).single()).isEqualTo(rows);
        assertThat(jdbc.sql("SELECT count(*) FROM pre_history WHERE actor_id = 'mallory'").query(Long.class).single())
                .isZero();
    }

    private void importAccountOpening() {
        engine.identity.as("bank-a", "pipeline", "processes:deploy");
        engine.services.importDefinition(new ImportRequest(SourceFormat.BPMN_XML, """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                                  xmlns:horus="urn:horus-bpm:bpmn:extensions:v1"
                                  id="Definitions_account-opening" targetNamespace="urn:horus-bpm:examples"
                                  expressionLanguage="urn:horus-bpm:expr:v1">
                  <bpmn:message id="Message_request" name="account-opening-request"/>
                  <bpmn:signal id="Signal_kyc" name="kyc-cleared"/>
                  <bpmn:process id="account-opening" name="Account opening" isExecutable="true">
                    <bpmn:startEvent id="start" parallelMultiple="true">
                      <bpmn:messageEventDefinition messageRef="Message_request"/>
                      <bpmn:signalEventDefinition signalRef="Signal_kyc"/>
                    </bpmn:startEvent>
                    <bpmn:userTask id="open-account" name="Open the account" horus:assignee="role:BRANCH_OFFICER" horus:resultVariable="opened"/>
                    <bpmn:endEvent id="end"/>
                    <bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="open-account"/>
                    <bpmn:sequenceFlow id="f2" sourceRef="open-account" targetRef="end"/>
                  </bpmn:process>
                </bpmn:definitions>
                """.getBytes(StandardCharsets.UTF_8), null));
    }

    private MessageDelivery request(String applicationNo) {
        engine.identity.as("bank-a", "channel-consumer", "processes:message:correlate");
        return engine.services.correlate("account-opening-request", applicationNo, Map.of("customer", "Tran Thi Mai"),
                "m-" + applicationNo + "-" + clock.instant().toEpochMilli());
    }

    private long arrivals() {
        return jdbc.sql("SELECT count(*) FROM pre_start_arrival").query(Long.class).single();
    }

    @Test
    void PRE_360_control_a_definition_holds_at_most_the_configured_arrivals_and_a_repeat_does_not_count() {
        importAccountOpening();
        engine.services.useStartArrivalSettings(new StartArrivalSettings(Duration.ofHours(24), 3));

        for (String application : List.of("APP-1", "APP-2", "APP-3")) {
            assertThat(request(application).outcome()).isEqualTo(MessageDelivery.Outcome.WAITING);
        }
        assertThatThrownBy(() -> request("APP-4")).isInstanceOf(PayloadTooLargeException.class);
        assertThat(arrivals()).isEqualTo(3);

        clock.advance(Duration.ofSeconds(1));
        assertThat(request("APP-1").outcome()).isEqualTo(MessageDelivery.Outcome.WAITING);
        assertThat(arrivals()).isEqualTo(3);
    }

    @Test
    void PRE_360_control_arrivals_older_than_the_ttl_are_deleted_by_the_cleanup() {
        importAccountOpening();
        engine.services.useStartArrivalSettings(new StartArrivalSettings(Duration.ofHours(24), 3));
        request("APP-1");
        clock.advance(Duration.ofHours(23));
        request("APP-2");
        assertThat(engine.services.purgeExpiredStartArrivals()).isZero();

        clock.advance(Duration.ofHours(2));

        assertThat(engine.services.purgeExpiredStartArrivals()).isEqualTo(1);
        assertThat(arrivals()).isEqualTo(1);
        assertThat(request("APP-5").outcome()).isEqualTo(MessageDelivery.Outcome.WAITING);
        assertThat(arrivals()).isEqualTo(2);
    }
}
