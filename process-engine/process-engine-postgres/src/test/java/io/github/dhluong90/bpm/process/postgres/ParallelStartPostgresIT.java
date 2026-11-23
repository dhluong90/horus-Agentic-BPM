package io.github.dhluong90.bpm.process.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dhluong90.bpm.process.api.ImportRequest;
import io.github.dhluong90.bpm.process.api.MessageService.MessageDelivery;
import io.github.dhluong90.bpm.process.api.SignalBroadcast;
import io.github.dhluong90.bpm.process.api.SignalRequest;
import io.github.dhluong90.bpm.process.api.SourceFormat;
import io.github.dhluong90.bpm.process.test.TestClock;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Parallel multiple process start (PD-149) on PostgreSQL. Opening a current account waits for two triggers: the
 * message {@code account-opening-request} (its correlation key is the application number) and the signal
 * {@code kyc-cleared} from the screening desk. The first to arrive is held; the second starts exactly one instance, and
 * a repeat of either starts no second one. No test sleeps.
 */
class ParallelStartPostgresIT {

    private static final Instant T0 = Instant.parse("2027-03-01T02:00:00Z");
    private static final String ACCOUNT_OPENING = """
            <bpmn:startEvent id="start" parallelMultiple="true">
              <bpmn:messageEventDefinition messageRef="Message_request"/>
              <bpmn:signalEventDefinition signalRef="Signal_kyc"/>
            </bpmn:startEvent>
            <bpmn:userTask id="open-account" name="Open the account" horus:assignee="role:BRANCH_OFFICER" horus:resultVariable="opened"/>
            <bpmn:endEvent id="end"/>
            <bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="open-account"/>
            <bpmn:sequenceFlow id="f2" sourceRef="open-account" targetRef="end"/>
            """;

    private final TestClock clock = TestClock.at(T0);
    private TestEngine engine;
    private JdbcClient jdbc;

    @BeforeEach
    void setUp() {
        TestEngine.reset();
        engine = TestEngine.replica("pod-1", clock).build();
        jdbc = TestEngine.jdbc();
        engine.identity.as("bank-a", "pipeline", "processes:deploy", "processes:read");
        engine.services.importDefinition(new ImportRequest(SourceFormat.BPMN_XML, ("""
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                                  xmlns:horus="urn:horus-bpm:bpmn:extensions:v1"
                                  id="Definitions_account-opening" targetNamespace="urn:horus-bpm:examples"
                                  expressionLanguage="urn:horus-bpm:expr:v1">
                  <bpmn:message id="Message_request" name="account-opening-request"/>
                  <bpmn:signal id="Signal_kyc" name="kyc-cleared"/>
                  <bpmn:process id="account-opening" name="Account opening" isExecutable="true">
                %s
                  </bpmn:process>
                </bpmn:definitions>
                """).formatted(ACCOUNT_OPENING).getBytes(StandardCharsets.UTF_8), null));
    }

    @AfterEach
    void stop() {
        engine.close();
    }

    private MessageDelivery request(String applicationNo, String idempotencyKey) {
        engine.identity.as("bank-a", "channel-consumer", "processes:message:correlate");
        return engine.services.correlate("account-opening-request", applicationNo, Map.of("customer", "Tran Thi Mai"),
                idempotencyKey);
    }

    private SignalBroadcast kycCleared(String idempotencyKey) {
        engine.identity.as("bank-a", "screening-desk", "processes:signal:broadcast");
        return engine.services.broadcast(new SignalRequest("kyc-cleared", idempotencyKey));
    }

    private long instances() {
        return jdbc.sql("SELECT count(*) FROM pre_instance WHERE definition_key = 'account-opening'")
                .query(Long.class).single();
    }

    @Test
    void PRE_PD149_the_instance_starts_only_when_the_message_and_the_signal_have_both_arrived() {
        // Given application APP-2026-0042 whose request message arrives before the KYC signal
        MessageDelivery first = request("APP-2026-0042", "m-1");

        // Then it is held and nothing started
        assertThat(first.outcome()).isEqualTo(MessageDelivery.Outcome.WAITING);
        assertThat(first.instanceId()).isNull();
        assertThat(instances()).isZero();

        // When the signal comes
        SignalBroadcast signal = kycCleared("s-1");

        // Then exactly one instance starts, with the application number as business key and the message payload
        assertThat(signal.startedInstances()).hasSize(1);
        assertThat(instances()).isOne();
        assertThat(jdbc.sql("SELECT business_key FROM pre_instance WHERE id = ?").param(signal.startedInstances().get(0))
                .query(String.class).single()).isEqualTo("APP-2026-0042");
        assertThat(jdbc.sql("SELECT count(*) FROM pre_task WHERE instance_id = ? AND node_id = 'open-account'"
                + " AND status = 'OPEN'").param(signal.startedInstances().get(0)).query(Long.class).single()).isOne();
        assertThat(jdbc.sql("SELECT count(*) FROM pre_start_arrival").query(Long.class).single()).isZero();

        // And a replay of the message with its key answers as before and starts nothing more
        assertThat(request("APP-2026-0042", "m-1").outcome()).isEqualTo(MessageDelivery.Outcome.WAITING);
        assertThat(instances()).isOne();
    }

    @Test
    void PRE_PD149_a_signal_that_comes_first_is_held_and_the_message_starts_the_instance() {
        // Given the KYC signal arrives first
        assertThat(kycCleared("s-2").startedInstances()).isEmpty();
        assertThat(instances()).isZero();

        // When the request message arrives
        MessageDelivery started = request("APP-2026-0043", "m-2");

        // Then it starts the instance
        assertThat(started.outcome()).isEqualTo(MessageDelivery.Outcome.STARTED);
        assertThat(instances()).isOne();
    }
}
