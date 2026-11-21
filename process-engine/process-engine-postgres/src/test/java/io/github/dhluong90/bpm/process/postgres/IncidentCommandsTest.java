package io.github.dhluong90.bpm.process.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.process.api.Incident;
import io.github.dhluong90.bpm.process.api.IncidentNotFoundException;
import io.github.dhluong90.bpm.process.api.IncidentQuery;
import io.github.dhluong90.bpm.process.api.InputInvalidException;
import io.github.dhluong90.bpm.process.api.InvalidStateException;
import io.github.dhluong90.bpm.process.api.Page;
import io.github.dhluong90.bpm.process.api.PageRequest;
import io.github.dhluong90.bpm.process.api.PermissionDeniedException;
import io.github.dhluong90.bpm.process.api.PreconditionRequiredException;
import io.github.dhluong90.bpm.process.api.ProcessException;
import io.github.dhluong90.bpm.process.api.ProcessInstance;
import io.github.dhluong90.bpm.process.api.QueryInvalidException;
import io.github.dhluong90.bpm.process.api.SelfApprovalForbiddenException;
import io.github.dhluong90.bpm.process.api.SourceFormat;
import io.github.dhluong90.bpm.process.api.StartRequest;
import io.github.dhluong90.bpm.process.api.VersionConflictException;
import io.github.dhluong90.bpm.process.api.ImportRequest;
import io.github.dhluong90.bpm.process.internal.service.EngineSettings;
import io.github.dhluong90.bpm.process.spi.HandlerResult;
import io.github.dhluong90.bpm.process.spi.ProcessEventPublisher;
import io.github.dhluong90.bpm.process.spi.ServiceCall;
import io.github.dhluong90.bpm.process.spi.ServiceTaskHandler;
import io.github.dhluong90.bpm.process.test.TestClock;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Retry and resolve incidents (#68, PRE-077 to PRE-080, PD-126) on PostgreSQL, for a local step. A loan disbursement
 * records the request, then its local step {@code check-limit} asks the limits handler; while the daily limit is
 * reached the handler answers the business error {@code LIMIT_EXCEEDED}, which opens an incident and leaves the token
 * on the step. Operators {@code ops.lan} and {@code ops.minh} hold {@code processes:incident:manage}. The clock is a
 * {@link TestClock}; no test sleeps.
 */
class IncidentCommandsTest {

    private static final String[] OPERATOR = {"processes:incident:manage", "processes:read", "processes:cancel"};
    private static final Map<String, Object> RAISED_LIMIT = Map.of("approvedLimit",
            Map.of("amount", "30000000", "currency", "VND"));
    private static final String DISBURSEMENT = """
            <?xml version="1.0" encoding="UTF-8"?>
            <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                              xmlns:horus="urn:horus-bpm:bpmn:extensions:v1" id="Definitions_loan-disbursement"
                              targetNamespace="urn:horus-bpm:examples" expressionLanguage="urn:horus-bpm:expr:v1">
              <bpmn:process id="loan-disbursement" name="Loan disbursement" isExecutable="true">
                <bpmn:startEvent id="start"/>
                <bpmn:serviceTask id="record-request" horus:handler="disbursements.record" horus:resultVariable="request">
                  <bpmn:extensionElements>
                    <horus:input name="amount" value="${instance.amount}"/>
                  </bpmn:extensionElements>
                </bpmn:serviceTask>
                <bpmn:serviceTask id="check-limit" horus:handler="limits.check" horus:resultVariable="limit">
                  <bpmn:extensionElements>
                    <horus:input name="accountId" value="${instance.accountId}"/>
                  </bpmn:extensionElements>
                </bpmn:serviceTask>
                <bpmn:userTask id="disburse" horus:assignee="role:LOAN_OFFICER" horus:resultVariable="disbursal"/>
                <bpmn:endEvent id="end"/>
                <bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="record-request"/>
                <bpmn:sequenceFlow id="f2" sourceRef="record-request" targetRef="check-limit"/>
                <bpmn:sequenceFlow id="f3" sourceRef="check-limit" targetRef="disburse"/>
                <bpmn:sequenceFlow id="f4" sourceRef="disburse" targetRef="end"/>
              </bpmn:process>
            </bpmn:definitions>
            """;

    private final TestClock clock = TestClock.at("2027-05-03T02:00:00Z");
    private final AtomicInteger limitChecks = new AtomicInteger();
    /** What the limits handler answers; the daily limit is reached until a test lifts it. */
    private final AtomicReference<HandlerResult> limits = new AtomicReference<>(
            new HandlerResult.BusinessError("LIMIT_EXCEEDED", "Daily limit reached for account ACC-0001"));

    @BeforeEach
    void clean() {
        TestEngine.reset();
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

    private TestEngine engine(EngineSettings.ResolveApproval approval) {
        TestEngine engine = TestEngine.replica("pod-1", clock).resolveApproval(approval).handlers(
                handler("disbursements.record", call -> HandlerResult.completed(Map.of("recordedAmount",
                        call.inputs().get("amount")))),
                handler("limits.check", call -> {
                    limitChecks.incrementAndGet();
                    return limits.get();
                })).build();
        for (String tenant : List.of("bank-a", "bank-b")) {
            engine.identity.as(tenant, "pipeline", "processes:deploy", "processes:start", "processes:read");
            engine.services.importDefinition(new ImportRequest(SourceFormat.BPMN_XML,
                    DISBURSEMENT.strip().getBytes(StandardCharsets.UTF_8), null));
        }
        return engine;
    }

    private TestEngine engine() {
        return engine(EngineSettings.ResolveApproval.FOUR_EYES);
    }

    /** Starts a disbursement in the tenant; it stops on check-limit with an incident. */
    private static ProcessInstance disburse(TestEngine engine, String tenant, String reference) {
        engine.identity.as(tenant, "teller", "processes:start", "processes:read");
        return engine.services.start(new StartRequest("loan-disbursement", reference,
                Map.of("accountId", "ACC-0001", "amount", 50_000_000), null));
    }

    private static Incident incidentOf(TestEngine engine, String tenant, String instanceId) {
        engine.identity.as(tenant, "ops.lan", OPERATOR);
        return engine.services.openIncidents(instanceId).get(0);
    }

    private static String tokenOn(TestEngine engine, String instanceId) {
        return engine.services.instance(instanceId).tokens().stream().filter(t -> t.kind().equals("FLOW")
                && !t.status().equals("COMPLETED")).map(t -> t.nodeId() + ":" + t.status()).findFirst().orElse("-");
    }

    /** Every row a refused command could write, in the whole database. */
    private static Map<String, Object> footprint() {
        return TestEngine.jdbc().sql("SELECT (SELECT count(*) FROM pre_history) AS history, "
                + "(SELECT count(*) FROM pre_incident) AS incidents, "
                + "(SELECT coalesce(sum(lock_version), 0) FROM pre_incident) AS locks, "
                + "(SELECT count(*) FROM pre_task) AS tasks, "
                + "(SELECT count(*) FROM pre_idempotency) AS keys").query().singleRow();
    }

    private static List<String> history(String instanceId, String type) {
        return TestEngine.jdbc().sql("SELECT coalesce(actor_id, '-') || ' ' || details::text FROM pre_history "
                + "WHERE instance_id = ? AND event_type = ? ORDER BY occurred_at, id").params(instanceId, type)
                .query(String.class).list();
    }

    private static List<ProcessEventPublisher.Event> events(TestEngine engine, String type) {
        return engine.events.stream().filter(e -> e.type().equals(type)).toList();
    }

    // ---- PRE-077 ----------------------------------------------------------------------------------------------

    @Test
    void PRE_077_retrying_a_local_step_runs_its_handler_again_and_a_success_resolves_the_incident() {
        // Given DISB-2027-0077 stopped on check-limit with LIMIT_EXCEEDED, and the limit has since been raised
        TestEngine pod = engine();
        ProcessInstance loan = disburse(pod, "bank-a", "DISB-2027-0077");
        Incident incident = incidentOf(pod, "bank-a", loan.id());
        assertThat(incident.allowedActions()).containsExactly("retry", "resolve");
        limits.set(HandlerResult.completed(RAISED_LIMIT));

        // When ops.lan retries it, then repeats the same request
        Incident retried = pod.services.retry(incident.id(), 0, "Limit raised by the branch manager", "r-77");
        Map<String, Object> afterFirst = footprint();
        Incident again = pod.services.retry(incident.id(), 0, "Limit raised by the branch manager", "r-77");

        // Then the handler ran once more, the step completed and the incident is resolved by the retry
        assertThat(limitChecks).hasValue(2);
        assertThat(retried.status()).isEqualTo("RESOLVED");
        assertThat(retried.resolution()).isEqualTo("RETRY_SUCCEEDED");
        assertThat(retried.retryCount()).isEqualTo(1);
        assertThat(retried.allowedActions()).isEmpty();
        assertThat(tokenOn(pod, loan.id())).isEqualTo("disburse:WAITING_USER");
        assertThat(pod.services.instance(loan.id()).variables()).containsEntry("limit", RAISED_LIMIT);
        assertThat(history(loan.id(), "INCIDENT_RETRIED")).singleElement().asString()
                .startsWith("ops.lan ").contains("\"reason\": \"Limit raised by the branch manager\"");
        assertThat(history(loan.id(), "INCIDENT_RESOLVED")).singleElement().asString()
                .contains("\"resolution\": \"RETRY_SUCCEEDED\"");
        assertThat(events(pod, "processes.incident.retried.v1")).singleElement()
                .satisfies(e -> assertThat(e.payload()).containsEntry("retriedBy", "ops.lan"));
        assertThat(events(pod, "processes.incident.resolved.v1")).singleElement()
                .satisfies(e -> assertThat(e.payload()).containsEntry("resolution", "RETRY_SUCCEEDED")
                        .containsEntry("incidentId", incident.id()));
        // And the repeat replayed the first answer and wrote nothing
        assertThat(again.status()).isEqualTo("RESOLVED");
        assertThat(again.lockVersion()).isEqualTo(retried.lockVersion());
        assertThat(footprint()).isEqualTo(afterFirst);
    }

    @Test
    void PRE_077_a_retried_local_step_that_fails_again_keeps_the_same_incident_open_with_the_new_cause() {
        // Given DISB-2027-0078 stopped on check-limit, and the limit is still reached, now for a frozen account
        TestEngine pod = engine();
        ProcessInstance loan = disburse(pod, "bank-a", "DISB-2027-0078");
        Incident incident = incidentOf(pod, "bank-a", loan.id());
        limits.set(new HandlerResult.BusinessError("ACCOUNT_FROZEN", "Account ACC-0001 is frozen"));

        // When ops.lan retries it
        Incident retried = pod.services.retry(incident.id(), 0, "Try again after the cut-off", "r-78");

        // Then the same incident stays open with the new cause, and no second incident is opened
        assertThat(retried.status()).isEqualTo("OPEN");
        assertThat(retried.id()).isEqualTo(incident.id());
        assertThat(retried.retryCount()).isEqualTo(1);
        assertThat(retried.causeSummary()).contains("ACCOUNT_FROZEN").doesNotContain("ACC-0001");
        assertThat(retried.retrying()).isFalse();
        assertThat(retried.allowedActions()).containsExactly("retry", "resolve");
        assertThat(TestEngine.jdbc().sql("SELECT count(*) FROM pre_incident WHERE instance_id = ?").param(loan.id())
                .query(Integer.class).single()).isEqualTo(1);
        assertThat(tokenOn(pod, loan.id())).isEqualTo("check-limit:WAITING_ASYNC");
        assertThat(history(loan.id(), "INCIDENT_RAISED")).hasSize(2).last().asString()
                .contains("\"failedAgain\": true");
    }

    // ---- PRE-078 ----------------------------------------------------------------------------------------------

    @Test
    void PRE_078_a_resolution_needs_a_second_person_and_only_the_approval_moves_the_token() {
        // Given DISB-2027-0080 stopped on check-limit
        TestEngine pod = engine();
        ProcessInstance loan = disburse(pod, "bank-a", "DISB-2027-0080");
        Incident incident = incidentOf(pod, "bank-a", loan.id());

        // When ops.lan proposes the limit the credit committee approved
        Incident proposed = pod.services.resolve(incident.id(), 0, RAISED_LIMIT,
                "Limit raised by credit committee, memo CC-118", "p-80");

        // Then the incident waits for a second person and the token has not moved
        assertThat(proposed.status()).isEqualTo("RESOLUTION_PENDING");
        assertThat(proposed.pendingResolution().proposedBy()).isEqualTo("ops.lan");
        assertThat(proposed.pendingResolution().approvalMode()).isEqualTo("four-eyes");
        assertThat(proposed.allowedActions()).isEmpty();
        assertThat(tokenOn(pod, loan.id())).isEqualTo("check-limit:WAITING_ASYNC");
        assertThat(pod.services.instance(loan.id()).variables()).doesNotContainKey("limit");

        // When ops.lan approves her own proposal
        Map<String, Object> before = footprint();
        assertThatThrownBy(() -> pod.services.approveResolution(incident.id(), 1, null, "a-self"))
                .isInstanceOf(SelfApprovalForbiddenException.class);
        assertThatThrownBy(() -> pod.services.rejectResolution(incident.id(), 1, "no", "j-self"))
                .isInstanceOf(SelfApprovalForbiddenException.class);
        // Then nothing is written
        assertThat(footprint()).isEqualTo(before);

        // When ops.minh, who sees the proposal and may decide it, approves
        pod.identity.as("bank-a", "ops.minh", OPERATOR);
        assertThat(pod.services.incident(incident.id()).allowedActions())
                .containsExactly("approve-resolution", "reject-resolution");
        Incident resolved = pod.services.approveResolution(incident.id(), 1, "Checked against memo CC-118", "a-80");

        // Then the step completes with the proposed output, the token moves on, and both identities are recorded
        assertThat(resolved.status()).isEqualTo("RESOLVED");
        assertThat(resolved.resolution()).isEqualTo("COMPLETED_MANUALLY");
        assertThat(resolved.resolvedBy()).isEqualTo("ops.minh");
        assertThat(tokenOn(pod, loan.id())).isEqualTo("disburse:WAITING_USER");
        assertThat(pod.services.instance(loan.id()).variables()).containsEntry("limit", RAISED_LIMIT);
        assertThat(limitChecks).hasValue(1);
        assertThat(history(loan.id(), "INCIDENT_RESOLUTION_PROPOSED")).singleElement().asString()
                .startsWith("ops.lan ").contains("memo CC-118").contains("30000000");
        assertThat(history(loan.id(), "INCIDENT_RESOLVED")).singleElement().asString().startsWith("ops.minh ")
                .contains("\"proposedBy\": \"ops.lan\"").contains("\"resolution\": \"COMPLETED_MANUALLY\"")
                .contains("\"approvalMode\": \"four-eyes\"");
        assertThat(events(pod, "processes.incident.resolved.v1")).singleElement()
                .satisfies(e -> assertThat(e.payload()).containsEntry("resolvedBy", "ops.minh")
                        .containsEntry("proposedBy", "ops.lan").containsEntry("resolution", "COMPLETED_MANUALLY"));
    }

    @Test
    void PRE_078_a_rejected_proposal_leaves_the_incident_open_and_the_token_on_the_step() {
        // Given ops.lan proposed a limit for DISB-2027-0081
        TestEngine pod = engine();
        ProcessInstance loan = disburse(pod, "bank-a", "DISB-2027-0081");
        Incident incident = incidentOf(pod, "bank-a", loan.id());
        pod.services.resolve(incident.id(), 0, RAISED_LIMIT, "Limit raised by credit committee", "p-81");

        // When ops.minh rejects it
        pod.identity.as("bank-a", "ops.minh", OPERATOR);
        Incident open = pod.services.rejectResolution(incident.id(), 1, "Memo CC-118 approves 25,000,000 VND", "j-81");

        // Then the incident is open again without a proposal, and the step still waits
        assertThat(open.status()).isEqualTo("OPEN");
        assertThat(open.pendingResolution()).isNull();
        assertThat(open.allowedActions()).containsExactly("retry", "resolve");
        assertThat(tokenOn(pod, loan.id())).isEqualTo("check-limit:WAITING_ASYNC");
        assertThat(history(loan.id(), "INCIDENT_RESOLUTION_REJECTED")).singleElement().asString()
                .startsWith("ops.minh ").contains("\"proposedBy\": \"ops.lan\"");
    }

    @Test
    void PRE_078_a_resolution_needs_an_output_and_a_reason_within_the_limit() {
        // Given DISB-2027-0082 stopped on check-limit
        TestEngine pod = engine();
        ProcessInstance loan = disburse(pod, "bank-a", "DISB-2027-0082");
        Incident incident = incidentOf(pod, "bank-a", loan.id());
        Map<String, Object> before = footprint();

        // When the reason is missing, blank or 2,001 characters, or the output is missing
        // Then each is refused as input, and nothing is written
        assertThatThrownBy(() -> pod.services.resolve(incident.id(), 0, RAISED_LIMIT, null, "p-1"))
                .isInstanceOf(InputInvalidException.class);
        assertThatThrownBy(() -> pod.services.resolve(incident.id(), 0, RAISED_LIMIT, " ", "p-2"))
                .isInstanceOf(InputInvalidException.class);
        assertThatThrownBy(() -> pod.services.resolve(incident.id(), 0, RAISED_LIMIT, "x".repeat(2001), "p-3"))
                .isInstanceOf(InputInvalidException.class);
        assertThatThrownBy(() -> pod.services.resolve(incident.id(), 0, null, "Limit raised", "p-4"))
                .isInstanceOf(InputInvalidException.class);
        assertThatThrownBy(() -> pod.services.retry(incident.id(), 0, "Limit raised", null))
                .isInstanceOf(PreconditionRequiredException.class);
        assertThat(footprint()).isEqualTo(before);
    }

    @Test
    void PRE_078_single_person_resolution_applies_at_once_and_is_recorded_as_single() {
        // Given incidents.resolve-approval: single (accepted on the dev profile only, see the starter tests)
        TestEngine pod = engine(EngineSettings.ResolveApproval.SINGLE);
        ProcessInstance loan = disburse(pod, "bank-a", "DISB-2027-0083");
        Incident incident = incidentOf(pod, "bank-a", loan.id());

        // When ops.lan resolves it
        Incident resolved = pod.services.resolve(incident.id(), 0, RAISED_LIMIT, "Test data on a dev host", "p-83");

        // Then it is resolved by her alone, recorded as single
        assertThat(resolved.status()).isEqualTo("RESOLVED");
        assertThat(resolved.resolvedBy()).isEqualTo("ops.lan");
        assertThat(resolved.pendingResolution().approvalMode()).isEqualTo("single");
        assertThat(tokenOn(pod, loan.id())).isEqualTo("disburse:WAITING_USER");
    }

    @Test
    void PRE_078_the_table_refuses_a_four_eyes_resolution_by_its_proposer() {
        // Given ops.lan proposed a resolution
        TestEngine pod = engine();
        ProcessInstance loan = disburse(pod, "bank-a", "DISB-2027-0084");
        Incident incident = incidentOf(pod, "bank-a", loan.id());
        pod.services.resolve(incident.id(), 0, RAISED_LIMIT, "Limit raised", "p-84");

        // When a write past the engine records her as the resolver too, then the database refuses it
        assertThatThrownBy(() -> TestEngine.jdbc().sql("UPDATE pre_incident SET status = 'RESOLVED', resolved_by = "
                + "proposed_by, resolution = 'COMPLETED_MANUALLY' WHERE id = ?").param(incident.id()).update())
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_pre_incident_four_eyes");
    }

    // ---- PRE-079 ----------------------------------------------------------------------------------------------

    @Test
    void PRE_079_a_stale_repeated_or_late_command_is_refused_and_writes_nothing() {
        // Given DISB-2027-0090 stopped on check-limit, with ops.lan's proposal pending at lock version 1
        TestEngine pod = engine();
        ProcessInstance loan = disburse(pod, "bank-a", "DISB-2027-0090");
        Incident incident = incidentOf(pod, "bank-a", loan.id());
        pod.services.resolve(incident.id(), 0, RAISED_LIMIT, "Limit raised", "p-90");
        Map<String, Object> before = footprint();

        // Then a command with the old lock version, a retry or a new proposal while one is pending, and the same key
        // with another body are each refused, and nothing is written
        pod.identity.as("bank-a", "ops.minh", OPERATOR);
        assertThatThrownBy(() -> pod.services.approveResolution(incident.id(), 0, null, "a-stale"))
                .isInstanceOf(VersionConflictException.class);
        assertThatThrownBy(() -> pod.services.retry(incident.id(), 1, "Try again", "r-pending"))
                .isInstanceOf(InvalidStateException.class);
        assertThatThrownBy(() -> pod.services.resolve(incident.id(), 1, RAISED_LIMIT, "Mine instead", "p-pending"))
                .isInstanceOf(InvalidStateException.class);
        pod.identity.as("bank-a", "ops.lan", OPERATOR);
        assertThatThrownBy(() -> pod.services.resolve(incident.id(), 0, Map.of("approvedLimit", Map.of("amount",
                "25000000", "currency", "VND")), "Limit raised", "p-90"))
                .isInstanceOf(ProcessException.class)
                .extracting(e -> ((ProcessException) e).code()).isEqualTo("IDEMPOTENCY_KEY_REUSE_MISMATCH");
        assertThat(footprint()).isEqualTo(before);

        // When ops.minh approves, then approves again under a new key
        pod.identity.as("bank-a", "ops.minh", OPERATOR);
        pod.services.approveResolution(incident.id(), 1, null, "a-90");
        assertThatThrownBy(() -> pod.services.approveResolution(incident.id(), 2, null, "a-91"))
                .isInstanceOf(InvalidStateException.class);
        assertThatThrownBy(() -> pod.services.retry(incident.id(), 2, "Again", "r-91"))
                .isInstanceOf(InvalidStateException.class);
    }

    @Test
    void PRE_079_cancelling_the_instance_resolves_its_incident_in_the_cancel_transaction() {
        // Given DISB-2027-0091 stopped on check-limit with an open incident
        TestEngine pod = engine();
        ProcessInstance loan = disburse(pod, "bank-a", "DISB-2027-0091");
        Incident incident = incidentOf(pod, "bank-a", loan.id());

        // When ops.lan cancels the disbursement
        ProcessInstance cancelled = pod.services.cancel(loan.id(), pod.services.instance(loan.id()).lockVersion(),
                "Customer withdrew the loan", "c-91");

        // Then the incident is resolved as cancelled by her, with the event
        assertThat(cancelled.status()).isEqualTo("CANCELLED");
        assertThat(pod.services.incident(incident.id())).satisfies(i -> {
            assertThat(i.status()).isEqualTo("RESOLVED");
            assertThat(i.resolution()).isEqualTo("INSTANCE_CANCELLED");
            assertThat(i.resolvedBy()).isEqualTo("ops.lan");
        });
        assertThat(pod.services.openIncidents(loan.id())).isEmpty();
        assertThat(events(pod, "processes.incident.resolved.v1")).singleElement()
                .satisfies(e -> assertThat(e.payload()).containsEntry("resolution", "INSTANCE_CANCELLED"));
    }

    // ---- PRE-080 ----------------------------------------------------------------------------------------------

    @Test
    void PRE_080_incidents_are_listed_in_the_callers_tenant_only_newest_first_with_a_signed_cursor() {
        // Given three stopped disbursements at bank-a, a minute apart, and one at bank-b
        TestEngine pod = engine();
        for (int i = 1; i <= 3; i++) {
            disburse(pod, "bank-a", "DISB-A-" + i);
            clock.advance(Duration.ofMinutes(1));
        }
        ProcessInstance other = disburse(pod, "bank-b", "DISB-B-1");
        Incident inc9 = incidentOf(pod, "bank-b", other.id());

        // When a bank-a operator lists the open incidents two at a time
        pod.identity.as("bank-a", "ops.lan", OPERATOR);
        Page<Incident> first = pod.services.incidents(new IncidentQuery("OPEN", new PageRequest(2, null)));
        Page<Incident> second = pod.services.incidents(new IncidentQuery("OPEN", new PageRequest(2,
                first.nextCursor())));

        // Then they see their three, newest first, each with its cause, step, instance and actions
        List<Incident> all = new java.util.ArrayList<>(first.items());
        all.addAll(second.items());
        assertThat(all).hasSize(3).allSatisfy(i -> {
            assertThat(i.errorCode()).isEqualTo("UNHANDLED_BUSINESS_ERROR");
            assertThat(i.nodeId()).isEqualTo("check-limit");
            assertThat(i.allowedActions()).containsExactly("retry", "resolve");
        }).extracting(Incident::id).doesNotContain(inc9.id());
        assertThat(all).extracting(Incident::createdAt).isSortedAccordingTo(java.util.Comparator.reverseOrder());
        assertThat(second.hasNext()).isFalse();
        // And the cursor is bound to the tenant and the status
        String cursor = first.nextCursor();
        assertThatThrownBy(() -> pod.services.incidents(new IncidentQuery("RESOLVED", new PageRequest(2, cursor))))
                .isInstanceOf(QueryInvalidException.class);
        assertThatThrownBy(() -> pod.services.incidents(new IncidentQuery("CLOSED", null)))
                .isInstanceOf(QueryInvalidException.class);
        pod.identity.as("bank-b", "ops.lan", OPERATOR);
        assertThatThrownBy(() -> pod.services.incidents(new IncidentQuery("OPEN", new PageRequest(2, cursor))))
                .isInstanceOf(QueryInvalidException.class);
    }

    @Test
    void PRE_080_another_tenants_incident_is_not_found_for_every_command_and_nothing_is_written() {
        // Given INC-9 open at bank-b
        TestEngine pod = engine();
        ProcessInstance other = disburse(pod, "bank-b", "DISB-B-9");
        Incident inc9 = incidentOf(pod, "bank-b", other.id());
        Map<String, Object> before = footprint();

        // When a bank-a operator reads, retries, resolves, approves or rejects it
        pod.identity.as("bank-a", "ops.lan", OPERATOR);
        for (Runnable command : List.<Runnable>of(() -> pod.services.incident(inc9.id()),
                () -> pod.services.retry(inc9.id(), 0, "Try again", "r-9"),
                () -> pod.services.resolve(inc9.id(), 0, RAISED_LIMIT, "Limit raised", "p-9"),
                () -> pod.services.approveResolution(inc9.id(), 0, null, "a-9"),
                () -> pod.services.rejectResolution(inc9.id(), 0, "No", "j-9"))) {
            // Then each is not found, and nothing is written
            assertThatThrownBy(command::run).isInstanceOf(IncidentNotFoundException.class)
                    .extracting(e -> ((ProcessException) e).code()).isEqualTo("INCIDENT_NOT_FOUND");
        }
        assertThat(footprint()).isEqualTo(before);
        assertThat(limitChecks).hasValue(1);
    }

    @Test
    void PRE_080_a_reader_sees_incidents_without_actions_and_cannot_retry_or_resolve() {
        // Given DISB-2027-0095 stopped on check-limit
        TestEngine pod = engine();
        ProcessInstance loan = disburse(pod, "bank-a", "DISB-2027-0095");
        Incident incident = incidentOf(pod, "bank-a", loan.id());
        Map<String, Object> before = footprint();

        // When a bank-a user with processes:read only lists, retries and resolves
        pod.identity.as("bank-a", "auditor", "processes:read");
        assertThat(pod.services.incidents(IncidentQuery.open()).items()).singleElement()
                .satisfies(i -> assertThat(i.allowedActions()).isEmpty());

        // Then both commands are refused, and nothing is written
        assertThatThrownBy(() -> pod.services.retry(incident.id(), 0, "Try again", "r-95"))
                .isInstanceOf(PermissionDeniedException.class);
        assertThatThrownBy(() -> pod.services.resolve(incident.id(), 0, RAISED_LIMIT, "Limit raised", "p-95"))
                .isInstanceOf(PermissionDeniedException.class);
        assertThat(footprint()).isEqualTo(before);
    }
}
