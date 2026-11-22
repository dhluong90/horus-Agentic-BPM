package io.github.dhluong90.bpm.process.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.process.api.ImportRequest;
import io.github.dhluong90.bpm.process.api.ProcessInstance;
import io.github.dhluong90.bpm.process.api.SourceFormat;
import io.github.dhluong90.bpm.process.api.StartRequest;
import io.github.dhluong90.bpm.process.internal.service.AsyncStepExecutor.Claim;
import io.github.dhluong90.bpm.process.spi.HandlerResult;
import io.github.dhluong90.bpm.process.spi.ProcessEventPublisher;
import io.github.dhluong90.bpm.process.spi.ServiceCall;
import io.github.dhluong90.bpm.process.spi.ServiceTaskHandler;
import io.github.dhluong90.bpm.process.test.TestClock;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * A process calls a child process and waits for it (#92, PRE-211 to PRE-214, PD-127, PD-144) on PostgreSQL. Account
 * opening {@code account-opening} calls {@code kyc-check} at step {@code kyc} and continues with the child's declared
 * output {@code kycLevel} only. The clock is a {@link TestClock}; jobs are fired by hand; no test sleeps.
 */
class CallActivityPostgresIT {

    private static final String[] PIPELINE = {"processes:deploy", "processes:start", "processes:read"};
    private static final String[] OPERATOR = {"processes:incident:manage", "processes:read", "processes:cancel"};
    private static final Instant T0 = Instant.parse("2027-03-01T02:00:00Z");

    private static final String KYC_CHECK = """
            <bpmn:startEvent id="start" name="Check requested"/>
            <bpmn:userTask id="check" name="KYC check" horus:assignee="role:KYC_ANALYST" horus:resultVariable="check"/>
            <bpmn:endEvent id="end" name="Checked"/>
            <bpmn:sequenceFlow id="start__check" sourceRef="start" targetRef="check"/>
            <bpmn:sequenceFlow id="check__end" sourceRef="check" targetRef="end"/>
            """;
    private static final String ACCOUNT_OPENING = """
            <bpmn:startEvent id="start" name="Application received"/>
            <bpmn:callActivity id="kyc" name="KYC check" calledElement="kyc-check" %s>
              <bpmn:extensionElements>
                <horus:input name="customerId" value="${instance.customerId}"/>
                <horus:input name="segment" value="${instance.segment}"/>
                <horus:output name="kycLevel" from="check.kycLevel"/>
              </bpmn:extensionElements>
            </bpmn:callActivity>
            <bpmn:userTask id="review" name="Account review" horus:assignee="role:OPS" horus:resultVariable="review"/>
            <bpmn:endEvent id="end" name="Opened"/>
            <bpmn:sequenceFlow id="start__kyc" sourceRef="start" targetRef="kyc"/>
            <bpmn:sequenceFlow id="kyc__review" sourceRef="kyc" targetRef="review"/>
            <bpmn:sequenceFlow id="review__end" sourceRef="review" targetRef="end"/>
            """;
    /** A process whose first step calls itself, to meet the depth limit. */
    private static final String PING = """
            <bpmn:startEvent id="start" name="Start"/>
            <bpmn:callActivity id="again" name="Call myself" calledElement="ping"/>
            <bpmn:endEvent id="end" name="End"/>
            <bpmn:sequenceFlow id="start__again" sourceRef="start" targetRef="again"/>
            <bpmn:sequenceFlow id="again__end" sourceRef="again" targetRef="end"/>
            """;
    /** A saga that calls kyc-check, declares its undo, and then fails on a business error. */
    private static final String ONBOARD_SAGA = """
            <bpmn:startEvent id="start" name="Application received"/>
            <bpmn:transaction id="onboard" name="Onboard">
              <bpmn:startEvent id="onboard-start"/>
              <bpmn:callActivity id="kyc" name="KYC check" calledElement="kyc-check" horus:compensate="reverse-kyc">
                <bpmn:extensionElements>
                  <horus:input name="customerId" value="${instance.customerId}"/>
                  <horus:output name="kycLevel" from="check.kycLevel"/>
                </bpmn:extensionElements>
              </bpmn:callActivity>
              <bpmn:serviceTask id="open-account" name="Open account" horus:handler="open-account" horus:onFailure="compensate"/>
              <bpmn:endEvent id="onboard-end"/>
              <bpmn:sequenceFlow id="o1" sourceRef="onboard-start" targetRef="kyc"/>
              <bpmn:sequenceFlow id="o2" sourceRef="kyc" targetRef="open-account"/>
              <bpmn:sequenceFlow id="o3" sourceRef="open-account" targetRef="onboard-end"/>
            </bpmn:transaction>
            <bpmn:endEvent id="end" name="Opened"/>
            <bpmn:sequenceFlow id="s1" sourceRef="start" targetRef="onboard"/>
            <bpmn:sequenceFlow id="s2" sourceRef="onboard" targetRef="end"/>
            """;

    private final TestClock clock = TestClock.at(T0);
    private final List<ServiceCall> undos = new CopyOnWriteArrayList<>();
    private TestEngine engine;
    private JdbcClient jdbc;

    @BeforeEach
    void setUp() {
        TestEngine.reset();
        engine = TestEngine.replica("pod-1", clock).handlers(
                handler("reverse-kyc", call -> {
                    undos.add(call);
                    return HandlerResult.completed(Map.of("reversed", true));
                }),
                handler("open-account", call -> new HandlerResult.BusinessError("ACCOUNT_BLOCKED", "blocked"))).build();
        jdbc = TestEngine.jdbc();
    }

    @AfterEach
    void stop() {
        engine.close();
    }

    private static ServiceTaskHandler handler(String name, Function<ServiceCall, HandlerResult> body) {
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

    private static byte[] bpmn(String key, String body) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                                  xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                                  xmlns:horus="urn:horus-bpm:bpmn:extensions:v1"
                                  id="Definitions_%s" targetNamespace="urn:horus-bpm:examples"
                                  expressionLanguage="urn:horus-bpm:expr:v1">
                  <bpmn:process id="%s" name="%s" isExecutable="true">
                %s
                  </bpmn:process>
                </bpmn:definitions>
                """.formatted(key, key, key, body).getBytes(StandardCharsets.UTF_8);
    }

    private void deploy(String tenant, String key, String body) {
        engine.identity.as(tenant, "pipeline", PIPELINE);
        engine.services.importDefinition(new ImportRequest(SourceFormat.BPMN_XML, bpmn(key, body), null));
    }

    private ProcessInstance start(String tenant, String key, String businessKey) {
        engine.identity.as(tenant, "officer-lan", PIPELINE);
        return engine.services.start(new StartRequest(key, businessKey, Map.of("customerId", "C-778", "segment",
                "RETAIL"), null));
    }

    private String child(String parentId) {
        return jdbc.sql("SELECT id FROM pre_instance WHERE parent_instance_id = ? ORDER BY call_attempt DESC, id")
                .param(parentId).query(String.class).list().getFirst();
    }

    private long count(String sql, Object... params) {
        return jdbc.sql(sql).params(params).query(Long.class).single();
    }

    private String text(String sql, Object... params) {
        return jdbc.sql(sql).params(params).query(String.class).single();
    }

    /** Completes the open user task of an instance as a person of the given role. */
    private void completeTask(String tenant, String instanceId, String role, Map<String, Object> result) {
        engine.identity.as(tenant, "analyst-" + role, "processes:task:work", "processes:read").roles = Set.of(role);
        String task = text("SELECT id FROM pre_task WHERE tenant_id = ? AND instance_id = ? AND status = 'OPEN'", tenant,
                instanceId);
        long lock = engine.services.claim(task, count("SELECT lock_version FROM pre_task WHERE tenant_id = ? AND id = ?",
                tenant, task), "claim-" + task).lockVersion();
        engine.services.complete(task, result, lock, "complete-" + task);
    }

    private int fireDueJobs() {
        return engine.jobs.pollOnce();
    }

    @Test
    void PRE_211_a_parent_waits_for_its_child_and_continues_with_the_childs_declared_outputs_only() {
        // Given account-opening with a callProcess step kyc, and kyc-check effective
        deploy("bank-a", "kyc-check", KYC_CHECK);
        deploy("bank-a", "account-opening", ACCOUNT_OPENING.formatted(""));

        // When P1 (customer C-778, segment RETAIL, business key AO-2026-1201) reaches kyc
        ProcessInstance p1 = start("bank-a", "account-opening", "AO-2026-1201");

        // Then its token waits as WAITING_CHILD and child C1 starts on kyc-check v1, with only the declared inputs
        assertThat(text("SELECT status FROM pre_token WHERE instance_id = ? AND current_node_id = 'kyc'", p1.id()))
                .isEqualTo("WAITING_CHILD");
        String c1 = child(p1.id());
        assertThat(text("SELECT definition_key || '/' || definition_ver || '/' || business_key || '/' || call_attempt "
                + "|| '/' || call_depth FROM pre_instance WHERE id = ?", c1)).isEqualTo("kyc-check/1/AO-2026-1201/kyc/0/1");
        assertThat(text("SELECT parent_instance_id || '/' || parent_token_id FROM pre_instance WHERE id = ?", c1))
                .startsWith(p1.id() + "/");
        assertThat(text("SELECT variables::text FROM pre_instance WHERE id = ?", c1)).contains("C-778")
                .contains("RETAIL");
        assertThat(count("SELECT count(*) FROM pre_history WHERE instance_id = ? AND event_type = 'CHILD_STARTED'",
                p1.id())).isOne();

        // When the child sets kycLevel 2 and reviewNote, and ends
        completeTask("bank-a", c1, "KYC_ANALYST", Map.of("kycLevel", 2, "reviewNote", "ok"));

        // Then a CALL_RESULT job for P1 is due, and P1 still waits: the child's end never touched the parent
        assertThat(text("SELECT status FROM pre_instance WHERE id = ?", c1)).isEqualTo("COMPLETED");
        assertThat(count("SELECT count(*) FROM pre_timer WHERE instance_id = ? AND job_kind = 'CALL_RESULT' AND status "
                + "= 'DUE'", p1.id())).isOne();
        assertThat(text("SELECT status FROM pre_token WHERE instance_id = ? AND current_node_id = 'kyc'", p1.id()))
                .isEqualTo("WAITING_CHILD");

        // When the job fires, in a transaction of its own
        assertThat(fireDueJobs()).isOne();

        // Then P1 has kycLevel 2 and no reviewNote, and its token moved on to the review task
        ProcessInstance after = engine.services.instance(p1.id());
        assertThat(after.variables()).containsEntry("kycLevel", 2L).doesNotContainKey("reviewNote");
        assertThat(count("SELECT count(*) FROM pre_task WHERE instance_id = ? AND node_id = 'review' AND status = 'OPEN'",
                p1.id())).isOne();
        assertThat(count("SELECT count(*) FROM pre_history WHERE instance_id = ? AND event_type = 'CHILD_COMPLETED' "
                + "AND details->>'childInstanceId' = ?", p1.id(), c1)).isOne();
    }

    @Test
    void PRE_211_a_pinned_version_is_used_even_when_a_later_one_is_effective_and_a_retired_one_raises_an_incident() {
        // Given kyc-check v1 pinned by the step, then v2 effective
        deploy("bank-a", "kyc-check", KYC_CHECK);
        deploy("bank-a", "account-opening", ACCOUNT_OPENING.formatted("horus:calledVersion=\"1\""));
        deploy("bank-a", "kyc-check", KYC_CHECK.replace("KYC check", "KYC check v2"));

        // When P1 reaches kyc
        ProcessInstance p1 = start("bank-a", "account-opening", "AO-2026-1202");

        // Then the child starts on version 1, which v2 has retired: NO_EFFECTIVE_VERSION, and no child
        assertThat(count("SELECT count(*) FROM pre_instance WHERE parent_instance_id = ?", p1.id())).isZero();
        assertThat(text("SELECT error_code || ':' || node_id || ':' || status FROM pre_incident WHERE instance_id = ?",
                p1.id())).isEqualTo("NO_EFFECTIVE_VERSION:kyc:OPEN");
        assertThat(text("SELECT status FROM pre_token WHERE instance_id = ? AND current_node_id = 'kyc'", p1.id()))
                .isEqualTo("WAITING_CHILD");
    }

    @Test
    void PRE_212_a_second_child_for_the_same_token_and_attempt_is_refused_by_the_database() {
        // Given P1 with its child C1 started for the kyc token, attempt 0
        deploy("bank-a", "kyc-check", KYC_CHECK);
        deploy("bank-a", "account-opening", ACCOUNT_OPENING.formatted(""));
        ProcessInstance p1 = start("bank-a", "account-opening", "AO-2026-1203");
        String token = text("SELECT parent_token_id FROM pre_instance WHERE parent_instance_id = ?", p1.id());
        JdbcProcessStore store = new JdbcProcessStore(TestEngine.dataSource());

        // When another replica tries to link a second child to the same token and attempt
        // Then the unique index refuses it, and the token has exactly one child
        assertThat(store.callAttempts("bank-a", token)).isOne();
        assertThatThrownBy(() -> jdbc.sql("INSERT INTO pre_instance (tenant_id, id, definition_id, definition_key, "
                + "definition_ver, status, variables, started_at, parent_instance_id, parent_token_id, call_attempt) "
                + "SELECT tenant_id, '01TESTDUPLICATECHILD000000', definition_id, definition_key, definition_ver, "
                + "status, variables, started_at, parent_instance_id, parent_token_id, call_attempt FROM pre_instance "
                + "WHERE parent_instance_id = ?").param(p1.id()).update()).isInstanceOf(DataAccessException.class);
        assertThat(count("SELECT count(*) FROM pre_instance WHERE parent_token_id = ?", token)).isOne();
    }

    @Test
    void PRE_212_the_chain_of_calls_stops_at_the_depth_limit_with_an_incident_and_no_child() {
        // Given ping, whose first step calls ping, and calls.max-depth at its default of 5
        deploy("bank-a", "ping", PING);

        // When ping is started
        ProcessInstance root = start("bank-a", "ping", null);

        // Then the root and five calls exist, and the step that would start depth 6 raises CALL_DEPTH_EXCEEDED
        assertThat(count("SELECT count(*) FROM pre_instance WHERE tenant_id = 'bank-a'")).isEqualTo(6);
        assertThat(count("SELECT max(call_depth) FROM pre_instance WHERE tenant_id = 'bank-a'")).isEqualTo(5);
        assertThat(text("SELECT i.error_code || ':' || n.call_depth FROM pre_incident i JOIN pre_instance n ON n.tenant_id"
                + " = i.tenant_id AND n.id = i.instance_id WHERE i.tenant_id = 'bank-a'")).isEqualTo("CALL_DEPTH_EXCEEDED:5");
        assertThat(root.status()).isEqualTo("RUNNING");
    }

    @Test
    void PRE_212_a_called_element_with_no_effective_version_raises_an_incident_at_run_time() {
        // Given account-opening deployed before kyc-check exists: a warning at approval, never a refusal
        deploy("bank-a", "account-opening", ACCOUNT_OPENING.formatted(""));

        // When P1 reaches kyc
        ProcessInstance p1 = start("bank-a", "account-opening", "AO-2026-1204");

        // Then the step raises NO_EFFECTIVE_VERSION and no child starts
        assertThat(text("SELECT error_code FROM pre_incident WHERE instance_id = ?", p1.id()))
                .isEqualTo("NO_EFFECTIVE_VERSION");
        assertThat(count("SELECT count(*) FROM pre_instance WHERE parent_instance_id = ?", p1.id())).isZero();
    }

    @Test
    void PRE_213_a_cancelled_child_fails_the_parent_step_and_a_retry_starts_a_new_child_keyed_by_the_retry_count() {
        // Given P1 waiting on C1, which an operator cancels for a duplicate case
        deploy("bank-a", "kyc-check", KYC_CHECK);
        deploy("bank-a", "account-opening", ACCOUNT_OPENING.formatted(""));
        ProcessInstance p1 = start("bank-a", "account-opening", "AO-2026-1205");
        String c1 = child(p1.id());
        engine.identity.as("bank-a", "ops.lan", OPERATOR);
        ProcessInstance c1Now = engine.services.instance(c1);
        engine.services.cancel(c1, c1Now.lockVersion(), "duplicate case", "cancel-c1");

        // When the call result fires
        assertThat(fireDueJobs()).isOne();

        // Then the step fails with CHILD_CANCELLED and the reason in the cause, as an incident
        assertThat(text("SELECT error_code || ':' || node_id FROM pre_incident WHERE instance_id = ?", p1.id()))
                .isEqualTo("CHILD_CANCELLED:kyc");
        assertThat(text("SELECT cause_summary FROM pre_incident WHERE instance_id = ?", p1.id()))
                .contains("duplicate case");

        // When an operator retries the incident
        String incident = text("SELECT id FROM pre_incident WHERE instance_id = ?", p1.id());
        long lock = count("SELECT lock_version FROM pre_incident WHERE id = ?", incident);
        engine.services.retry(incident, lock, "Customer confirmed the case is not a duplicate", "retry-1");

        // Then a new child starts for attempt 1, and the old child's result can no longer move the parent
        assertThat(text("SELECT call_attempt || ':' || business_key || ':' || status FROM pre_instance WHERE "
                + "parent_instance_id = ? AND call_attempt = 1", p1.id())).isEqualTo("1:AO-2026-1205/kyc#1:RUNNING");
        assertThat(count("SELECT count(*) FROM pre_instance WHERE parent_instance_id = ?", p1.id())).isEqualTo(2);
        assertThat(text("SELECT status FROM pre_token WHERE instance_id = ? AND current_node_id = 'kyc'", p1.id()))
                .isEqualTo("WAITING_CHILD");
    }

    @Test
    void PRE_213_a_child_that_ended_failed_fails_the_parent_step_with_child_failed() {
        // Given P1 waiting on C1, which ended FAILED (as a compensated child does), with its call result due
        deploy("bank-a", "kyc-check", KYC_CHECK);
        deploy("bank-a", "account-opening", ACCOUNT_OPENING.formatted(""));
        ProcessInstance p1 = start("bank-a", "account-opening", "AO-2026-1206");
        String c1 = child(p1.id());
        jdbc.sql("UPDATE pre_instance SET status = 'FAILED', ended_at = now() WHERE id = ?").param(c1).update();
        String definition = text("SELECT definition_id FROM pre_instance WHERE id = ?", p1.id());
        String token = text("SELECT parent_token_id FROM pre_instance WHERE id = ?", c1);
        jdbc.sql("INSERT INTO pre_timer (tenant_id, id, instance_id, definition_id, token_id, job_kind, status, "
                + "next_fire_at, lease_duration, max_attempts, payload, created_at) VALUES ('bank-a', "
                + "'01TESTCALLRESULT00000000', ?, ?, ?, 'CALL_RESULT', 'DUE', ?, interval '1 minute', 3, "
                + "?::jsonb, ?)").params(p1.id(), definition, token, java.sql.Timestamp.from(T0),
                "{\"childInstanceId\":\"" + c1 + "\",\"outcome\":\"FAILED\"}", java.sql.Timestamp.from(T0)).update();

        // When the call result fires
        assertThat(fireDueJobs()).isOne();

        // Then the parent step has a CHILD_FAILED incident and the token stays on the step
        assertThat(text("SELECT error_code || ':' || node_id || ':' || status FROM pre_incident WHERE instance_id = ?",
                p1.id())).isEqualTo("CHILD_FAILED:kyc:OPEN");
    }

    @Test
    void PRE_213_a_cancel_of_the_parent_cancels_its_child_in_the_same_transaction_with_the_parent_cancelled_reason() {
        // Given P1 waiting on C1, whose KYC task is open
        deploy("bank-a", "kyc-check", KYC_CHECK);
        deploy("bank-a", "account-opening", ACCOUNT_OPENING.formatted(""));
        ProcessInstance p1 = start("bank-a", "account-opening", "AO-2026-1207");
        String c1 = child(p1.id());
        engine.events.clear();

        // When P1 is cancelled by ops.lan
        engine.identity.as("bank-a", "ops.lan", OPERATOR);
        engine.services.cancel(p1.id(), engine.services.instance(p1.id()).lockVersion(), "customer withdrew", "cancel-p1");

        // Then both are CANCELLED, the child's task is CANCELLED, and an event is published for each
        assertThat(text("SELECT status FROM pre_instance WHERE id = ?", p1.id())).isEqualTo("CANCELLED");
        assertThat(text("SELECT status FROM pre_instance WHERE id = ?", c1)).isEqualTo("CANCELLED");
        assertThat(count("SELECT count(*) FROM pre_task WHERE instance_id = ? AND status = 'CANCELLED'", c1)).isOne();
        List<ProcessEventPublisher.Event> cancelled = engine.events.stream()
                .filter(e -> "processes.instance.cancelled.v1".equals(e.type())).toList();
        assertThat(cancelled).hasSize(2);
        assertThat(cancelled).allSatisfy(e -> assertThat(e.payload()).containsEntry("cancelledBy", "ops.lan"));
        assertThat(cancelled).filteredOn(e -> c1.equals(e.payload().get("instanceId"))).singleElement()
                .satisfies(e -> assertThat(e.payload()).containsEntry("reason", "parent cancelled"));
    }

    @Test
    void PRE_214_a_completed_child_is_undone_only_by_the_call_steps_own_handler() {
        // Given a saga whose call step kyc declares reverse-kyc and has completed with child C1, and a later failure
        deploy("bank-a", "kyc-check", KYC_CHECK);
        deploy("bank-a", "onboarding", ONBOARD_SAGA);
        ProcessInstance p1 = start("bank-a", "onboarding", "AO-2026-1208");
        String c1 = child(p1.id());
        completeTask("bank-a", c1, "KYC_ANALYST", Map.of("kycLevel", 2));
        assertThat(fireDueJobs()).isOne();
        // The next step open-account fails with a business error, so the saga is undone
        assertThat(text("SELECT status FROM pre_instance WHERE id = ?", p1.id())).isEqualTo("COMPENSATING");

        // When the walk reaches kyc
        for (Claim claim : engine.async.claim(5)) {
            engine.async.runNow(claim);
        }

        // Then reverse-kyc ran once, with the forward attempt and the child's id, and C1's own stack was not walked
        assertThat(undos).singleElement().satisfies(call -> {
            assertThat(call.inputs()).containsEntry("childInstanceId", c1);
            assertThat(call.forwardAttemptId()).isNotBlank();
        });
        assertThat(text("SELECT status FROM pre_instance WHERE id = ?", p1.id())).isEqualTo("FAILED");
        assertThat(text("SELECT status FROM pre_instance WHERE id = ?", c1)).isEqualTo("COMPLETED");
        assertThat(count("SELECT count(*) FROM pre_compensation_stack WHERE instance_id = ?", c1)).isZero();
    }

    @Test
    void PRE_214_a_child_is_looked_up_only_in_the_parents_tenant() {
        // Given kyc-check deployed in bank-b only, and account-opening in bank-a
        deploy("bank-b", "kyc-check", KYC_CHECK);
        deploy("bank-a", "account-opening", ACCOUNT_OPENING.formatted(""));

        // When P1 of bank-a reaches kyc
        ProcessInstance p1 = start("bank-a", "account-opening", "AO-2026-1209");

        // Then the step raises NO_EFFECTIVE_VERSION and no instance exists in either tenant but P1
        assertThat(text("SELECT error_code FROM pre_incident WHERE instance_id = ?", p1.id()))
                .isEqualTo("NO_EFFECTIVE_VERSION");
        assertThat(count("SELECT count(*) FROM pre_instance WHERE tenant_id = 'bank-b'")).isZero();
        assertThat(count("SELECT count(*) FROM pre_instance WHERE tenant_id = 'bank-a'")).isOne();
    }
}
