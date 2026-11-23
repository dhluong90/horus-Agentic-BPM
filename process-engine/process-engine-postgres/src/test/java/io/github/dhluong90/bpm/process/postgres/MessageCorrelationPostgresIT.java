package io.github.dhluong90.bpm.process.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.process.api.IdempotencyKeyReuseException;
import io.github.dhluong90.bpm.process.api.ImportRequest;
import io.github.dhluong90.bpm.process.api.MessageAmbiguousException;
import io.github.dhluong90.bpm.process.api.MessageNotCorrelatedException;
import io.github.dhluong90.bpm.process.api.MessageService.MessageDelivery;
import io.github.dhluong90.bpm.process.api.ProcessInstance;
import io.github.dhluong90.bpm.process.api.SourceFormat;
import io.github.dhluong90.bpm.process.api.StartRequest;
import io.github.dhluong90.bpm.process.spi.MessageAdapter.OutgoingMessage;
import io.github.dhluong90.bpm.process.test.TestClock;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Messages and correlation (#491, PRE-230 to PRE-235) on PostgreSQL. The order {@code order-payment} sends
 * {@code payment-requested} and waits at {@code wait-for-payment} for {@code payment-received} carrying its order
 * number; {@code order-intake} starts on {@code order-submitted}. The host's consumer calls the correlate API.
 */
class MessageCorrelationPostgresIT {

    private static final String[] PIPELINE = {"processes:deploy", "processes:start", "processes:read"};
    private static final String[] CONSUMER = {"processes:message:correlate", "processes:read"};

    private static final String ORDER_PAYMENT = """
            <bpmn:message id="m-req" name="payment-requested"/>
            <bpmn:message id="m-paid" name="payment-received"/>
            <bpmn:process id="order-payment" isExecutable="true">
              <bpmn:startEvent id="start"/>
              <bpmn:sendTask id="request-payment" messageRef="m-req" horus:correlationKey="orderId">
                <bpmn:extensionElements><horus:input name="amount" value="${instance.amount}"/></bpmn:extensionElements>
              </bpmn:sendTask>
              <bpmn:receiveTask id="wait-for-payment" messageRef="m-paid" horus:correlationKey="orderId">
                <bpmn:extensionElements><horus:output name="paymentRef" from="reference"/></bpmn:extensionElements>
              </bpmn:receiveTask>
              <bpmn:endEvent id="end"/>
              <bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="request-payment"/>
              <bpmn:sequenceFlow id="f2" sourceRef="request-payment" targetRef="wait-for-payment"/>
              <bpmn:sequenceFlow id="f3" sourceRef="wait-for-payment" targetRef="end"/>
            </bpmn:process>
            """;
    private static final String ORDER_INTAKE = """
            <bpmn:message id="m-sub" name="order-submitted"/>
            <bpmn:process id="order-intake" isExecutable="true">
              <bpmn:startEvent id="start"><bpmn:messageEventDefinition messageRef="m-sub"/></bpmn:startEvent>
              <bpmn:userTask id="review" horus:assignee="role:OPS" horus:resultVariable="review"/>
              <bpmn:endEvent id="end"/>
              <bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="review"/>
              <bpmn:sequenceFlow id="f2" sourceRef="review" targetRef="end"/>
            </bpmn:process>
            """;

    private final TestClock clock = TestClock.at(Instant.parse("2027-03-01T02:00:00Z"));
    private final List<OutgoingMessage> outbox = new CopyOnWriteArrayList<>();
    private TestEngine engine;
    private JdbcClient jdbc;

    @BeforeEach
    void setUp() {
        TestEngine.reset();
        engine = TestEngine.replica("pod-1", clock).messageAdapter(outbox::add).build();
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
                                  xmlns:horus="urn:horus-bpm:bpmn:extensions:v1"
                                  id="Definitions_%s" targetNamespace="urn:horus-bpm:examples"
                                  expressionLanguage="urn:horus-bpm:expr:v1">
                %s
                </bpmn:definitions>
                """.formatted(key, body).getBytes(StandardCharsets.UTF_8);
    }

    private void deploy(String tenant, String key, String body) {
        engine.identity.as(tenant, "pipeline", PIPELINE);
        engine.services.importDefinition(new ImportRequest(SourceFormat.BPMN_XML, bpmn(key, body), null));
    }

    private ProcessInstance order(String tenant, String orderId) {
        engine.identity.as(tenant, "officer-lan", PIPELINE);
        return engine.services.start(new StartRequest("order-payment", "BK-" + orderId + "-" + tenant,
                Map.of("orderId", orderId, "amount", "1250000"), null));
    }

    private MessageDelivery correlate(String tenant, String name, String key, Map<String, Object> payload, String idem) {
        engine.identity.as(tenant, "payments-consumer", CONSUMER);
        return engine.services.correlate(name, key, payload, idem);
    }

    private String text(String sql, Object... params) {
        return jdbc.sql(sql).params(params).query(String.class).single();
    }

    private long count(String sql, Object... params) {
        return jdbc.sql(sql).params(params).query(Long.class).single();
    }

    @Test
    void PRE_230_a_message_continues_exactly_the_instance_with_its_key() {
        // Given two orders waiting at wait-for-payment, ORD-20481 and ORD-20482
        deploy("bank-a", "order-payment", ORDER_PAYMENT);
        ProcessInstance o1 = order("bank-a", "ORD-20481");
        ProcessInstance o2 = order("bank-a", "ORD-20482");

        // When payment-received for ORD-20481 arrives
        MessageDelivery d = correlate("bank-a", "payment-received", "ORD-20481", Map.of("reference", "TT-77120",
                "ignored", "x"), "pay-1");

        // Then O1 continues with only the declared field, and O2 still waits
        assertThat(d.outcome()).isEqualTo(MessageDelivery.Outcome.DELIVERED);
        assertThat(d.instanceId()).isEqualTo(o1.id());
        assertThat(d.nodeId()).isEqualTo("wait-for-payment");
        assertThat(text("SELECT status FROM pre_instance WHERE id = ?", o1.id())).isEqualTo("COMPLETED");
        assertThat(text("SELECT variables::text FROM pre_instance WHERE id = ?", o1.id())).contains("TT-77120")
                .doesNotContain("ignored");
        assertThat(text("SELECT status FROM pre_token WHERE instance_id = ? AND current_node_id = 'wait-for-payment'",
                o2.id())).isEqualTo("WAITING_EVENT");
        assertThat(count("SELECT count(*) FROM pre_message_subscription WHERE instance_id = ?", o1.id())).isZero();
        assertThat(count("SELECT count(*) FROM pre_message_subscription WHERE instance_id = ?", o2.id())).isOne();
    }

    @Test
    void PRE_231_two_waiting_instances_with_the_same_key_are_a_conflict_and_nothing_runs() {
        deploy("bank-a", "order-payment", ORDER_PAYMENT);
        ProcessInstance first = order("bank-a", "ORD-30001");
        engine.identity.as("bank-a", "officer-lan", PIPELINE);
        ProcessInstance second = engine.services.start(new StartRequest("order-payment", "BK-second",
                Map.of("orderId", "ORD-30001", "amount", "10"), null));

        assertThatThrownBy(() -> correlate("bank-a", "payment-received", "ORD-30001", Map.of("reference", "R"), "pay-2"))
                .isInstanceOf(MessageAmbiguousException.class).hasMessageNotContaining("ORD-30001");

        assertThat(text("SELECT status FROM pre_instance WHERE id = ?", first.id())).isEqualTo("RUNNING");
        assertThat(text("SELECT status FROM pre_instance WHERE id = ?", second.id())).isEqualTo("RUNNING");
        assertThat(count("SELECT count(*) FROM pre_history WHERE event_type = 'MESSAGE_RECEIVED'")).isZero();
        assertThat(count("SELECT count(*) FROM pre_idempotency WHERE operation = 'message-correlate'")).isZero();
    }

    @Test
    void PRE_232_a_message_with_no_waiting_instance_is_refused_and_nothing_is_stored() {
        deploy("bank-a", "order-payment", ORDER_PAYMENT);
        order("bank-a", "ORD-40001");
        long history = count("SELECT count(*) FROM pre_history");

        assertThatThrownBy(() -> correlate("bank-a", "payment-received", "ORD-99999", Map.of("reference", "R"), "pay-3"))
                .isInstanceOf(MessageNotCorrelatedException.class).extracting("code")
                .isEqualTo("MESSAGE_NOT_CORRELATED");

        assertThat(count("SELECT count(*) FROM pre_history")).isEqualTo(history);
        assertThat(count("SELECT count(*) FROM pre_idempotency WHERE operation = 'message-correlate'")).isZero();
        assertThat(count("SELECT count(*) FROM pre_instance")).isOne();
    }

    @Test
    void PRE_233_a_message_never_reaches_an_instance_of_another_tenant() {
        deploy("bank-a", "order-payment", ORDER_PAYMENT);
        ProcessInstance a = order("bank-a", "ORD-50001");

        assertThatThrownBy(() -> correlate("bank-b", "payment-received", "ORD-50001", Map.of("reference", "R"), "pay-4"))
                .isInstanceOf(MessageNotCorrelatedException.class);

        assertThat(text("SELECT status FROM pre_instance WHERE id = ?", a.id())).isEqualTo("RUNNING");
        assertThat(count("SELECT count(*) FROM pre_history WHERE event_type = 'MESSAGE_RECEIVED'")).isZero();
    }

    @Test
    void PRE_234_delivering_the_same_message_twice_with_one_key_runs_it_once() {
        deploy("bank-a", "order-payment", ORDER_PAYMENT);
        ProcessInstance o = order("bank-a", "ORD-60001");
        MessageDelivery first = correlate("bank-a", "payment-received", "ORD-60001", Map.of("reference", "TT-1"), "pay-5");

        // A replay with the same Idempotency-Key answers the first result, although nothing waits any more
        MessageDelivery replay = correlate("bank-a", "payment-received", "ORD-60001", Map.of("reference", "TT-1"), "pay-5");

        assertThat(replay).isEqualTo(first);
        assertThat(count("SELECT count(*) FROM pre_history WHERE instance_id = ? AND event_type = 'MESSAGE_RECEIVED'",
                o.id())).isOne();
        // The same key with another message is a reuse error; a new key for the consumed message is refused
        assertThatThrownBy(() -> correlate("bank-a", "payment-received", "ORD-60001", Map.of("reference", "TT-2"), "pay-5"))
                .isInstanceOf(IdempotencyKeyReuseException.class);
        assertThatThrownBy(() -> correlate("bank-a", "payment-received", "ORD-60001", Map.of("reference", "TT-1"), "pay-6"))
                .isInstanceOf(MessageNotCorrelatedException.class);
    }

    @Test
    void PRE_235_a_send_step_hands_its_message_to_the_host_once_with_only_the_named_values_and_a_masked_key() {
        deploy("bank-a", "order-payment", ORDER_PAYMENT);

        ProcessInstance o = order("bank-a", "ORD-70001");

        assertThat(outbox).singleElement().satisfies(m -> {
            assertThat(m.tenantId()).isEqualTo("bank-a");
            assertThat(m.name()).isEqualTo("payment-requested");
            assertThat(m.correlationKey()).isEqualTo("ORD-70001");
            assertThat(m.payload()).containsExactly(Map.entry("amount", "1250000"));
            assertThat(m.instanceId()).isEqualTo(o.id());
            assertThat(m.messageId()).hasSize(26);
        });
        // History keeps the key only as the masker makes it
        assertThat(text("SELECT details::text FROM pre_history WHERE instance_id = ? AND event_type = 'MESSAGE_SENT'",
                o.id())).doesNotContain("ORD-70001").contains("payment-requested");
        assertThat(text("SELECT details::text FROM pre_history WHERE instance_id = ? AND event_type = 'MESSAGE_WAITING'",
                o.id())).doesNotContain("ORD-70001");
    }

    @Test
    void PRE_236_a_message_starts_the_process_with_a_message_start_event_and_the_same_key_finds_the_same_instance() {
        deploy("bank-a", "order-intake", ORDER_INTAKE);

        MessageDelivery first = correlate("bank-a", "order-submitted", "ORD-80001", Map.of("customer", "C-778"), "sub-1");
        MessageDelivery again = correlate("bank-a", "order-submitted", "ORD-80001", Map.of("customer", "C-778"), "sub-2");

        assertThat(first.outcome()).isEqualTo(MessageDelivery.Outcome.STARTED);
        assertThat(again.instanceId()).isEqualTo(first.instanceId());
        assertThat(text("SELECT business_key || '/' || variables::text FROM pre_instance WHERE id = ?", first.instanceId()))
                .startsWith("ORD-80001/").contains("C-778");
        assertThat(count("SELECT count(*) FROM pre_instance")).isOne();
        // Another tenant has no such process
        assertThatThrownBy(() -> correlate("bank-b", "order-submitted", "ORD-80001", Map.of(), "sub-3"))
                .isInstanceOf(MessageNotCorrelatedException.class);
    }
}
