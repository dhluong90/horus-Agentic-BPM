package io.github.dhluong90.bpm.process.postgres;

import static org.assertj.core.api.Assertions.assertThat;

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
import java.time.Instant;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Boundary events on PostgreSQL (#489, PRE-280, PRE-282). A timer boundary on a call activity fires through the same
 * timer jobs as every timer and cancels the called child; an error boundary takes a handler's business error in the
 * transaction of the step, so no incident row is written. The clock is a {@link TestClock}; jobs are fired by hand.
 */
class BoundaryEventsPostgresIT {

    private static final String[] PIPELINE = {"processes:deploy", "processes:start", "processes:read"};
    private static final String[] OPERATOR = {"processes:incident:manage", "processes:read"};
    private static final Instant T0 = Instant.parse("2027-03-01T02:00:00Z");

    private static final String KYC_CHECK = """
            <bpmn:startEvent id="start"/>
            <bpmn:userTask id="check" horus:assignee="role:KYC_ANALYST" horus:resultVariable="check"/>
            <bpmn:endEvent id="end"/>
            <bpmn:sequenceFlow id="a" sourceRef="start" targetRef="check"/>
            <bpmn:sequenceFlow id="b" sourceRef="check" targetRef="end"/>
            """;
    /** The KYC call has two days; after that the child is cancelled and an officer decides by hand. */
    private static final String ACCOUNT_OPENING = """
            <bpmn:startEvent id="start"/>
            <bpmn:callActivity id="kyc" calledElement="kyc-check">
              <bpmn:extensionElements><horus:input name="customerId" value="${instance.customerId}"/></bpmn:extensionElements>
            </bpmn:callActivity>
            <bpmn:boundaryEvent id="kyc-late" attachedToRef="kyc">
              <bpmn:timerEventDefinition><bpmn:timeDuration>P2D</bpmn:timeDuration></bpmn:timerEventDefinition>
            </bpmn:boundaryEvent>
            <bpmn:userTask id="manual-decision" horus:assignee="role:OPS" horus:resultVariable="decision"/>
            <bpmn:endEvent id="end"/>
            <bpmn:endEvent id="decided"/>
            <bpmn:sequenceFlow id="a" sourceRef="start" targetRef="kyc"/>
            <bpmn:sequenceFlow id="b" sourceRef="kyc" targetRef="end"/>
            <bpmn:sequenceFlow id="c" sourceRef="kyc-late" targetRef="manual-decision"/>
            <bpmn:sequenceFlow id="d" sourceRef="manual-decision" targetRef="decided"/>
            """;
    private static final String DEBIT = """
            <bpmn:startEvent id="start"/>
            <bpmn:serviceTask id="debit" horus:handler="post-debit"/>
            <bpmn:boundaryEvent id="no-funds" attachedToRef="debit">
              <bpmn:errorEventDefinition errorRef="Err_funds"/>
            </bpmn:boundaryEvent>
            <bpmn:endEvent id="paid"/>
            <bpmn:endEvent id="declined"/>
            <bpmn:sequenceFlow id="a" sourceRef="start" targetRef="debit"/>
            <bpmn:sequenceFlow id="b" sourceRef="debit" targetRef="paid"/>
            <bpmn:sequenceFlow id="c" sourceRef="no-funds" targetRef="declined"/>
            """;

    /** Two local steps in parallel: the registry flags the collateral, the valuation is a second local step. */
    private static final String PREPARATION = """
            <bpmn:startEvent id="start"/>
            <bpmn:parallelGateway id="fork"/>
            <bpmn:serviceTask id="register-collateral" horus:handler="register-collateral"/>
            <bpmn:boundaryEvent id="collateral-flagged" attachedToRef="register-collateral">
              <bpmn:errorEventDefinition errorRef="Err_funds"/>
            </bpmn:boundaryEvent>
            <bpmn:endEvent id="end-fraud"><bpmn:terminateEventDefinition/></bpmn:endEvent>
            <bpmn:serviceTask id="valuation" horus:handler="valuate"/>
            <bpmn:parallelGateway id="join"/>
            <bpmn:endEvent id="end"/>
            <bpmn:sequenceFlow id="a" sourceRef="start" targetRef="fork"/>
            <bpmn:sequenceFlow id="b" sourceRef="fork" targetRef="register-collateral"/>
            <bpmn:sequenceFlow id="c" sourceRef="fork" targetRef="valuation"/>
            <bpmn:sequenceFlow id="d" sourceRef="register-collateral" targetRef="join"/>
            <bpmn:sequenceFlow id="e" sourceRef="valuation" targetRef="join"/>
            <bpmn:sequenceFlow id="f" sourceRef="collateral-flagged" targetRef="end-fraud"/>
            <bpmn:sequenceFlow id="g" sourceRef="join" targetRef="end"/>
            """;

    private final TestClock clock = TestClock.at(T0);
    private String debitAnswer = "INSUFFICIENT_FUNDS";
    private TestEngine engine;
    private JdbcClient jdbc;

    @BeforeEach
    void setUp() {
        TestEngine.reset();
        engine = TestEngine.replica("pod-1", clock).handlers(handler("post-debit",
                call -> new HandlerResult.BusinessError(debitAnswer, "balance 200,000 VND"))).build();
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
                                  xmlns:horus="urn:horus-bpm:bpmn:extensions:v1"
                                  id="Definitions_%s" targetNamespace="urn:horus-bpm:examples"
                                  expressionLanguage="urn:horus-bpm:expr:v1">
                  <bpmn:error id="Err_funds" errorCode="INSUFFICIENT_FUNDS"/>
                  <bpmn:process id="%s" isExecutable="true">
                %s
                  </bpmn:process>
                </bpmn:definitions>
                """.formatted(key, key, body).getBytes(StandardCharsets.UTF_8);
    }

    private void deploy(String key, String body) {
        engine.identity.as("bank-a", "pipeline", PIPELINE);
        engine.services.importDefinition(new ImportRequest(SourceFormat.BPMN_XML, bpmn(key, body), null));
    }

    private ProcessInstance start(String key, String businessKey) {
        engine.identity.as("bank-a", "officer-lan", PIPELINE);
        return engine.services.start(new StartRequest(key, businessKey, Map.of("customerId", "C-778"), null));
    }

    private long count(String sql, Object... params) {
        return jdbc.sql(sql).params(params).query(Long.class).single();
    }

    private String text(String sql, Object... params) {
        return jdbc.sql(sql).params(params).query(String.class).single();
    }

    @Test
    void PRE_280_a_timer_boundary_on_a_call_activity_cancels_the_child_and_runs_its_flow() {
        // Given account-opening whose KYC call has a two-day timer boundary, and a KYC child that waits for an analyst
        deploy("kyc-check", KYC_CHECK);
        deploy("account-opening", ACCOUNT_OPENING);
        ProcessInstance parent = start("account-opening", "AO-2027-0301");
        String child = text("SELECT id FROM pre_instance WHERE parent_instance_id = ?", parent.id());
        assertThat(text("SELECT status FROM pre_timer WHERE instance_id = ? AND node_id = 'kyc-late'", parent.id()))
                .isEqualTo("DUE");

        // When two days pass and the due timer is fired
        clock.set(T0.plus(Duration.ofDays(2)));
        assertThat(engine.jobs.pollOnce()).isGreaterThanOrEqualTo(1);

        // Then the child is cancelled, the call step is cancelled, and the officer's manual decision is open
        assertThat(text("SELECT status FROM pre_instance WHERE id = ?", child)).isEqualTo("CANCELLED");
        assertThat(text("SELECT status FROM pre_token WHERE instance_id = ? AND current_node_id = 'kyc'", parent.id()))
                .isEqualTo("CANCELLED");
        assertThat(count("SELECT count(*) FROM pre_task WHERE instance_id = ? AND node_id = 'manual-decision' AND status"
                + " = 'OPEN'", parent.id())).isOne();
        assertThat(count("SELECT count(*) FROM pre_history WHERE instance_id = ? AND event_type = 'BOUNDARY_FIRED' AND "
                + "details->>'boundaryId' = 'kyc-late'", parent.id())).isOne();
        assertThat(count("SELECT count(*) FROM pre_incident WHERE instance_id = ?", parent.id())).isZero();

        // And the parent is still running, waiting for the manual decision
        assertThat(text("SELECT status FROM pre_instance WHERE id = ?", parent.id())).isEqualTo("RUNNING");
    }

    @Test
    void PRE_282_a_business_error_with_a_matching_boundary_opens_no_incident() {
        // Given a debit with a boundary for INSUFFICIENT_FUNDS and a handler that answers that code
        deploy("debit-payment", DEBIT);

        // When the payment starts
        ProcessInstance payment = start("debit-payment", "PAY-2027-0042");

        // Then the boundary's flow ends the instance, and no incident row exists
        assertThat(engine.services.instance(payment.id()).status()).isEqualTo("COMPLETED");
        assertThat(count("SELECT count(*) FROM pre_incident WHERE instance_id = ?", payment.id())).isZero();
        assertThat(count("SELECT count(*) FROM pre_history WHERE instance_id = ? AND event_type = 'ERROR_THROWN' AND "
                + "details->>'caughtBy' = 'no-funds'", payment.id())).isOne();
    }

    @Test
    void PRE_282_a_business_error_with_another_code_still_opens_an_incident() {
        // Given the same debit and a handler that now answers ACCOUNT_FROZEN
        debitAnswer = "ACCOUNT_FROZEN";
        deploy("debit-payment", DEBIT);

        // When the payment starts
        ProcessInstance payment = start("debit-payment", "PAY-2027-0043");

        // Then the step stays and an incident names the code; the instance is still running
        assertThat(text("SELECT error_code || ':' || node_id FROM pre_incident WHERE instance_id = ?", payment.id()))
                .isEqualTo("UNHANDLED_BUSINESS_ERROR:debit");
        assertThat(engine.services.instance(payment.id()).status()).isEqualTo("RUNNING");
    }

    @Test
    void PRE_374_a_terminate_reached_from_an_error_boundary_ends_the_instance_and_the_sibling_local_step_never_runs() {
        // Given a loan preparation that forks into a collateral registration and a valuation, both local steps
        java.util.concurrent.atomic.AtomicInteger valuations = new java.util.concurrent.atomic.AtomicInteger();
        engine.close();
        engine = TestEngine.replica("pod-2", clock).handlers(
                handler("register-collateral", call -> new HandlerResult.BusinessError("INSUFFICIENT_FUNDS",
                        "collateral LA-2027-000454 is double pledged")),
                handler("valuate", call -> {
                    valuations.incrementAndGet();
                    return new HandlerResult.Completed(Map.of("value", 1L));
                })).build();
        deploy("loan-preparation", PREPARATION);

        // When the application starts and the registry flags the collateral
        ProcessInstance loan = start("loan-preparation", "LA-2027-000454");

        // Then the terminate end ends the instance in the same call, nothing is left open, and no job failed
        assertThat(engine.services.instance(loan.id()).status()).isEqualTo("COMPLETED");
        assertThat(count("SELECT count(*) FROM pre_token WHERE instance_id = ? AND status NOT IN ('COMPLETED',"
                + " 'CANCELLED', 'JOINED')", loan.id())).isZero();
        assertThat(count("SELECT count(*) FROM pre_history WHERE instance_id = ? AND event_type = 'TERMINATED'",
                loan.id())).isEqualTo(1);
        assertThat(valuations.get()).isZero();
        // And the history says the valuation was skipped because its branch ended, so an operator can tell
        assertThat(text("SELECT node_id || ':' || event_type FROM pre_history WHERE instance_id = ? AND event_type = "
                + "'STEP_SKIPPED'", loan.id())).isEqualTo("valuation:STEP_SKIPPED");
    }

    /** The valuation comes first in the fork, so it runs; the terminate then ends the instance as COMPLETED. */
    private static final String PREPARATION_VALUATION_FIRST = PREPARATION
            .replace("""
                    <bpmn:sequenceFlow id="b" sourceRef="fork" targetRef="register-collateral"/>
                    <bpmn:sequenceFlow id="c" sourceRef="fork" targetRef="valuation"/>
                    """, """
                    <bpmn:sequenceFlow id="b" sourceRef="fork" targetRef="valuation"/>
                    <bpmn:sequenceFlow id="c" sourceRef="fork" targetRef="register-collateral"/>
                    """);

    @Test
    void PRE_374_a_sibling_local_step_queued_before_the_terminate_runs_once_and_the_instance_still_completes() {
        // Given the valuation is queued before the registration that will flag the collateral
        assertThat(PREPARATION_VALUATION_FIRST).isNotEqualTo(PREPARATION);
        java.util.concurrent.atomic.AtomicInteger valuations = new java.util.concurrent.atomic.AtomicInteger();
        engine.close();
        engine = TestEngine.replica("pod-2", clock).handlers(
                handler("register-collateral", call -> new HandlerResult.BusinessError("INSUFFICIENT_FUNDS",
                        "collateral LA-2027-000455 is double pledged")),
                handler("valuate", call -> {
                    valuations.incrementAndGet();
                    return new HandlerResult.Completed(Map.of("value", 1L));
                })).build();
        deploy("loan-preparation", PREPARATION_VALUATION_FIRST);

        // When the application starts and the registry flags the collateral
        ProcessInstance loan = start("loan-preparation", "LA-2027-000455");

        // Then the valuation ran once (only steps behind the terminate are skipped), the instance is COMPLETED and
        // nothing was skipped
        assertThat(valuations.get()).isOne();
        assertThat(engine.services.instance(loan.id()).status()).isEqualTo("COMPLETED");
        assertThat(count("SELECT count(*) FROM pre_history WHERE instance_id = ? AND event_type = 'STEP_SKIPPED'",
                loan.id())).isZero();
    }

    @Test
    void PRE_374_a_retried_local_step_runs_under_its_new_attempt_and_the_instance_goes_on() {
        // Given a registration that answers a business error no boundary catches, so its step opens an incident
        java.util.concurrent.atomic.AtomicInteger registrations = new java.util.concurrent.atomic.AtomicInteger();
        engine.close();
        engine = TestEngine.replica("pod-2", clock).handlers(
                handler("register-collateral", call -> registrations.incrementAndGet() == 1
                        ? new HandlerResult.BusinessError("REGISTRY_DOWN", "collateral registry unreachable")
                        : new HandlerResult.Completed(Map.of())),
                handler("valuate", call -> new HandlerResult.Completed(Map.of("value", 1L)))).build();
        deploy("loan-preparation", PREPARATION);
        ProcessInstance loan = start("loan-preparation", "LA-2027-000456");
        assertThat(engine.services.instance(loan.id()).status()).isEqualTo("RUNNING");
        String incident = text("SELECT id FROM pre_incident WHERE instance_id = ?", loan.id());
        long lock = count("SELECT lock_version FROM pre_incident WHERE id = ?", incident);
        String firstAttempt = text("SELECT activity_instance_id FROM pre_token WHERE instance_id = ? AND "
                + "current_node_id = 'register-collateral'", loan.id());

        // When an operator retries it
        engine.identity.as("bank-a", "ops.lan", OPERATOR);
        engine.services.retry(incident, lock, "Registry is back", "retry-456");

        // Then the handler ran again under a new attempt, which the call accepted, and the instance completed
        assertThat(registrations.get()).isEqualTo(2);
        assertThat(firstAttempt).isNotBlank();
        assertThat(count("SELECT count(*) FROM pre_history WHERE instance_id = ? AND event_type = 'STEP_SKIPPED'",
                loan.id())).isZero();
        assertThat(engine.services.instance(loan.id()).status()).isEqualTo("COMPLETED");
    }
}
