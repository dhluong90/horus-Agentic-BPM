package io.github.dhluong90.bpm.process.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.process.api.ImportRequest;
import io.github.dhluong90.bpm.process.api.PermissionDeniedException;
import io.github.dhluong90.bpm.process.api.PreconditionRequiredException;
import io.github.dhluong90.bpm.process.api.ProcessInstance;
import io.github.dhluong90.bpm.process.api.SignalBroadcast;
import io.github.dhluong90.bpm.process.api.SignalRequest;
import io.github.dhluong90.bpm.process.api.SourceFormat;
import io.github.dhluong90.bpm.process.api.StartRequest;
import io.github.dhluong90.bpm.process.test.TestClock;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Signal events on PostgreSQL (#492). Card pricing watches the signal {@code rate-card-updated}: every waiting instance
 * of the tenant that threw or received it continues, no instance of another tenant does, and a retried broadcast wakes
 * nobody twice. Jobs are fired by hand; no test sleeps.
 */
class SignalEventsPostgresIT {

    private static final String[] PIPELINE = {"processes:deploy", "processes:start", "processes:read"};
    private static final String[] BROADCASTER = {"processes:signal:broadcast"};
    private static final Instant T0 = Instant.parse("2027-03-01T02:00:00Z");

    private static final String RATE_WATCHER = """
            <bpmn:startEvent id="start"/>
            <bpmn:intermediateCatchEvent id="wait-rate-card" name="Rate card updated">
              <bpmn:signalEventDefinition signalRef="Signal_rate"/>
            </bpmn:intermediateCatchEvent>
            <bpmn:userTask id="reprice" name="Reprice" horus:assignee="role:PRICING_OFFICER" horus:resultVariable="repriced"/>
            <bpmn:endEvent id="end"/>
            <bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="wait-rate-card"/>
            <bpmn:sequenceFlow id="f2" sourceRef="wait-rate-card" targetRef="reprice"/>
            <bpmn:sequenceFlow id="f3" sourceRef="reprice" targetRef="end"/>
            """;
    private static final String RATE_PUBLISHER = """
            <bpmn:startEvent id="start"/>
            <bpmn:intermediateThrowEvent id="announce" name="Announce the new rate card">
              <bpmn:signalEventDefinition signalRef="Signal_rate"/>
            </bpmn:intermediateThrowEvent>
            <bpmn:endEvent id="end"/>
            <bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="announce"/>
            <bpmn:sequenceFlow id="f2" sourceRef="announce" targetRef="end"/>
            """;
    private static final String REPRICING_ON_SIGNAL = """
            <bpmn:startEvent id="start" name="Rate card updated">
              <bpmn:signalEventDefinition signalRef="Signal_rate"/>
            </bpmn:startEvent>
            <bpmn:userTask id="reprice" name="Reprice" horus:assignee="role:PRICING_OFFICER" horus:resultVariable="repriced"/>
            <bpmn:endEvent id="end"/>
            <bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="reprice"/>
            <bpmn:sequenceFlow id="f2" sourceRef="reprice" targetRef="end"/>
            """;

    private final TestClock clock = TestClock.at(T0);
    private TestEngine engine;
    private JdbcClient jdbc;

    @BeforeEach
    void setUp() {
        TestEngine.reset();
        engine = TestEngine.replica("pod-1", clock).build();
        jdbc = TestEngine.jdbc();
    }

    @AfterEach
    void stop() {
        engine.close();
    }

    private static byte[] bpmn(String key, String body) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                                  xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                                  xmlns:horus="urn:horus-bpm:bpmn:extensions:v1"
                                  id="Definitions_%s" targetNamespace="urn:horus-bpm:examples"
                                  expressionLanguage="urn:horus-bpm:expr:v1">
                  <bpmn:signal id="Signal_rate" name="rate-card-updated"/>
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
        return engine.services.start(new StartRequest(key, businessKey, Map.of(), null));
    }

    private SignalBroadcast broadcast(String tenant, String name, String key) {
        engine.identity.as(tenant, "pricing-admin", BROADCASTER);
        return engine.services.broadcast(new SignalRequest(name, key));
    }

    private long count(String sql, Object... params) {
        return jdbc.sql(sql).params(params).query(Long.class).single();
    }

    private String tokenStatus(String instanceId) {
        return jdbc.sql("SELECT status FROM pre_token WHERE instance_id = ? AND current_node_id = 'wait-rate-card'")
                .param(instanceId).query(String.class).single();
    }

    @Test
    void PRE_270_a_throw_wakes_every_waiter_of_its_tenant_by_job_and_never_a_waiter_of_another_tenant() {
        // Given three instances of bank-a and one of bank-b waiting for rate-card-updated, and a publisher in bank-a
        deploy("bank-a", "rate-watcher", RATE_WATCHER);
        deploy("bank-b", "rate-watcher", RATE_WATCHER);
        deploy("bank-a", "rate-publisher", RATE_PUBLISHER);
        ProcessInstance w1 = start("bank-a", "rate-watcher", "W-1");
        ProcessInstance w2 = start("bank-a", "rate-watcher", "W-2");
        ProcessInstance w3 = start("bank-a", "rate-watcher", "W-3");
        ProcessInstance other = start("bank-b", "rate-watcher", "W-9");
        assertThat(tokenStatus(w1.id())).isEqualTo("WAITING_EVENT");
        assertThat(count("SELECT count(*) FROM pre_event_subscription WHERE tenant_id = 'bank-a'")).isEqualTo(3);

        // When the publisher throws the signal
        ProcessInstance publisher = start("bank-a", "rate-publisher", "P-1");

        // Then the thrower continued at once and finished, and the waiters are not woken inline: three jobs are due
        assertThat(engine.services.instance(publisher.id()).status()).isEqualTo("COMPLETED");
        assertThat(count("SELECT count(*) FROM pre_timer WHERE job_kind = 'SIGNAL_DELIVERY' AND status = 'DUE' "
                + "AND tenant_id = 'bank-a'")).isEqualTo(3);
        assertThat(count("SELECT count(*) FROM pre_timer WHERE job_kind = 'SIGNAL_DELIVERY' AND tenant_id = 'bank-b'"))
                .isZero();
        assertThat(tokenStatus(w1.id())).isEqualTo("WAITING_EVENT");
        assertThat(count("SELECT (details->>'waiters')::int FROM pre_history WHERE instance_id = ? "
                + "AND event_type = 'SIGNAL_THROWN'", publisher.id())).isEqualTo(3);

        // When the jobs fire
        assertThat(engine.jobs.pollOnce()).isEqualTo(3);

        // Then all three of bank-a continue to the repricing task, their subscriptions are gone, and bank-b still waits
        for (ProcessInstance waiter : new ProcessInstance[] {w1, w2, w3}) {
            assertThat(count("SELECT count(*) FROM pre_task WHERE instance_id = ? AND node_id = 'reprice' "
                    + "AND status = 'OPEN'", waiter.id())).isOne();
        }
        assertThat(count("SELECT count(*) FROM pre_event_subscription WHERE tenant_id = 'bank-a'")).isZero();
        assertThat(tokenStatus(other.id())).isEqualTo("WAITING_EVENT");
        assertThat(count("SELECT count(*) FROM pre_task WHERE instance_id = ?", other.id())).isZero();
    }

    @Test
    void PRE_273_a_broadcast_from_the_api_reaches_its_own_tenant_only_and_a_retry_wakes_nobody_twice() {
        deploy("bank-a", "rate-watcher", RATE_WATCHER);
        deploy("bank-b", "rate-watcher", RATE_WATCHER);
        ProcessInstance a = start("bank-a", "rate-watcher", "W-1");
        ProcessInstance b = start("bank-b", "rate-watcher", "W-9");

        // When bank-b's operator broadcasts with key K1, then retries with K1
        SignalBroadcast first = broadcast("bank-b", "rate-card-updated", "K1");
        SignalBroadcast retry = broadcast("bank-b", "rate-card-updated", "K1");

        // Then the retry returns the first answer, one signal and one job exist, and bank-a is untouched
        assertThat(first.waiters()).isOne();
        assertThat(retry).isEqualTo(first);
        assertThat(count("SELECT count(*) FROM pre_signal WHERE name = 'rate-card-updated'")).isOne();
        assertThat(count("SELECT count(*) FROM pre_timer WHERE job_kind = 'SIGNAL_DELIVERY'")).isOne();
        assertThat(engine.jobs.pollOnce()).isOne();
        assertThat(count("SELECT count(*) FROM pre_task WHERE instance_id = ?", b.id())).isOne();
        assertThat(count("SELECT count(*) FROM pre_task WHERE instance_id = ?", a.id())).isZero();
        assertThat(tokenStatus(a.id())).isEqualTo("WAITING_EVENT");
    }

    @Test
    void PRE_273_a_broadcast_needs_its_permission_and_an_idempotency_key() {
        engine.identity.as("bank-a", "officer-lan", PIPELINE);
        assertThatThrownBy(() -> engine.services.broadcast(new SignalRequest("rate-card-updated", "K1")))
                .isInstanceOf(PermissionDeniedException.class);
        engine.identity.as("bank-a", "pricing-admin", BROADCASTER);
        assertThatThrownBy(() -> engine.services.broadcast(new SignalRequest("rate-card-updated", null)))
                .isInstanceOf(PreconditionRequiredException.class);
        assertThat(count("SELECT count(*) FROM pre_signal")).isZero();
    }

    @Test
    void PRE_273_a_signal_start_event_starts_one_instance_for_a_broadcast_of_its_tenant_and_a_retry_starts_none() {
        deploy("bank-a", "repricing-on-signal", REPRICING_ON_SIGNAL);

        SignalBroadcast first = broadcast("bank-a", "rate-card-updated", "K1");
        SignalBroadcast retry = broadcast("bank-a", "rate-card-updated", "K1");
        SignalBroadcast elsewhere = broadcast("bank-b", "rate-card-updated", "K2");

        assertThat(first.startedInstances()).hasSize(1);
        assertThat(retry).isEqualTo(first);
        assertThat(elsewhere.startedInstances()).isEmpty();
        assertThat(count("SELECT count(*) FROM pre_instance WHERE definition_key = 'repricing-on-signal'")).isOne();
        assertThat(count("SELECT count(*) FROM pre_task WHERE instance_id = ? AND node_id = 'reprice' AND status = 'OPEN'",
                first.startedInstances().get(0))).isOne();
    }

    @Test
    void PRE_270_a_cancelled_waiter_leaves_no_subscription_and_is_not_woken() {
        deploy("bank-a", "rate-watcher", RATE_WATCHER);
        ProcessInstance w1 = start("bank-a", "rate-watcher", "W-1");
        engine.identity.as("bank-a", "operator", "processes:cancel", "processes:read");

        engine.services.cancel(w1.id(), engine.services.instance(w1.id()).lockVersion(), "Customer withdrew", "C-1");
        SignalBroadcast sent = broadcast("bank-a", "rate-card-updated", "K1");

        assertThat(sent.waiters()).isZero();
        assertThat(count("SELECT count(*) FROM pre_event_subscription")).isZero();
    }
}
