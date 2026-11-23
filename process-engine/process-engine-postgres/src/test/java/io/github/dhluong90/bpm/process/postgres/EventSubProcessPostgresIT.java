package io.github.dhluong90.bpm.process.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dhluong90.bpm.process.api.ImportRequest;
import io.github.dhluong90.bpm.process.api.ProcessInstance;
import io.github.dhluong90.bpm.process.api.SignalRequest;
import io.github.dhluong90.bpm.process.api.SourceFormat;
import io.github.dhluong90.bpm.process.api.StartRequest;
import io.github.dhluong90.bpm.process.spi.HandlerResult;
import io.github.dhluong90.bpm.process.spi.ServiceCall;
import io.github.dhluong90.bpm.process.spi.ServiceTaskHandler;
import io.github.dhluong90.bpm.process.test.TestClock;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Event sub-processes (#487, PRE-320 to PRE-324) on PostgreSQL. A loan application waits two days for documents; its
 * event sub-processes remind the officer after one day (timer, non-interrupting), close the file when the applicant
 * withdraws (message, interrupting), reprice on a rate-card change (signal, non-interrupting) and take a refused
 * credit check (error). The rows that matter are the timer jobs, the subscriptions and the history.
 */
class EventSubProcessPostgresIT {

    private static final String[] PIPELINE = {"processes:deploy", "processes:start", "processes:read"};
    private static final Instant T0 = Instant.parse("2027-03-01T02:00:00Z");

    private static final String WAIT_FOR_DOCUMENTS = """
            <bpmn:startEvent id="start"/>
            <bpmn:intermediateCatchEvent id="wait-for-documents">
              <bpmn:timerEventDefinition><bpmn:timeDuration>P2D</bpmn:timeDuration></bpmn:timerEventDefinition>
            </bpmn:intermediateCatchEvent>
            <bpmn:endEvent id="end"/>
            <bpmn:sequenceFlow id="a" sourceRef="start" targetRef="wait-for-documents"/>
            <bpmn:sequenceFlow id="b" sourceRef="wait-for-documents" targetRef="end"/>
            """;
    private static final String REMINDER = """
            <bpmn:subProcess id="reminder" triggeredByEvent="true">
              <bpmn:startEvent id="after-a-day" isInterrupting="false">
                <bpmn:timerEventDefinition><bpmn:timeDuration>P1D</bpmn:timeDuration></bpmn:timerEventDefinition>
              </bpmn:startEvent>
              <bpmn:serviceTask id="remind" horus:handler="send-reminder"/>
              <bpmn:endEvent id="reminded"/>
              <bpmn:sequenceFlow id="r1" sourceRef="after-a-day" targetRef="remind"/>
              <bpmn:sequenceFlow id="r2" sourceRef="remind" targetRef="reminded"/>
            </bpmn:subProcess>
            """;
    private static final String WITHDRAWAL = """
            <bpmn:subProcess id="withdrawal" triggeredByEvent="true">
              <bpmn:startEvent id="withdrawn" horus:correlationKey="applicationId">
                <bpmn:messageEventDefinition messageRef="Msg_withdrawn"/>
              </bpmn:startEvent>
              <bpmn:serviceTask id="close-file" horus:handler="close-file"/>
              <bpmn:endEvent id="closed"/>
              <bpmn:sequenceFlow id="w1" sourceRef="withdrawn" targetRef="close-file"/>
              <bpmn:sequenceFlow id="w2" sourceRef="close-file" targetRef="closed"/>
            </bpmn:subProcess>
            """;
    private static final String REPRICE = """
            <bpmn:subProcess id="reprice" triggeredByEvent="true">
              <bpmn:startEvent id="rate-changed" isInterrupting="false">
                <bpmn:signalEventDefinition signalRef="Sig_rate"/>
              </bpmn:startEvent>
              <bpmn:serviceTask id="reprice-offer" horus:handler="reprice-offer"/>
              <bpmn:endEvent id="repriced"/>
              <bpmn:sequenceFlow id="p1" sourceRef="rate-changed" targetRef="reprice-offer"/>
              <bpmn:sequenceFlow id="p2" sourceRef="reprice-offer" targetRef="repriced"/>
            </bpmn:subProcess>
            """;
    private static final String CREDIT_CHECK = """
            <bpmn:startEvent id="start"/>
            <bpmn:serviceTask id="check-credit" horus:handler="check-credit"/>
            <bpmn:endEvent id="end"/>
            <bpmn:sequenceFlow id="a" sourceRef="start" targetRef="check-credit"/>
            <bpmn:sequenceFlow id="b" sourceRef="check-credit" targetRef="end"/>
            <bpmn:subProcess id="refused" triggeredByEvent="true">
              <bpmn:startEvent id="credit-refused"><bpmn:errorEventDefinition errorRef="Err_refused"/></bpmn:startEvent>
              <bpmn:serviceTask id="notify-applicant" horus:handler="notify-applicant"/>
              <bpmn:endEvent id="notified"/>
              <bpmn:sequenceFlow id="e1" sourceRef="credit-refused" targetRef="notify-applicant"/>
              <bpmn:sequenceFlow id="e2" sourceRef="notify-applicant" targetRef="notified"/>
            </bpmn:subProcess>
            """;

    private final TestClock clock = TestClock.at(T0);
    private final List<String> ran = new CopyOnWriteArrayList<>();
    private TestEngine engine;
    private JdbcClient jdbc;

    @BeforeEach
    void setUp() {
        TestEngine.reset();
        engine = TestEngine.replica("pod-1", clock).handlers(handler("send-reminder"), handler("close-file"),
                handler("reprice-offer"), handler("notify-applicant"),
                handler("check-credit", call -> new HandlerResult.BusinessError("CREDIT_REFUSED", "score 410"))).build();
        jdbc = TestEngine.jdbc();
    }

    @AfterEach
    void stop() {
        engine.close();
    }

    private ServiceTaskHandler handler(String name) {
        return handler(name, call -> {
            ran.add(name);
            return new HandlerResult.Completed(Map.of());
        });
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
                                  xmlns:horus="urn:horus-bpm:bpmn:extensions:v1"
                                  id="Definitions_%s" targetNamespace="urn:horus-bpm:examples"
                                  expressionLanguage="urn:horus-bpm:expr:v1">
                  <bpmn:error id="Err_refused" errorCode="CREDIT_REFUSED"/>
                  <bpmn:message id="Msg_withdrawn" name="applicant-withdrew"/>
                  <bpmn:signal id="Sig_rate" name="rate-card-updated"/>
                  <bpmn:process id="%s" isExecutable="true">
                %s
                  </bpmn:process>
                </bpmn:definitions>
                """.formatted(key, key, body).getBytes(StandardCharsets.UTF_8);
    }

    private ProcessInstance deployAndStart(String key, String body) {
        engine.identity.as("bank-a", "pipeline", PIPELINE);
        engine.services.importDefinition(new ImportRequest(SourceFormat.BPMN_XML, bpmn(key, body), null));
        return engine.services.start(new StartRequest(key, "LA-2027-0301", Map.of("applicationId", "LA-2027-0301"),
                null));
    }

    private long count(String sql, Object... params) {
        return jdbc.sql(sql).params(params).query(Long.class).single();
    }

    private String text(String sql, Object... params) {
        return jdbc.sql(sql).params(params).query(String.class).single();
    }

    @Test
    void PRE_321_a_non_interrupting_timer_event_sub_process_runs_beside_the_wait_and_the_scope_ends_over_nothing() {
        // Given a loan application waiting two days for documents, with a reminder after one day
        ProcessInstance loan = deployAndStart("loan-application", WAIT_FOR_DOCUMENTS + REMINDER);
        assertThat(count("SELECT count(*) FROM pre_timer WHERE instance_id = ? AND status = 'DUE'", loan.id()))
                .isEqualTo(2);

        // When one day passes and the due timer is fired
        clock.set(T0.plus(Duration.ofDays(1)));
        assertThat(engine.jobs.pollOnce()).isEqualTo(1);

        // Then the reminder ran once, the wait for documents is untouched and the instance is still running
        assertThat(ran).containsExactly("send-reminder");
        assertThat(text("SELECT status FROM pre_instance WHERE id = ?", loan.id())).isEqualTo("RUNNING");
        assertThat(text("SELECT status FROM pre_token WHERE instance_id = ? AND current_node_id = 'wait-for-documents'",
                loan.id())).isEqualTo("WAITING_TIMER");
        assertThat(count("SELECT count(*) FROM pre_history WHERE instance_id = ? AND event_type ="
                + " 'EVENT_SUBPROCESS_STARTED' AND details->>'trigger' = 'TIMER' AND details->>'interrupting' ="
                + " 'false'", loan.id())).isOne();

        // And when the second day passes, the instance completes with no timer left waiting
        clock.set(T0.plus(Duration.ofDays(2)));
        assertThat(engine.jobs.pollOnce()).isEqualTo(1);
        assertThat(text("SELECT status FROM pre_instance WHERE id = ?", loan.id())).isEqualTo("COMPLETED");
        assertThat(count("SELECT count(*) FROM pre_timer WHERE instance_id = ? AND status = 'DUE'", loan.id())).isZero();
    }

    @Test
    void PRE_320_an_interrupting_message_event_sub_process_cancels_the_wait_and_leaves_no_subscription() {
        // Given a loan application waiting for documents, with a withdrawal keyed by the application number
        ProcessInstance loan = deployAndStart("loan-application", WAIT_FOR_DOCUMENTS + WITHDRAWAL);
        assertThat(count("SELECT count(*) FROM pre_message_subscription WHERE instance_id = ?", loan.id())).isOne();

        // When the applicant withdraws
        engine.identity.as("bank-a", "payments-consumer", new String[] {"processes:message:correlate"});
        engine.services.correlate("applicant-withdrew", "LA-2027-0301", Map.of(), "withdraw-1");

        // Then the wait for documents is cancelled with its timer, the file is closed and the instance has ended
        assertThat(ran).containsExactly("close-file");
        assertThat(text("SELECT status FROM pre_token WHERE instance_id = ? AND current_node_id = 'wait-for-documents'",
                loan.id())).isEqualTo("CANCELLED");
        assertThat(count("SELECT count(*) FROM pre_timer WHERE instance_id = ? AND node_id = 'wait-for-documents'"
                + " AND status = 'DUE'", loan.id())).isZero();
        assertThat(text("SELECT status FROM pre_instance WHERE id = ?", loan.id())).isEqualTo("COMPLETED");
        assertThat(count("SELECT count(*) FROM pre_message_subscription WHERE instance_id = ?", loan.id())).isZero();
    }

    @Test
    void PRE_324_a_non_interrupting_signal_event_sub_process_starts_for_each_broadcast_and_its_subscription_goes_with_the_scope() {
        // Given a loan application waiting for documents, with a repricing that listens for rate-card-updated
        ProcessInstance loan = deployAndStart("loan-application", WAIT_FOR_DOCUMENTS + REPRICE);
        assertThat(count("SELECT count(*) FROM pre_event_subscription WHERE instance_id = ? AND kind = 'SIGNAL'",
                loan.id())).isOne();

        // When the rate card changes and the wake-up job is fired
        engine.identity.as("bank-a", "pricing-admin", new String[] {"processes:signal:broadcast"});
        engine.services.broadcast(new SignalRequest("rate-card-updated", "K1"));
        assertThat(engine.jobs.pollOnce()).isGreaterThanOrEqualTo(1);

        // Then the repricing ran once, a new subscription waits, and the instance still waits for documents
        assertThat(ran).containsExactly("reprice-offer");
        assertThat(count("SELECT count(*) FROM pre_event_subscription WHERE instance_id = ? AND kind = 'SIGNAL'",
                loan.id())).isOne();
        assertThat(text("SELECT status FROM pre_instance WHERE id = ?", loan.id())).isEqualTo("RUNNING");

        // And when the documents arrive and the scope ends, the subscription is removed
        clock.set(T0.plus(Duration.ofDays(2)));
        engine.jobs.pollOnce();
        assertThat(text("SELECT status FROM pre_instance WHERE id = ?", loan.id())).isEqualTo("COMPLETED");
        assertThat(count("SELECT count(*) FROM pre_event_subscription WHERE instance_id = ?", loan.id())).isZero();
    }

    @Test
    void PRE_323_a_business_error_of_a_step_is_taken_by_the_error_event_sub_process_and_opens_no_incident() {
        // When a credit check answers CREDIT_REFUSED and the process has an error event sub-process for it
        ProcessInstance loan = deployAndStart("credit-check", CREDIT_CHECK);

        // Then the applicant is notified, the instance has ended and no incident is open
        assertThat(ran).containsExactly("notify-applicant");
        assertThat(text("SELECT status FROM pre_instance WHERE id = ?", loan.id())).isEqualTo("COMPLETED");
        assertThat(count("SELECT count(*) FROM pre_incident WHERE instance_id = ?", loan.id())).isZero();
        assertThat(count("SELECT count(*) FROM pre_history WHERE instance_id = ? AND event_type ="
                + " 'ERROR_THROWN' AND details->>'caughtBy' = 'refused'", loan.id())).isOne();
    }
}
