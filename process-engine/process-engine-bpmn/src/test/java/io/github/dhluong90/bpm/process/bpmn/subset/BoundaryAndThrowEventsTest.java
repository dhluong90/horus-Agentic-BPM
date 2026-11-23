package io.github.dhluong90.bpm.process.bpmn.subset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.process.api.DefinitionProblem;
import io.github.dhluong90.bpm.process.api.ValidationFailedException;
import io.github.dhluong90.bpm.process.bpmn.xml.Limits;
import io.github.dhluong90.bpm.process.internal.exec.BehaviorCatalog;
import io.github.dhluong90.bpm.process.internal.exec.Command;
import io.github.dhluong90.bpm.process.internal.exec.CompiledProcess;
import io.github.dhluong90.bpm.process.internal.exec.DefaultExecutionKernel;
import io.github.dhluong90.bpm.process.internal.exec.Effect;
import io.github.dhluong90.bpm.process.internal.exec.HistoryEntry;
import io.github.dhluong90.bpm.process.internal.exec.InstanceState;
import io.github.dhluong90.bpm.process.internal.exec.InstanceStatus;
import io.github.dhluong90.bpm.process.internal.exec.ProcessCompiler;
import io.github.dhluong90.bpm.process.internal.exec.StepResult;
import io.github.dhluong90.bpm.process.internal.exec.Token;
import io.github.dhluong90.bpm.process.internal.exec.TokenKind;
import io.github.dhluong90.bpm.process.internal.exec.TokenStatus;
import io.github.dhluong90.bpm.process.internal.exec.Transition;
import io.github.dhluong90.bpm.process.internal.expr.V1ExpressionEvaluator;
import io.github.dhluong90.bpm.process.internal.model.bpmn.Process;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Boundary events, error and escalation events, terminate and link events on the reader, the compiler and the kernel,
 * in memory (#489, #493): a claim review with an overdue timer, a debit that answers a business error, a disbursement
 * that raises an error or an escalation from inside a sub-process, a screening that terminates its scope, and a link
 * that jumps to the fee step.
 */
class BoundaryAndThrowEventsTest {

    private static final String INSTANCE = "01JD0000000000000000000489";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-23T03:00:00Z"), ZoneOffset.UTC);
    private static final String HEAD = """
            <?xml version="1.0" encoding="UTF-8"?>
            <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                              xmlns:horus="urn:horus-bpm:bpmn:extensions:v1" id="d" targetNamespace="urn:t"
                              expressionLanguage="urn:horus-bpm:expr:v1">
              <bpmn:error id="Err_funds" name="Insufficient funds" errorCode="INSUFFICIENT_FUNDS"/>
              <bpmn:error id="Err_limit" name="Limit exceeded" errorCode="LIMIT_EXCEEDED"/>
              <bpmn:escalation id="Esc_aml" name="AML hit" escalationCode="AML_HIT"/>
              <bpmn:process id="claim-flow" isExecutable="true">
            """;
    private static final String TAIL = "</bpmn:process></bpmn:definitions>";

    /** An officer reviews a claim; a boundary timer of four hours fires a reminder. {@code %s} is the attribute. */
    private static String review(String boundaryAttributes) {
        return HEAD + """
                <bpmn:startEvent id="start"/>
                <bpmn:userTask id="review" horus:assignee="role:CLAIM_OFFICER" horus:resultVariable="outcome"/>
                <bpmn:boundaryEvent id="overdue" attachedToRef="review" %s>
                  <bpmn:timerEventDefinition><bpmn:timeDuration>PT4H</bpmn:timeDuration></bpmn:timerEventDefinition>
                </bpmn:boundaryEvent>
                <bpmn:serviceTask id="remind" horus:handler="send-reminder"/>
                <bpmn:endEvent id="reminded"/>
                <bpmn:endEvent id="end"/>
                <bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="review"/>
                <bpmn:sequenceFlow id="f2" sourceRef="review" targetRef="end"/>
                <bpmn:sequenceFlow id="f3" sourceRef="overdue" targetRef="remind"/>
                <bpmn:sequenceFlow id="f4" sourceRef="remind" targetRef="reminded"/>
                """.formatted(boundaryAttributes) + TAIL;
    }

    /** A debit with an error boundary on the funds code, and a catch-all when {@code catchAll} is true. */
    private static String debit(boolean catchAll) {
        return HEAD + """
                <bpmn:startEvent id="start"/>
                <bpmn:serviceTask id="debit" horus:handler="post-debit"/>
                <bpmn:boundaryEvent id="no-funds" attachedToRef="debit">
                  <bpmn:errorEventDefinition errorRef="Err_funds"/>
                </bpmn:boundaryEvent>
                %s
                <bpmn:endEvent id="paid"/>
                <bpmn:endEvent id="declined"/>
                %s
                <bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="debit"/>
                <bpmn:sequenceFlow id="f2" sourceRef="debit" targetRef="paid"/>
                <bpmn:sequenceFlow id="f3" sourceRef="no-funds" targetRef="declined"/>
                %s
                """.formatted(catchAll ? """
                <bpmn:boundaryEvent id="any-error" attachedToRef="debit">
                  <bpmn:errorEventDefinition/>
                </bpmn:boundaryEvent>""" : "", catchAll ? "<bpmn:endEvent id=\"referred\"/>" : "",
                catchAll ? "<bpmn:sequenceFlow id=\"f4\" sourceRef=\"any-error\" targetRef=\"referred\"/>" : "")
                + TAIL;
    }

    /** A disbursement sub-process whose check ends with an error; a boundary on the sub-process may catch it. */
    private static String disbursement(String boundaryCode) {
        String boundary = boundaryCode == null ? "" : """
                <bpmn:boundaryEvent id="limit" attachedToRef="disburse">
                  <bpmn:errorEventDefinition errorRef="%s"/>
                </bpmn:boundaryEvent>
                <bpmn:serviceTask id="notify-limit" horus:handler="notify-limit"/>
                <bpmn:endEvent id="notified"/>
                <bpmn:sequenceFlow id="g1" sourceRef="limit" targetRef="notify-limit"/>
                <bpmn:sequenceFlow id="g2" sourceRef="notify-limit" targetRef="notified"/>
                """.formatted(boundaryCode);
        return HEAD + """
                <bpmn:startEvent id="start"/>
                <bpmn:subProcess id="disburse">
                  <bpmn:startEvent id="d-start"/>
                  <bpmn:serviceTask id="check-limit" horus:handler="check-limit"/>
                  <bpmn:endEvent id="reject"><bpmn:errorEventDefinition errorRef="Err_limit"/></bpmn:endEvent>
                  <bpmn:sequenceFlow id="d1" sourceRef="d-start" targetRef="check-limit"/>
                  <bpmn:sequenceFlow id="d2" sourceRef="check-limit" targetRef="reject"/>
                </bpmn:subProcess>
                <bpmn:endEvent id="end"/>
                <bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="disburse"/>
                <bpmn:sequenceFlow id="f2" sourceRef="disburse" targetRef="end"/>
                """ + boundary + TAIL;
    }

    /** A KYC sub-process that raises an escalation and then goes on; {@code boundary} catches it, or is empty. */
    private static String kyc(String cancelActivity) {
        String boundary = cancelActivity == null ? "" : """
                <bpmn:boundaryEvent id="aml-watch" attachedToRef="kyc" %s>
                  <bpmn:escalationEventDefinition escalationRef="Esc_aml"/>
                </bpmn:boundaryEvent>
                <bpmn:serviceTask id="aml-review" horus:handler="aml-review"/>
                <bpmn:endEvent id="aml-done"/>
                <bpmn:sequenceFlow id="g1" sourceRef="aml-watch" targetRef="aml-review"/>
                <bpmn:sequenceFlow id="g2" sourceRef="aml-review" targetRef="aml-done"/>
                """.formatted(cancelActivity);
        return HEAD + """
                <bpmn:startEvent id="start"/>
                <bpmn:subProcess id="kyc">
                  <bpmn:startEvent id="k-start"/>
                  <bpmn:intermediateThrowEvent id="raise">
                    <bpmn:escalationEventDefinition escalationRef="Esc_aml"/>
                  </bpmn:intermediateThrowEvent>
                  <bpmn:serviceTask id="verify" horus:handler="verify-id"/>
                  <bpmn:endEvent id="k-end"/>
                  <bpmn:sequenceFlow id="k1" sourceRef="k-start" targetRef="raise"/>
                  <bpmn:sequenceFlow id="k2" sourceRef="raise" targetRef="verify"/>
                  <bpmn:sequenceFlow id="k3" sourceRef="verify" targetRef="k-end"/>
                </bpmn:subProcess>
                <bpmn:endEvent id="end"/>
                <bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="kyc"/>
                <bpmn:sequenceFlow id="f2" sourceRef="kyc" targetRef="end"/>
                """ + boundary + TAIL;
    }

    /** Screening and a manual check in parallel; the screening ends with a terminate. */
    private static final String SCREENING = HEAD + """
                <bpmn:startEvent id="start"/>
                <bpmn:parallelGateway id="fork"/>
                <bpmn:serviceTask id="screen" horus:handler="sanctions-screen"/>
                <bpmn:endEvent id="blocked"><bpmn:terminateEventDefinition/></bpmn:endEvent>
                <bpmn:userTask id="manual-check" horus:assignee="role:COMPLIANCE" horus:resultVariable="check"/>
                <bpmn:endEvent id="checked"/>
                <bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="fork"/>
                <bpmn:sequenceFlow id="f2" sourceRef="fork" targetRef="screen"/>
                <bpmn:sequenceFlow id="f3" sourceRef="fork" targetRef="manual-check"/>
                <bpmn:sequenceFlow id="f4" sourceRef="screen" targetRef="blocked"/>
                <bpmn:sequenceFlow id="f5" sourceRef="manual-check" targetRef="checked"/>
            """ + TAIL;

    /** A sub-process with a parallel branch and a terminate inside; the parent goes on after it. */
    private static final String ONBOARDING = HEAD + """
                <bpmn:startEvent id="start"/>
                <bpmn:subProcess id="onboard">
                  <bpmn:startEvent id="o-start"/>
                  <bpmn:parallelGateway id="o-fork"/>
                  <bpmn:serviceTask id="bureau" horus:handler="credit-bureau"/>
                  <bpmn:endEvent id="o-stop"><bpmn:terminateEventDefinition/></bpmn:endEvent>
                  <bpmn:userTask id="o-review" horus:assignee="role:ONBOARDING" horus:resultVariable="rv"/>
                  <bpmn:endEvent id="o-end"/>
                  <bpmn:sequenceFlow id="o1" sourceRef="o-start" targetRef="o-fork"/>
                  <bpmn:sequenceFlow id="o2" sourceRef="o-fork" targetRef="bureau"/>
                  <bpmn:sequenceFlow id="o3" sourceRef="o-fork" targetRef="o-review"/>
                  <bpmn:sequenceFlow id="o4" sourceRef="bureau" targetRef="o-stop"/>
                  <bpmn:sequenceFlow id="o5" sourceRef="o-review" targetRef="o-end"/>
                </bpmn:subProcess>
                <bpmn:serviceTask id="open-account" horus:handler="open-account"/>
                <bpmn:endEvent id="end"/>
                <bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="onboard"/>
                <bpmn:sequenceFlow id="f2" sourceRef="onboard" targetRef="open-account"/>
                <bpmn:sequenceFlow id="f3" sourceRef="open-account" targetRef="end"/>
            """ + TAIL;

    /** Registration, then a link to the fee step, skipping the optional step in between. */
    private static String linked(String catchName) {
        return HEAD + """
                <bpmn:startEvent id="start"/>
                <bpmn:serviceTask id="register" horus:handler="register-card"/>
                <bpmn:exclusiveGateway id="gw" default="f2b"/>
                <bpmn:intermediateThrowEvent id="to-fees"><bpmn:linkEventDefinition name="fees"/></bpmn:intermediateThrowEvent>
                <bpmn:serviceTask id="optional" horus:handler="optional-step"/>
                <bpmn:intermediateCatchEvent id="from-fees"><bpmn:linkEventDefinition name="%s"/></bpmn:intermediateCatchEvent>
                <bpmn:serviceTask id="charge-fee" horus:handler="charge-fee"/>
                <bpmn:endEvent id="end"/>
                <bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="register"/>
                <bpmn:sequenceFlow id="f2" sourceRef="register" targetRef="gw"/>
                <bpmn:sequenceFlow id="f2b" sourceRef="gw" targetRef="to-fees"/>
                <bpmn:sequenceFlow id="f2c" sourceRef="gw" targetRef="optional">
                  <bpmn:conditionExpression>amount &gt; 1000000</bpmn:conditionExpression>
                </bpmn:sequenceFlow>
                <bpmn:sequenceFlow id="f3" sourceRef="from-fees" targetRef="charge-fee"/>
                <bpmn:sequenceFlow id="f4" sourceRef="charge-fee" targetRef="end"/>
                <bpmn:sequenceFlow id="f5" sourceRef="optional" targetRef="end"/>
                """.formatted(catchName) + TAIL;
    }

    private final SubsetBpmnReader reader = new SubsetBpmnReader(Limits.DEFAULTS);

    private Process read(String xml) {
        return reader.read(xml.getBytes(StandardCharsets.UTF_8));
    }

    private static DefaultExecutionKernel kernel(Process process) {
        CompiledProcess compiled = new ProcessCompiler(BehaviorCatalog.registry(), 500).compile("D1", process);
        return new DefaultExecutionKernel(compiled, BehaviorCatalog.registry(), new V1ExpressionEvaluator(100));
    }

    private static Transition start(DefaultExecutionKernel kernel) {
        return kernel.apply(InstanceState.created(INSTANCE, "bank-a", "D1", "CLM-2026-0001"),
                new Command.Start(Map.of("amount", 500_000L), "officer.hoa"), CLOCK);
    }

    private static Token flow(InstanceState state, String nodeId) {
        return state.tokens().stream().filter(t -> t.nodeId().equals(nodeId) && t.kind() == TokenKind.FLOW)
                .findFirst().orElseThrow();
    }

    private static Token scope(InstanceState state, String nodeId) {
        return state.tokens().stream().filter(t -> t.nodeId().equals(nodeId) && t.kind() == TokenKind.SCOPE)
                .findFirst().orElseThrow();
    }

    private static boolean tokenOn(InstanceState state, String nodeId) {
        return state.tokens().stream().anyMatch(t -> t.nodeId().equals(nodeId) && t.status().live());
    }

    private static <E extends Effect> List<E> effects(Transition t, Class<E> type) {
        return t.effects().stream().filter(type::isInstance).map(type::cast).toList();
    }

    private static Effect.InvokeService call(Transition t, String nodeId) {
        return effects(t, Effect.InvokeService.class).stream().filter(e -> e.nodeId().equals(nodeId)).findFirst()
                .orElseThrow();
    }

    private static Transition answer(DefaultExecutionKernel kernel, Transition scheduled, InstanceState from,
            String nodeId, StepResult result) {
        Effect.InvokeService call = call(scheduled, nodeId);
        return kernel.apply(from, new Command.AsyncResult(call.tokenId(), call.attemptId(), result), CLOCK);
    }

    private static Transition timerFires(DefaultExecutionKernel kernel, Transition scheduled, InstanceState from,
            String boundaryId) {
        Effect.ScheduleJob job = effects(scheduled, Effect.ScheduleJob.class).stream()
                .filter(e -> e.nodeId().equals(boundaryId)).findFirst().orElseThrow();
        return kernel.apply(from, new Command.JobDue(job.jobId(), job.tokenId(), job.jobKind()), CLOCK);
    }

    private static List<String> historyOf(Transition t, String type) {
        return t.history().stream().filter(h -> h.type().equals(type)).map(HistoryEntry::nodeId).toList();
    }

    @Test
    void PRE_280_an_interrupting_timer_boundary_cancels_the_task_and_runs_its_own_flow() {
        // Given a claim review with a four-hour timer boundary that interrupts, started at 03:00 UTC
        DefaultExecutionKernel kernel = kernel(read(review("")));
        Transition started = start(kernel);

        // Then the task is open and the boundary holds one timer job due at 07:00
        Token task = flow(started.state(), "review");
        assertThat(task.status()).isEqualTo(TokenStatus.WAITING_USER);
        Effect.ScheduleJob job = effects(started, Effect.ScheduleJob.class).get(0);
        assertThat(job.nodeId()).isEqualTo("overdue");
        assertThat(job.dueAt()).isEqualTo(Instant.parse("2026-11-23T07:00:00Z"));

        // When the four hours pass
        Transition fired = timerFires(kernel, started, started.state(), "overdue");

        // Then the task is cancelled with its work item, the reminder runs, and the timeline names the boundary
        assertThat(flow(fired.state(), "review").status()).isEqualTo(TokenStatus.CANCELLED);
        assertThat(effects(fired, Effect.CancelUserTask.class)).extracting(Effect.CancelUserTask::taskId)
                .containsExactly(task.activityInstanceId());
        assertThat(call(fired, "remind").target()).isEqualTo("send-reminder");
        HistoryEntry boundary = fired.history().stream().filter(h -> h.type().equals("BOUNDARY_FIRED")).findFirst()
                .orElseThrow();
        assertThat(boundary.details()).containsEntry("boundaryId", "overdue").containsEntry("attachedTo", "review")
                .containsEntry("interrupting", true);

        // And a completion of the cancelled task is refused, so the officer cannot answer a task that is gone
        assertThatThrownBy(() -> kernel.apply(fired.state(), new Command.CompleteTask(task.activityInstanceId(),
                task.id(), Map.of("outcome", "paid"), "officer.lan"), CLOCK))
                .hasMessageContaining("CANCELLED");

        // And the instance completes when the reminder's flow ends
        Transition done = answer(kernel, fired, fired.state(), "remind", new StepResult.Completed(Map.of()));
        assertThat(done.state().status()).isEqualTo(InstanceStatus.COMPLETED);
    }

    @Test
    void PRE_281_a_non_interrupting_timer_boundary_runs_its_flow_once_and_leaves_the_task_open() {
        // Given a claim review whose boundary does not interrupt
        DefaultExecutionKernel kernel = kernel(read(review("cancelActivity=\"false\"")));
        Transition started = start(kernel);

        // When the timer fires
        Transition fired = timerFires(kernel, started, started.state(), "overdue");

        // Then the task is still open, the reminder runs, and no second timer is scheduled
        assertThat(flow(fired.state(), "review").status()).isEqualTo(TokenStatus.WAITING_USER);
        assertThat(effects(fired, Effect.CancelUserTask.class)).isEmpty();
        assertThat(call(fired, "remind")).isNotNull();
        assertThat(effects(fired, Effect.ScheduleJob.class)).isEmpty();
        assertThat(fired.history()).extracting(HistoryEntry::type).contains("BOUNDARY_FIRED");

        // When the reminder ends, the instance is still waiting for the officer
        Transition reminded = answer(kernel, fired, fired.state(), "remind", new StepResult.Completed(Map.of()));
        assertThat(reminded.state().status()).isEqualTo(InstanceStatus.RUNNING);

        // When the officer completes the task, the instance completes
        Token task = flow(reminded.state(), "review");
        Transition completed = kernel.apply(reminded.state(), new Command.CompleteTask(task.activityInstanceId(),
                task.id(), Map.of("outcome", "paid"), "officer.lan"), CLOCK);
        assertThat(completed.state().status()).isEqualTo(InstanceStatus.COMPLETED);
    }

    @Test
    void PRE_281_the_timer_is_cancelled_when_the_task_completes_before_it_fires() {
        // Given a claim review with the timer armed
        DefaultExecutionKernel kernel = kernel(read(review("")));
        Transition started = start(kernel);
        Effect.ScheduleJob job = effects(started, Effect.ScheduleJob.class).get(0);
        Token task = flow(started.state(), "review");

        // When the officer completes the task in time
        Transition completed = kernel.apply(started.state(), new Command.CompleteTask(task.activityInstanceId(),
                task.id(), Map.of("outcome", "paid"), "officer.lan"), CLOCK);

        // Then the timer job is cancelled, the boundary has no live token, and the instance completes
        assertThat(effects(completed, Effect.CancelJob.class)).extracting(Effect.CancelJob::jobId)
                .containsExactly(job.jobId());
        assertThat(tokenOn(completed.state(), "overdue")).isFalse();
        assertThat(completed.state().status()).isEqualTo(InstanceStatus.COMPLETED);
        // And a late firing of that job is refused as stale
        assertThatThrownBy(() -> kernel.apply(completed.state(), new Command.JobDue(job.jobId(), job.tokenId(),
                job.jobKind()), CLOCK)).isNotNull();
    }

    @Test
    void PRE_282_an_error_boundary_takes_the_business_error_with_its_code_and_no_incident_opens() {
        // Given a debit with an error boundary for INSUFFICIENT_FUNDS and a catch-all boundary
        DefaultExecutionKernel kernel = kernel(read(debit(true)));
        Transition started = start(kernel);

        // When the debit answers the business error INSUFFICIENT_FUNDS
        Transition funds = answer(kernel, started, started.state(), "debit",
                new StepResult.BusinessError("INSUFFICIENT_FUNDS", "balance 200,000 VND, debit 500,000 VND"));

        // Then the specific boundary wins over the catch-all, the instance ends at 'declined', and no incident opens
        assertThat(effects(funds, Effect.RaiseIncident.class)).isEmpty();
        assertThat(funds.state().status()).isEqualTo(InstanceStatus.COMPLETED);
        assertThat(historyOf(funds, "NODE_COMPLETED")).contains("no-funds").doesNotContain("any-error");
        assertThat(flow(funds.state(), "debit").status()).isEqualTo(TokenStatus.CANCELLED);

        // When the debit answers another code
        Transition frozen = answer(kernel, started, started.state(), "debit",
                new StepResult.BusinessError("ACCOUNT_FROZEN", "account blocked by court order"));

        // Then the catch-all takes it
        assertThat(effects(frozen, Effect.RaiseIncident.class)).isEmpty();
        assertThat(historyOf(frozen, "NODE_COMPLETED")).contains("any-error").doesNotContain("no-funds");
    }

    @Test
    void PRE_282_an_error_code_no_boundary_matches_opens_an_incident_as_before() {
        // Given a debit with only the INSUFFICIENT_FUNDS boundary
        DefaultExecutionKernel kernel = kernel(read(debit(false)));
        Transition started = start(kernel);

        // When the debit answers ACCOUNT_FROZEN
        Transition frozen = answer(kernel, started, started.state(), "debit",
                new StepResult.BusinessError("ACCOUNT_FROZEN", "account blocked by court order"));

        // Then the token stays on the step and an incident opens with the code
        assertThat(effects(frozen, Effect.RaiseIncident.class)).singleElement().satisfies(i -> {
            assertThat(i.nodeId()).isEqualTo("debit");
            assertThat(i.errorCode()).isEqualTo("UNHANDLED_BUSINESS_ERROR");
            assertThat(i.detail()).isEqualTo("ACCOUNT_FROZEN");
        });
        assertThat(flow(frozen.state(), "debit").status()).isEqualTo(TokenStatus.WAITING_ASYNC);
        assertThat(frozen.state().status()).isEqualTo(InstanceStatus.RUNNING);
    }

    @Test
    void PRE_283_an_error_end_inside_a_sub_process_is_caught_by_the_boundary_on_the_sub_process() {
        // Given a disbursement whose check ends with the error LIMIT_EXCEEDED and a boundary for it on the sub-process
        DefaultExecutionKernel kernel = kernel(read(disbursement("Err_limit")));
        Transition started = start(kernel);

        // When the check completes and the sub-process reaches its error end
        Transition thrown = answer(kernel, started, started.state(), "check-limit", new StepResult.Completed(Map.of()));

        // Then the sub-process is cancelled, the boundary's flow runs in the root scope, and no incident opens
        assertThat(scope(thrown.state(), "disburse").status()).isEqualTo(TokenStatus.CANCELLED);
        assertThat(effects(thrown, Effect.RaiseIncident.class)).isEmpty();
        Token notify = flow(thrown.state(), "notify-limit");
        assertThat(notify.scopeTokenId()).isEqualTo(thrown.state().tokens().stream()
                .filter(t -> t.kind() == TokenKind.SCOPE && t.scopeTokenId() == null).findFirst().orElseThrow().id());
        HistoryEntry error = thrown.history().stream().filter(h -> h.type().equals("ERROR_THROWN")).findFirst()
                .orElseThrow();
        assertThat(error.details()).containsEntry("code", "LIMIT_EXCEEDED").containsEntry("caughtBy", "limit");
    }

    @Test
    void PRE_283_an_error_end_that_no_boundary_catches_opens_an_incident_on_the_end_event() {
        // Given the same disbursement with a boundary for another code
        DefaultExecutionKernel kernel = kernel(read(disbursement("Err_funds")));
        Transition started = start(kernel);

        // When the sub-process reaches its error end
        Transition thrown = answer(kernel, started, started.state(), "check-limit", new StepResult.Completed(Map.of()));

        // Then an incident opens on the end event with the code, and the sub-process is not cancelled
        assertThat(effects(thrown, Effect.RaiseIncident.class)).singleElement().satisfies(i -> {
            assertThat(i.nodeId()).isEqualTo("reject");
            assertThat(i.detail()).isEqualTo("LIMIT_EXCEEDED");
        });
        assertThat(scope(thrown.state(), "disburse").status()).isEqualTo(TokenStatus.WAITING_SCOPE);
        assertThat(thrown.state().status()).isEqualTo(InstanceStatus.RUNNING);
    }

    @Test
    void PRE_284_a_non_interrupting_escalation_boundary_runs_beside_the_sub_process_which_goes_on() {
        // Given a KYC sub-process that raises AML_HIT and goes on, with a non-interrupting boundary for it
        DefaultExecutionKernel kernel = kernel(read(kyc("cancelActivity=\"false\"")));

        // When it starts and reaches the escalation
        Transition started = start(kernel);

        // Then the AML review runs and so does the identity check inside the sub-process
        assertThat(call(started, "aml-review")).isNotNull();
        assertThat(call(started, "verify")).isNotNull();
        assertThat(scope(started.state(), "kyc").status()).isEqualTo(TokenStatus.WAITING_SCOPE);
        HistoryEntry thrown = started.history().stream().filter(h -> h.type().equals("ESCALATION_THROWN")).findFirst()
                .orElseThrow();
        assertThat(thrown.details()).containsEntry("code", "AML_HIT").containsEntry("caughtBy", "aml-watch");
    }

    @Test
    void PRE_284_an_interrupting_escalation_boundary_cancels_the_sub_process() {
        // Given the same sub-process with an interrupting boundary
        DefaultExecutionKernel kernel = kernel(read(kyc("")));

        // When it starts and reaches the escalation
        Transition started = start(kernel);

        // Then the sub-process is cancelled, the identity check never runs, and only the AML review runs
        assertThat(scope(started.state(), "kyc").status()).isEqualTo(TokenStatus.CANCELLED);
        assertThat(effects(started, Effect.InvokeService.class)).extracting(Effect.InvokeService::nodeId)
                .containsExactly("aml-review");
    }

    @Test
    void PRE_284_an_escalation_nothing_catches_is_recorded_and_the_flow_goes_on() {
        // Given the KYC sub-process with no boundary for the escalation
        DefaultExecutionKernel kernel = kernel(read(kyc(null)));

        // When it starts and reaches the escalation
        Transition started = start(kernel);

        // Then the identity check runs, and the timeline records the escalation with no catcher
        assertThat(call(started, "verify")).isNotNull();
        HistoryEntry thrown = started.history().stream().filter(h -> h.type().equals("ESCALATION_THROWN")).findFirst()
                .orElseThrow();
        assertThat(thrown.details()).containsEntry("code", "AML_HIT").doesNotContainKey("caughtBy");
        assertThat(effects(started, Effect.RaiseIncident.class)).isEmpty();
    }

    @Test
    void PRE_285_a_terminate_end_cancels_the_other_branches_and_the_instance_completes() {
        // Given a screening and a manual check running in parallel
        DefaultExecutionKernel kernel = kernel(read(SCREENING));
        Transition started = start(kernel);
        Token manual = flow(started.state(), "manual-check");
        assertThat(manual.status()).isEqualTo(TokenStatus.WAITING_USER);

        // When the screening completes and reaches its terminate end
        Transition ended = answer(kernel, started, started.state(), "screen", new StepResult.Completed(Map.of()));

        // Then the manual check is cancelled with its work item, no token is live, and the instance completes
        assertThat(effects(ended, Effect.CancelUserTask.class)).extracting(Effect.CancelUserTask::taskId)
                .containsExactly(manual.activityInstanceId());
        assertThat(flow(ended.state(), "manual-check").status()).isEqualTo(TokenStatus.CANCELLED);
        assertThat(ended.state().tokens()).noneMatch(t -> t.status().live());
        assertThat(ended.state().status()).isEqualTo(InstanceStatus.COMPLETED);
        assertThat(ended.history()).extracting(HistoryEntry::type).contains("TERMINATED");
    }

    @Test
    void PRE_285_a_terminate_end_inside_a_sub_process_ends_only_that_sub_process() {
        // Given an onboarding sub-process with a bureau call and a review task in parallel
        DefaultExecutionKernel kernel = kernel(read(ONBOARDING));
        Transition started = start(kernel);

        // When the bureau call completes and the inner terminate end is reached
        Transition ended = answer(kernel, started, started.state(), "bureau", new StepResult.Completed(Map.of()));

        // Then the inner review is cancelled, the sub-process completes, and the parent goes on with open-account
        assertThat(flow(ended.state(), "o-review").status()).isEqualTo(TokenStatus.CANCELLED);
        assertThat(scope(ended.state(), "onboard").status()).isEqualTo(TokenStatus.COMPLETED);
        assertThat(call(ended, "open-account")).isNotNull();
        assertThat(ended.state().status()).isEqualTo(InstanceStatus.RUNNING);
    }

    @Test
    void PRE_286_a_link_throw_jumps_to_the_link_catch_of_the_same_name() {
        // Given a card registration whose link 'fees' jumps over the optional step
        DefaultExecutionKernel kernel = kernel(read(linked("fees")));
        Transition started = start(kernel);

        // When the registration completes and the link throw is reached
        Transition jumped = answer(kernel, started, started.state(), "register", new StepResult.Completed(Map.of()));

        // Then the fee is charged next, the optional step never ran, and the timeline names the jump
        assertThat(call(jumped, "charge-fee")).isNotNull();
        assertThat(tokenOn(jumped.state(), "optional")).isFalse();
        HistoryEntry jump = jumped.history().stream().filter(h -> h.type().equals("LINK_JUMPED")).findFirst()
                .orElseThrow();
        assertThat(jump.details()).containsEntry("name", "fees").containsEntry("to", "from-fees");
    }

    @Test
    void PRE_286_a_link_throw_without_a_catch_of_its_name_is_refused_at_import_with_its_element_id() {
        // Given a file whose link throw 'fees' has only a catch named 'charges'
        String xml = linked("charges");

        // When it is imported, Then it is refused naming the throw
        assertThatThrownBy(() -> read(xml)).isInstanceOfSatisfying(ValidationFailedException.class, e ->
                assertThat(e.problems()).extracting(DefinitionProblem::code, DefinitionProblem::elementId)
                        .contains(org.assertj.core.groups.Tuple.tuple("LINK_CATCH_MISSING", "to-fees")));
    }

    @Test
    void PRE_287_the_new_events_export_and_read_back_to_the_same_definition() {
        // Given definitions that use every new element
        for (String xml : List.of(review("cancelActivity=\"false\""), debit(true), disbursement("Err_limit"),
                kyc("cancelActivity=\"false\""), SCREENING, linked("fees"))) {
            Process original = read(xml);

            // When it is exported and imported again, Then the canonical form, which the version hash is made of, is equal
            Process again = read(new String(new SubsetBpmnWriter().write(original), StandardCharsets.UTF_8));
            assertThat(io.github.dhluong90.bpm.process.internal.json.CanonicalProcessWriter.canonicalText(again))
                    .isEqualTo(io.github.dhluong90.bpm.process.internal.json.CanonicalProcessWriter.canonicalText(original));
        }
    }
}
