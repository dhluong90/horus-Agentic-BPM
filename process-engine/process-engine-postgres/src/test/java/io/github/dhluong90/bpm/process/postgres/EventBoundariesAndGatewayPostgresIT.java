package io.github.dhluong90.bpm.process.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dhluong90.bpm.process.api.ImportRequest;
import io.github.dhluong90.bpm.process.api.MessageNotCorrelatedException;
import io.github.dhluong90.bpm.process.api.MessageService.MessageDelivery;
import io.github.dhluong90.bpm.process.api.ProcessInstance;
import io.github.dhluong90.bpm.process.api.SourceFormat;
import io.github.dhluong90.bpm.process.api.StartRequest;
import io.github.dhluong90.bpm.process.test.TestClock;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Message boundary events and the event-based gateway on PostgreSQL (#490, #495; PRE-290, PRE-294). A payment review is
 * interrupted by a cancellation message correlated into its boundary; a payment wait is resolved by a message or a
 * two-hour timer, and when two events arrive at the same moment on two replicas exactly one branch runs.
 */
class EventBoundariesAndGatewayPostgresIT {

    private static final String[] PIPELINE = {"processes:deploy", "processes:start", "processes:read"};
    private static final String[] CONSUMER = {"processes:message:correlate", "processes:read"};
    private static final Instant T0 = Instant.parse("2027-03-01T02:00:00Z");

    private static final String PAYMENT_REVIEW = """
            <bpmn:message id="m-cancel" name="payment-cancelled"/>
            <bpmn:process id="payment-review" isExecutable="true">
              <bpmn:startEvent id="start"/>
              <bpmn:userTask id="review" horus:assignee="role:PAYMENT_OFFICER" horus:resultVariable="outcome"/>
              <bpmn:boundaryEvent id="cancelled-by-customer" attachedToRef="review" horus:correlationKey="paymentId">
                <bpmn:messageEventDefinition messageRef="m-cancel"/>
              </bpmn:boundaryEvent>
              <bpmn:endEvent id="end"/>
              <bpmn:endEvent id="withdrawn"/>
              <bpmn:sequenceFlow id="a" sourceRef="start" targetRef="review"/>
              <bpmn:sequenceFlow id="b" sourceRef="review" targetRef="end"/>
              <bpmn:sequenceFlow id="c" sourceRef="cancelled-by-customer" targetRef="withdrawn"/>
            </bpmn:process>
            """;
    /** The order waits for the payment message or the reversal message, or two hours. */
    private static final String PAYMENT_WAIT = """
            <bpmn:message id="m-paid" name="payment-received"/>
            <bpmn:message id="m-rev" name="payment-reversed"/>
            <bpmn:process id="payment-wait" isExecutable="true">
              <bpmn:startEvent id="start"/>
              <bpmn:eventBasedGateway id="gw"/>
              <bpmn:intermediateCatchEvent id="paid" horus:correlationKey="orderId">
                <bpmn:messageEventDefinition messageRef="m-paid"/>
              </bpmn:intermediateCatchEvent>
              <bpmn:intermediateCatchEvent id="reversed" horus:correlationKey="orderId">
                <bpmn:messageEventDefinition messageRef="m-rev"/>
              </bpmn:intermediateCatchEvent>
              <bpmn:intermediateCatchEvent id="two-hours">
                <bpmn:timerEventDefinition><bpmn:timeDuration>PT2H</bpmn:timeDuration></bpmn:timerEventDefinition>
              </bpmn:intermediateCatchEvent>
              <bpmn:endEvent id="settled"/>
              <bpmn:endEvent id="refunded"/>
              <bpmn:endEvent id="expired"/>
              <bpmn:sequenceFlow id="a" sourceRef="start" targetRef="gw"/>
              <bpmn:sequenceFlow id="b" sourceRef="gw" targetRef="paid"/>
              <bpmn:sequenceFlow id="c" sourceRef="gw" targetRef="reversed"/>
              <bpmn:sequenceFlow id="d" sourceRef="gw" targetRef="two-hours"/>
              <bpmn:sequenceFlow id="e" sourceRef="paid" targetRef="settled"/>
              <bpmn:sequenceFlow id="f" sourceRef="reversed" targetRef="refunded"/>
              <bpmn:sequenceFlow id="g" sourceRef="two-hours" targetRef="expired"/>
            </bpmn:process>
            """;

    private final TestClock clock = TestClock.at(T0);
    private TestEngine pod1;
    private TestEngine pod2;
    private JdbcClient jdbc;
    private ExecutorService pods;

    @BeforeEach
    void setUp() {
        TestEngine.reset();
        pod1 = TestEngine.replica("pod-1", clock).build();
        pod2 = TestEngine.replica("pod-2", clock).build();
        jdbc = TestEngine.jdbc();
        pods = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void stop() {
        pods.shutdownNow();
        pod1.close();
        pod2.close();
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

    private void deploy(String key, String body) {
        pod1.identity.as("bank-a", "pipeline", PIPELINE);
        pod1.services.importDefinition(new ImportRequest(SourceFormat.BPMN_XML, bpmn(key, body), null));
    }

    private ProcessInstance start(String key, String businessKey, Map<String, Object> variables) {
        pod1.identity.as("bank-a", "officer-lan", PIPELINE);
        return pod1.services.start(new StartRequest(key, businessKey, variables, null));
    }

    private MessageDelivery correlate(TestEngine pod, String name, String key, String idem) {
        pod.identity.as("bank-a", "payments-consumer", CONSUMER);
        return pod.services.correlate(name, key, Map.of(), idem);
    }

    private long count(String sql, Object... params) {
        return jdbc.sql(sql).params(params).query(Long.class).single();
    }

    private String text(String sql, Object... params) {
        return jdbc.sql(sql).params(params).query(String.class).single();
    }

    @Test
    void PRE_290_a_message_correlated_into_a_boundary_cancels_the_task_and_removes_the_subscription() {
        // Given two payments under review, each with its own cancellation subscription
        deploy("payment-review", PAYMENT_REVIEW);
        ProcessInstance p1 = start("payment-review", "PAY-1", Map.of("paymentId", "PAY-9001"));
        ProcessInstance p2 = start("payment-review", "PAY-2", Map.of("paymentId", "PAY-9002"));
        assertThat(count("SELECT count(*) FROM pre_message_subscription")).isEqualTo(2);

        // When the customer's cancellation for PAY-9001 arrives
        MessageDelivery delivery = correlate(pod2, "payment-cancelled", "PAY-9001", "cancel-1");

        // Then only PAY-9001 ends, by the boundary's flow, with its task cancelled and its subscription gone
        assertThat(delivery.instanceId()).isEqualTo(p1.id());
        assertThat(delivery.nodeId()).isEqualTo("cancelled-by-customer");
        assertThat(text("SELECT status FROM pre_instance WHERE id = ?", p1.id())).isEqualTo("COMPLETED");
        assertThat(text("SELECT status FROM pre_task WHERE instance_id = ?", p1.id())).isEqualTo("CANCELLED");
        assertThat(count("SELECT count(*) FROM pre_token WHERE instance_id = ? AND current_node_id = 'withdrawn'"
                + " AND status = 'COMPLETED'", p1.id())).isOne();
        assertThat(count("SELECT count(*) FROM pre_history WHERE instance_id = ? AND event_type = 'BOUNDARY_FIRED'",
                p1.id())).isOne();
        assertThat(count("SELECT count(*) FROM pre_message_subscription WHERE instance_id = ?", p1.id())).isZero();
        assertThat(text("SELECT status FROM pre_instance WHERE id = ?", p2.id())).isEqualTo("RUNNING");
        assertThat(count("SELECT count(*) FROM pre_message_subscription WHERE instance_id = ?", p2.id())).isOne();
    }

    @Test
    void PRE_294_two_messages_that_arrive_together_on_two_replicas_run_exactly_one_branch() throws Exception {
        deploy("payment-wait", PAYMENT_WAIT);
        for (int round = 0; round < 6; round++) {
            String order = "ORD-7" + round;
            ProcessInstance instance = start("payment-wait", "BK-" + order, Map.of("orderId", order));
            assertThat(count("SELECT count(*) FROM pre_message_subscription WHERE instance_id = ?", instance.id()))
                    .isEqualTo(2);
            CyclicBarrier together = new CyclicBarrier(2);
            Future<String> paid = pods.submit(() -> deliver(together, pod1, "payment-received", order, "p-" + order));
            Future<String> reversed = pods.submit(() -> deliver(together, pod2, "payment-reversed", order, "r-" + order));

            String first = paid.get(30, TimeUnit.SECONDS);
            String second = reversed.get(30, TimeUnit.SECONDS);

            // Exactly one delivery succeeded; the other found its subscription gone
            assertThat(java.util.List.of(first, second)).containsExactlyInAnyOrder("DELIVERED", "NOT_CORRELATED");
            String winner = "DELIVERED".equals(first) ? "settled" : "refunded";
            assertThat(text("SELECT status FROM pre_instance WHERE id = ?", instance.id())).isEqualTo("COMPLETED");
            assertThat(count("SELECT count(*) FROM pre_token WHERE instance_id = ? AND current_node_id IN"
                    + " ('settled','refunded','expired') AND status = 'COMPLETED'", instance.id())).isOne();
            assertThat(count("SELECT count(*) FROM pre_token WHERE instance_id = ? AND current_node_id = ?"
                    + " AND status = 'COMPLETED'", instance.id(), winner)).isOne();
            assertThat(count("SELECT count(*) FROM pre_history WHERE instance_id = ? AND event_type ="
                    + " 'EVENT_GATEWAY_RESOLVED'", instance.id())).isOne();
            assertThat(count("SELECT count(*) FROM pre_message_subscription WHERE instance_id = ?", instance.id()))
                    .isZero();
            assertThat(count("SELECT count(*) FROM pre_timer WHERE instance_id = ? AND status = 'DUE'", instance.id()))
                    .isZero();
        }
    }

    @Test
    void PRE_294_a_message_and_the_timer_that_fall_due_together_run_exactly_one_branch() throws Exception {
        deploy("payment-wait", PAYMENT_WAIT);
        for (int round = 0; round < 6; round++) {
            String order = "ORD-8" + round;
            ProcessInstance instance = start("payment-wait", "BK-" + order, Map.of("orderId", order));
            clock.advance(Duration.ofHours(2));
            CyclicBarrier together = new CyclicBarrier(2);
            Future<String> message = pods.submit(() -> deliver(together, pod1, "payment-received", order, "m-" + order));
            Future<Integer> timer = pods.submit(() -> {
                together.await(30, TimeUnit.SECONDS);
                return pod2.jobs.pollOnce();
            });

            String delivered = message.get(30, TimeUnit.SECONDS);
            timer.get(30, TimeUnit.SECONDS);

            String winner = "DELIVERED".equals(delivered) ? "settled" : "expired";
            assertThat(text("SELECT status FROM pre_instance WHERE id = ?", instance.id())).isEqualTo("COMPLETED");
            assertThat(count("SELECT count(*) FROM pre_token WHERE instance_id = ? AND current_node_id IN"
                    + " ('settled','refunded','expired') AND status = 'COMPLETED'", instance.id())).isOne();
            assertThat(count("SELECT count(*) FROM pre_token WHERE instance_id = ? AND current_node_id = ?"
                    + " AND status = 'COMPLETED'", instance.id(), winner)).isOne();
            assertThat(count("SELECT count(*) FROM pre_message_subscription WHERE instance_id = ?", instance.id()))
                    .isZero();
            assertThat(count("SELECT count(*) FROM pre_timer WHERE instance_id = ? AND status = 'DUE'", instance.id()))
                    .isZero();
        }
    }

    @Test
    void PRE_293_cancelling_an_instance_that_waits_at_the_gateway_removes_every_subscription_and_timer() {
        deploy("payment-wait", PAYMENT_WAIT);
        ProcessInstance instance = start("payment-wait", "BK-ORD-6", Map.of("orderId", "ORD-6"));
        assertThat(count("SELECT count(*) FROM pre_message_subscription WHERE instance_id = ?", instance.id()))
                .isEqualTo(2);

        pod1.identity.as("bank-a", "officer-hoa", "processes:cancel", "processes:read");
        pod1.services.cancel(instance.id(), pod1.services.instance(instance.id()).lockVersion(), "customer withdrew the order",
                "cancel-order-6");

        assertThat(count("SELECT count(*) FROM pre_message_subscription WHERE instance_id = ?", instance.id())).isZero();
        assertThat(count("SELECT count(*) FROM pre_timer WHERE instance_id = ? AND status = 'DUE'", instance.id())).isZero();
    }

    private String deliver(CyclicBarrier together, TestEngine pod, String name, String key, String idem) throws Exception {
        together.await(30, TimeUnit.SECONDS);
        try {
            return correlate(pod, name, key, idem).outcome().name();
        } catch (MessageNotCorrelatedException e) {
            return "NOT_CORRELATED";
        }
    }
}
