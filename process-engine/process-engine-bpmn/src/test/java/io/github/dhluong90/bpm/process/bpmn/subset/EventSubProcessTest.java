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
import io.github.dhluong90.bpm.process.internal.json.CanonicalProcessWriter;
import io.github.dhluong90.bpm.process.internal.model.bpmn.Process;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Event sub-processes on the reader, the compiler and the kernel, in memory (#487): a loan application the applicant
 * withdraws or the underwriter is reminded of, a disbursement whose limit check raises an error, a card activation
 * that reprices on a rate change. Interrupting cancels the work of the scope, non-interrupting runs beside it, each
 * trigger starts the handler, and an error or an escalation raised inside is caught by the scope's own handler.
 */
class EventSubProcessTest {

    private static final String INSTANCE = "01JD0000000000000000000487";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-23T03:00:00Z"), ZoneOffset.UTC);
    private static final String HEAD = """
            <?xml version="1.0" encoding="UTF-8"?>
            <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                              xmlns:horus="urn:horus-bpm:bpmn:extensions:v1" id="d" targetNamespace="urn:t"
                              expressionLanguage="urn:horus-bpm:expr:v1">
              <bpmn:error id="Err_limit" name="Limit exceeded" errorCode="LIMIT_EXCEEDED"/>
              <bpmn:error id="Err_funds" name="Insufficient funds" errorCode="INSUFFICIENT_FUNDS"/>
              <bpmn:escalation id="Esc_aml" name="AML hit" escalationCode="AML_HIT"/>
              <bpmn:message id="Msg_withdrawn" name="applicant-withdrew"/>
              <bpmn:signal id="Sig_rate" name="rate-card-updated"/>
              <bpmn:process id="p" isExecutable="true">
            """;
    private static final String TAIL = "</bpmn:process></bpmn:definitions>";

    /** An underwriter assesses an application; {@code extra} holds the event sub-processes. */
    private static String assessment(String extra) {
        return HEAD + """
                <bpmn:startEvent id="start"/>
                <bpmn:userTask id="assess" horus:assignee="role:UNDERWRITER" horus:resultVariable="assessment"/>
                <bpmn:endEvent id="end"/>
                <bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="assess"/>
                <bpmn:sequenceFlow id="f2" sourceRef="assess" targetRef="end"/>
                """ + extra + TAIL;
    }

    private static String handler(String prefix, String start, String task) {
        return """
                <bpmn:serviceTask id="%1$s-do" horus:handler="%2$s"/>
                <bpmn:endEvent id="%1$s-end"/>
                <bpmn:sequenceFlow id="%1$s-1" sourceRef="%3$s" targetRef="%1$s-do"/>
                <bpmn:sequenceFlow id="%1$s-2" sourceRef="%1$s-do" targetRef="%1$s-end"/>
                """.formatted(prefix, task, start);
    }

    private static String message(boolean interrupting) {
        return """
                <bpmn:subProcess id="withdrawal" triggeredByEvent="true">
                  <bpmn:startEvent id="withdrawn" isInterrupting="%s" horus:correlationKey="applicationId">
                    <bpmn:messageEventDefinition messageRef="Msg_withdrawn"/>
                  </bpmn:startEvent>
                  %s
                </bpmn:subProcess>""".formatted(interrupting, handler("w", "withdrawn", "close-application-file"));
    }

    private static String timer(boolean interrupting) {
        return """
                <bpmn:subProcess id="reminder" triggeredByEvent="true">
                  <bpmn:startEvent id="after-a-day" isInterrupting="%s">
                    <bpmn:timerEventDefinition><bpmn:timeDuration>PT24H</bpmn:timeDuration></bpmn:timerEventDefinition>
                  </bpmn:startEvent>
                  %s
                </bpmn:subProcess>""".formatted(interrupting, handler("r", "after-a-day", "send-underwriter-reminder"));
    }

    private static String signal(boolean interrupting) {
        return """
                <bpmn:subProcess id="reprice" triggeredByEvent="true">
                  <bpmn:startEvent id="rate-changed" isInterrupting="%s">
                    <bpmn:signalEventDefinition signalRef="Sig_rate"/>
                  </bpmn:startEvent>
                  %s
                </bpmn:subProcess>""".formatted(interrupting, handler("p", "rate-changed", "reprice-loan-offer"));
    }

    private static String conditional(boolean interrupting) {
        return """
                <bpmn:subProcess id="risk" triggeredByEvent="true">
                  <bpmn:startEvent id="risky" isInterrupting="%s">
                    <bpmn:conditionalEventDefinition><bpmn:condition>assessment.score &gt;= 80</bpmn:condition>
                    </bpmn:conditionalEventDefinition>
                  </bpmn:startEvent>
                  %s
                </bpmn:subProcess>""".formatted(interrupting, handler("c", "risky", "notify-compliance"));
    }

    /** A disbursement sub-process whose limit check raises an error while a manual review runs in parallel. */
    private static String disbursement(String eventSubProcess) {
        return HEAD + """
                <bpmn:startEvent id="start"/>
                <bpmn:subProcess id="disburse">
                  <bpmn:startEvent id="d-start"/>
                  <bpmn:parallelGateway id="fork"/>
                  <bpmn:serviceTask id="check-limit" horus:handler="check-limit"/>
                  <bpmn:endEvent id="reject"><bpmn:errorEventDefinition errorRef="Err_limit"/></bpmn:endEvent>
                  <bpmn:userTask id="manual-review" horus:assignee="role:TREASURY" horus:resultVariable="review"/>
                  <bpmn:endEvent id="reviewed"/>
                  <bpmn:sequenceFlow id="d1" sourceRef="d-start" targetRef="fork"/>
                  <bpmn:sequenceFlow id="d2" sourceRef="fork" targetRef="check-limit"/>
                  <bpmn:sequenceFlow id="d3" sourceRef="fork" targetRef="manual-review"/>
                  <bpmn:sequenceFlow id="d4" sourceRef="check-limit" targetRef="reject"/>
                  <bpmn:sequenceFlow id="d5" sourceRef="manual-review" targetRef="reviewed"/>
                  %s
                </bpmn:subProcess>
                <bpmn:serviceTask id="post-ledger" horus:handler="post-ledger"/>
                <bpmn:endEvent id="end"/>
                <bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="disburse"/>
                <bpmn:sequenceFlow id="f2" sourceRef="disburse" targetRef="post-ledger"/>
                <bpmn:sequenceFlow id="f3" sourceRef="post-ledger" targetRef="end"/>
                """.formatted(eventSubProcess) + TAIL;
    }

    private static String errorHandler(String code) {
        return """
                <bpmn:subProcess id="limit-hit" triggeredByEvent="true">
                  <bpmn:startEvent id="limit-start"><bpmn:errorEventDefinition %s/></bpmn:startEvent>
                  %s
                </bpmn:subProcess>""".formatted(code == null ? "" : "errorRef=\"" + code + "\"",
                handler("e", "limit-start", "notify-limit"));
    }

    /** A KYC sub-process that raises an escalation and goes on; the handler stands in the process around it. */
    private static String kyc(boolean interrupting) {
        return HEAD + """
                <bpmn:startEvent id="start"/>
                <bpmn:subProcess id="kyc">
                  <bpmn:startEvent id="k-start"/>
                  <bpmn:intermediateThrowEvent id="raise"><bpmn:escalationEventDefinition escalationRef="Esc_aml"/>
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
                <bpmn:subProcess id="aml" triggeredByEvent="true">
                  <bpmn:startEvent id="aml-start" isInterrupting="%s">
                    <bpmn:escalationEventDefinition escalationRef="Esc_aml"/>
                  </bpmn:startEvent>
                  %s
                </bpmn:subProcess>
                """.formatted(interrupting, handler("a", "aml-start", "aml-review")) + TAIL;
    }

    private final SubsetBpmnReader reader = new SubsetBpmnReader(Limits.DEFAULTS);

    private Process read(String xml) {
        return reader.read(xml.getBytes(StandardCharsets.UTF_8));
    }

    private DefaultExecutionKernel kernel(String xml) {
        CompiledProcess compiled = new ProcessCompiler(BehaviorCatalog.registry(), 500).compile("D1", read(xml));
        return new DefaultExecutionKernel(compiled, BehaviorCatalog.registry(), new V1ExpressionEvaluator(100));
    }

    private static Transition start(DefaultExecutionKernel kernel, Map<String, Object> variables) {
        return kernel.apply(InstanceState.created(INSTANCE, "bank-a", "D1", "LA-2026-0042"),
                new Command.Start(variables, "officer.hoa"), CLOCK);
    }

    private static Token flow(InstanceState state, String nodeId) {
        return state.tokens().stream().filter(t -> t.nodeId().equals(nodeId) && t.kind() == TokenKind.FLOW)
                .reduce((first, second) -> second).orElseThrow();
    }

    private static boolean live(InstanceState state, String nodeId) {
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
            String nodeId) {
        Effect.InvokeService call = call(scheduled, nodeId);
        return kernel.apply(from, new Command.AsyncResult(call.tokenId(), call.attemptId(),
                new StepResult.Completed(Map.of())), CLOCK);
    }

    private static Transition completeTask(DefaultExecutionKernel kernel, InstanceState from, String nodeId,
            Map<String, Object> result) {
        Token task = flow(from, nodeId);
        return kernel.apply(from, new Command.CompleteTask(task.activityInstanceId(), task.id(), result, "officer.lan"),
                CLOCK);
    }

    private static HistoryEntry started(Transition t) {
        return t.history().stream().filter(h -> h.type().equals("EVENT_SUBPROCESS_STARTED")).findFirst().orElseThrow();
    }

    // ---- PRE-320: interrupting ---------------------------------------------------------------------------------

    @Test
    void PRE_320_an_interrupting_message_event_sub_process_cancels_the_other_work_and_the_scope_ends_with_it() {
        // Given a loan application waiting on the underwriter, with a withdrawal that interrupts
        DefaultExecutionKernel kernel = kernel(assessment(message(true)));
        Transition started = start(kernel, Map.of("applicationId", "LA-2026-0042"));
        Token task = flow(started.state(), "assess");
        Effect.CreateSubscription sub = effects(started, Effect.CreateSubscription.class).get(0);
        assertThat(sub.kind()).isEqualTo("MESSAGE");
        assertThat(sub.name()).isEqualTo("applicant-withdrew");
        assertThat(sub.correlationKey()).isEqualTo("LA-2026-0042");
        assertThat(flow(started.state(), "withdrawn").status()).isEqualTo(TokenStatus.WAITING_EVENT);

        // When the applicant withdraws
        Transition withdrawn = kernel.apply(started.state(), new Command.CorrelateMessage(sub.subscriptionId(),
                "applicant-withdrew", Map.of()), CLOCK);

        // Then the open task is cancelled with its work item, the handler runs, and the timeline says why
        assertThat(flow(withdrawn.state(), "assess").status()).isEqualTo(TokenStatus.CANCELLED);
        assertThat(effects(withdrawn, Effect.CancelUserTask.class)).extracting(Effect.CancelUserTask::taskId)
                .containsExactly(task.activityInstanceId());
        assertThat(call(withdrawn, "w-do").target()).isEqualTo("close-application-file");
        assertThat(started(withdrawn).details()).containsEntry("trigger", "MESSAGE")
                .containsEntry("eventSubProcessId", "withdrawal").containsEntry("interrupting", true);
        assertThatThrownBy(() -> kernel.apply(withdrawn.state(), new Command.CompleteTask(task.activityInstanceId(),
                task.id(), Map.of("assessment", "approve"), "officer.lan"), CLOCK)).hasMessageContaining("CANCELLED");

        // And the instance ends when the handler ends, not before
        assertThat(withdrawn.state().status()).isEqualTo(InstanceStatus.RUNNING);
        Transition done = answer(kernel, withdrawn, withdrawn.state(), "w-do");
        assertThat(done.state().status()).isEqualTo(InstanceStatus.COMPLETED);
    }

    // ---- PRE-321: non-interrupting -----------------------------------------------------------------------------

    @Test
    void PRE_321_a_non_interrupting_timer_event_sub_process_runs_once_beside_the_main_flow() {
        // Given an application with a reminder after 24 hours that does not interrupt
        DefaultExecutionKernel kernel = kernel(assessment(timer(false)));
        Transition started = start(kernel, Map.of());
        Effect.ScheduleJob job = effects(started, Effect.ScheduleJob.class).get(0);
        assertThat(job.nodeId()).isEqualTo("after-a-day");
        assertThat(job.dueAt()).isEqualTo(Instant.parse("2026-11-24T03:00:00Z"));

        // When the day passes
        Transition fired = kernel.apply(started.state(), new Command.JobDue(job.jobId(), job.tokenId(), job.jobKind()),
                CLOCK);

        // Then the task stays open, the reminder runs once and no second timer is scheduled
        assertThat(flow(fired.state(), "assess").status()).isEqualTo(TokenStatus.WAITING_USER);
        assertThat(call(fired, "r-do").target()).isEqualTo("send-underwriter-reminder");
        assertThat(effects(fired, Effect.ScheduleJob.class)).isEmpty();
        assertThat(effects(fired, Effect.CancelUserTask.class)).isEmpty();
        assertThat(started(fired).details()).containsEntry("trigger", "TIMER").containsEntry("interrupting", false);

        // When the underwriter finishes first, the scope still waits for the reminder to end
        Transition assessed = completeTask(kernel, fired.state(), "assess", Map.of("assessment", "approve"));
        assertThat(assessed.state().status()).isEqualTo(InstanceStatus.RUNNING);
        Transition done = answer(kernel, fired, assessed.state(), "r-do");
        assertThat(done.state().status()).isEqualTo(InstanceStatus.COMPLETED);
    }

    @Test
    void PRE_321_a_non_interrupting_signal_event_sub_process_starts_again_for_every_broadcast() {
        // Given a quote waiting for the customer, with a repricing that listens for a rate-card change
        DefaultExecutionKernel kernel = kernel(assessment(signal(false)));
        Transition started = start(kernel, Map.of());
        Effect.CreateSubscription first = effects(started, Effect.CreateSubscription.class).get(0);
        assertThat(first.kind()).isEqualTo("SIGNAL");
        assertThat(first.name()).isEqualTo("rate-card-updated");

        // When the rate card changes once
        Transition one = kernel.apply(started.state(), new Command.DeliverSignal(first.tokenId(),
                first.subscriptionId(), "rate-card-updated"), CLOCK);

        // Then the repricing runs, the old subscription is gone and a new one waits for the next change
        assertThat(call(one, "p-do").target()).isEqualTo("reprice-loan-offer");
        assertThat(effects(one, Effect.DeleteSubscription.class)).extracting(Effect.DeleteSubscription::subscriptionId)
                .containsExactly(first.subscriptionId());
        Effect.CreateSubscription second = effects(one, Effect.CreateSubscription.class).get(0);
        assertThat(second.subscriptionId()).isNotEqualTo(first.subscriptionId());
        assertThat(flow(one.state(), "assess").status()).isEqualTo(TokenStatus.WAITING_USER);

        // When it changes again, the repricing runs a second time; a repeat of the first delivery is refused
        Transition two = kernel.apply(one.state(), new Command.DeliverSignal(second.tokenId(), second.subscriptionId(),
                "rate-card-updated"), CLOCK);
        assertThat(call(two, "p-do")).isNotNull();
        assertThatThrownBy(() -> kernel.apply(two.state(), new Command.DeliverSignal(first.tokenId(),
                first.subscriptionId(), "rate-card-updated"), CLOCK)).hasMessageContaining("COMPLETED");
    }

    // ---- PRE-322: every trigger starts ------------------------------------------------------------------------

    @Test
    void PRE_322_a_conditional_event_sub_process_starts_when_the_variables_make_its_condition_true() {
        // Given an application whose risk handler waits for assessment.score >= 80
        DefaultExecutionKernel kernel = kernel(assessment(conditional(false)));
        Transition started = start(kernel, Map.of());
        assertThat(flow(started.state(), "risky").status()).isEqualTo(TokenStatus.WAITING_EVENT);
        assertThat(effects(started, Effect.InvokeService.class)).isEmpty();

        // When the underwriter completes the task with a score of 85
        Transition assessed = completeTask(kernel, started.state(), "assess", Map.of("score", 85));

        // Then the handler starts in the transaction that changed the variables, and the main flow has ended
        assertThat(call(assessed, "c-do").target()).isEqualTo("notify-compliance");
        assertThat(started(assessed).details()).containsEntry("trigger", "CONDITIONAL");
        assertThat(flow(assessed.state(), "end").status()).isEqualTo(TokenStatus.COMPLETED);
        assertThat(assessed.state().status()).isEqualTo(InstanceStatus.RUNNING);
        assertThat(answer(kernel, assessed, assessed.state(), "c-do").state().status())
                .isEqualTo(InstanceStatus.COMPLETED);
    }

    @Test
    void PRE_322_a_conditional_event_sub_process_that_is_true_when_its_scope_opens_starts_at_once() {
        // Given an interrupting handler for a score of 80 or more, and an application that arrives with 90
        DefaultExecutionKernel kernel = kernel(assessment(conditional(true)));

        // When the instance starts with assessment.score 90
        Transition started = start(kernel, Map.of("assessment", Map.of("score", 90)));

        // Then the handler runs and the underwriter's task was cancelled
        assertThat(call(started, "c-do")).isNotNull();
        assertThat(flow(started.state(), "assess").status()).isEqualTo(TokenStatus.CANCELLED);
    }

    @Test
    void PRE_322_a_non_interrupting_conditional_starts_again_only_after_its_condition_has_been_false() {
        // Given four reviews in a row that each write the score, and a non-interrupting handler for a score of 80 or more
        StringBuilder tasks = new StringBuilder();
        for (int i = 1; i <= 4; i++) {
            tasks.append("<bpmn:userTask id=\"t").append(i).append("\" horus:assignee=\"role:UNDERWRITER\""
                    + " horus:resultVariable=\"assessment\"/>");
        }
        String flows = "<bpmn:sequenceFlow id=\"a0\" sourceRef=\"start\" targetRef=\"t1\"/>"
                + "<bpmn:sequenceFlow id=\"a1\" sourceRef=\"t1\" targetRef=\"t2\"/>"
                + "<bpmn:sequenceFlow id=\"a2\" sourceRef=\"t2\" targetRef=\"t3\"/>"
                + "<bpmn:sequenceFlow id=\"a3\" sourceRef=\"t3\" targetRef=\"t4\"/>"
                + "<bpmn:sequenceFlow id=\"a4\" sourceRef=\"t4\" targetRef=\"end\"/>";
        DefaultExecutionKernel kernel = kernel(HEAD + "<bpmn:startEvent id=\"start\"/><bpmn:endEvent id=\"end\"/>"
                + tasks + flows + conditional(false) + TAIL);
        Transition now = start(kernel, Map.of());

        // When the scores are 85, 90, 60 and 95
        int started = 0;
        for (int i = 1; i <= 4; i++) {
            int score = new int[] {85, 90, 60, 95}[i - 1];
            now = completeTask(kernel, now.state(), "t" + i, Map.of("score", score));
            started += effects(now, Effect.InvokeService.class).size();
        }

        // Then the handler started for 85 and for 95 only: 90 still held, and 60 reset it
        assertThat(started).isEqualTo(2);
    }

    // ---- PRE-323: error and escalation from inside --------------------------------------------------------------

    @Test
    void PRE_323_an_error_raised_inside_a_sub_process_is_caught_by_its_error_event_sub_process() {
        // Given a disbursement whose limit check ends with LIMIT_EXCEEDED while a manual review is open
        DefaultExecutionKernel kernel = kernel(disbursement(errorHandler("Err_limit")));
        Transition started = start(kernel, Map.of());
        Token review = flow(started.state(), "manual-review");
        assertThat(review.status()).isEqualTo(TokenStatus.WAITING_USER);

        // When the limit check completes and reaches the error end event
        Transition raised = answer(kernel, started, started.state(), "check-limit");

        // Then the review is cancelled, the handler runs inside the sub-process, and the error is on the timeline
        assertThat(flow(raised.state(), "manual-review").status()).isEqualTo(TokenStatus.CANCELLED);
        assertThat(effects(raised, Effect.CancelUserTask.class)).extracting(Effect.CancelUserTask::taskId)
                .containsExactly(review.activityInstanceId());
        assertThat(call(raised, "e-do").target()).isEqualTo("notify-limit");
        assertThat(raised.history()).anySatisfy(h -> {
            assertThat(h.type()).isEqualTo("ERROR_THROWN");
            assertThat(h.details()).containsEntry("code", "LIMIT_EXCEEDED").containsEntry("caughtBy", "limit-hit");
        });
        assertThat(effects(raised, Effect.RaiseIncident.class)).isEmpty();

        // And when the handler ends, the sub-process is complete and the parent goes on to the ledger
        Transition handled = answer(kernel, raised, raised.state(), "e-do");
        assertThat(call(handled, "post-ledger")).isNotNull();
    }

    @Test
    void PRE_323_a_business_error_of_a_step_is_caught_by_an_error_event_sub_process_of_the_process() {
        // Given a debit whose process has a catch-all error handler, and no boundary on the step
        DefaultExecutionKernel kernel = kernel(HEAD + """
                <bpmn:startEvent id="start"/>
                <bpmn:serviceTask id="debit" horus:handler="post-debit"/>
                <bpmn:endEvent id="end"/>
                <bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="debit"/>
                <bpmn:sequenceFlow id="f2" sourceRef="debit" targetRef="end"/>
                """ + errorHandler(null) + TAIL);
        Transition started = start(kernel, Map.of());
        Effect.InvokeService debit = call(started, "debit");

        // When the debit answers INSUFFICIENT_FUNDS
        Transition declined = kernel.apply(started.state(), new Command.AsyncResult(debit.tokenId(), debit.attemptId(),
                new StepResult.BusinessError("INSUFFICIENT_FUNDS", "balance 200,000 VND, debit 500,000 VND")), CLOCK);

        // Then no incident opens: the handler runs and the step is cancelled
        assertThat(effects(declined, Effect.RaiseIncident.class)).isEmpty();
        assertThat(call(declined, "e-do").target()).isEqualTo("notify-limit");
        assertThat(flow(declined.state(), "debit").status()).isEqualTo(TokenStatus.CANCELLED);
    }

    @Test
    void PRE_323_a_boundary_on_the_step_wins_over_an_event_sub_process_of_the_scope_and_a_specific_code_over_a_catch_all() {
        // Given a debit with a boundary for INSUFFICIENT_FUNDS and a catch-all error handler in the process
        DefaultExecutionKernel kernel = kernel(HEAD + """
                <bpmn:startEvent id="start"/>
                <bpmn:serviceTask id="debit" horus:handler="post-debit"/>
                <bpmn:boundaryEvent id="no-funds" attachedToRef="debit"><bpmn:errorEventDefinition errorRef="Err_funds"/>
                </bpmn:boundaryEvent>
                <bpmn:endEvent id="paid"/>
                <bpmn:endEvent id="declined"/>
                <bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="debit"/>
                <bpmn:sequenceFlow id="f2" sourceRef="debit" targetRef="paid"/>
                <bpmn:sequenceFlow id="f3" sourceRef="no-funds" targetRef="declined"/>
                """ + errorHandler(null) + TAIL);
        Transition started = start(kernel, Map.of());
        Effect.InvokeService debit = call(started, "debit");

        // When the debit answers INSUFFICIENT_FUNDS, the boundary takes it and the handler does not start
        Transition funds = kernel.apply(started.state(), new Command.AsyncResult(debit.tokenId(), debit.attemptId(),
                new StepResult.BusinessError("INSUFFICIENT_FUNDS", "balance 200,000 VND")), CLOCK);
        assertThat(funds.state().status()).isEqualTo(InstanceStatus.COMPLETED);
        assertThat(effects(funds, Effect.InvokeService.class)).isEmpty();

        // When it answers another code, the catch-all handler of the process takes it
        Transition frozen = kernel.apply(started.state(), new Command.AsyncResult(debit.tokenId(), debit.attemptId(),
                new StepResult.BusinessError("ACCOUNT_FROZEN", "blocked by court order")), CLOCK);
        assertThat(call(frozen, "e-do")).isNotNull();
    }

    @Test
    void PRE_323_an_escalation_from_a_sub_process_starts_the_event_sub_process_of_the_process_around_it() {
        // Given a KYC sub-process that raises AML_HIT and a non-interrupting handler in the process
        DefaultExecutionKernel kernel = kernel(kyc(false));

        // When the instance starts
        Transition started = start(kernel, Map.of());

        // Then the review starts beside the KYC, which goes on to its verification
        assertThat(call(started, "a-do").target()).isEqualTo("aml-review");
        assertThat(call(started, "verify")).isNotNull();
        assertThat(started(started).details()).containsEntry("trigger", "ESCALATION").containsEntry("interrupting",
                false);

        // And the process ends only when both have ended
        Transition verified = answer(kernel, started, started.state(), "verify");
        assertThat(verified.state().status()).isEqualTo(InstanceStatus.RUNNING);
        assertThat(answer(kernel, started, verified.state(), "a-do").state().status())
                .isEqualTo(InstanceStatus.COMPLETED);
    }

    @Test
    void PRE_323_an_interrupting_escalation_handler_cancels_the_sub_process_that_raised_it() {
        // Given the same KYC, with a handler that interrupts
        DefaultExecutionKernel kernel = kernel(kyc(true));

        // When the instance starts
        Transition started = start(kernel, Map.of());

        // Then the verification never runs: the thrower is cancelled and only the review is left
        assertThat(effects(started, Effect.InvokeService.class)).extracting(Effect.InvokeService::nodeId)
                .containsExactly("a-do");
        assertThat(flow(started.state(), "raise").status()).isEqualTo(TokenStatus.CANCELLED);
        assertThat(answer(kernel, started, started.state(), "a-do").state().status())
                .isEqualTo(InstanceStatus.COMPLETED);
    }

    // ---- PRE-324: the scope ends -----------------------------------------------------------------------------------

    @Test
    void PRE_324_a_scope_that_ends_removes_the_timer_and_the_subscriptions_of_its_waiting_event_sub_processes() {
        // Given an application with a timer, a signal and a message handler, all waiting
        DefaultExecutionKernel kernel = kernel(assessment(timer(false) + signal(false) + message(true)));
        Transition started = start(kernel, Map.of("applicationId", "LA-2026-0042"));
        Effect.ScheduleJob job = effects(started, Effect.ScheduleJob.class).get(0);
        Effect.CreateSubscription signal = effects(started, Effect.CreateSubscription.class).stream()
                .filter(s -> s.kind().equals("SIGNAL")).findFirst().orElseThrow();

        // When the underwriter finishes and the main flow ends
        Transition done = completeTask(kernel, started.state(), "assess", Map.of("assessment", "approve"));

        // Then the instance completes, the timer is cancelled, the signal subscription is deleted, nothing waits
        assertThat(done.state().status()).isEqualTo(InstanceStatus.COMPLETED);
        assertThat(effects(done, Effect.CancelJob.class)).extracting(Effect.CancelJob::jobId)
                .containsExactly(job.jobId());
        assertThat(effects(done, Effect.DeleteSubscription.class)).extracting(Effect.DeleteSubscription::subscriptionId)
                .contains(signal.subscriptionId());
        assertThat(done.state().tokens()).noneMatch(t -> t.status().live());
    }

    @Test
    void PRE_324_an_event_sub_process_of_a_sub_process_is_armed_when_it_opens_and_dropped_when_it_ends() {
        // Given a KYC sub-process with its own interrupting message handler and a screening step
        DefaultExecutionKernel kernel = kernel(HEAD + """
                <bpmn:startEvent id="start"/>
                <bpmn:subProcess id="screening">
                  <bpmn:startEvent id="s-start"/>
                  <bpmn:serviceTask id="screen" horus:handler="sanctions-screen"/>
                  <bpmn:endEvent id="s-end"/>
                  <bpmn:sequenceFlow id="s1" sourceRef="s-start" targetRef="screen"/>
                  <bpmn:sequenceFlow id="s2" sourceRef="screen" targetRef="s-end"/>
                """ + message(true).replace("withdrawal", "cancel-screening") + """
                </bpmn:subProcess>
                <bpmn:endEvent id="end"/>
                <bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="screening"/>
                <bpmn:sequenceFlow id="f2" sourceRef="screening" targetRef="end"/>
                """ + TAIL);

        // When the instance starts, the handler waits only because the sub-process is open
        Transition started = start(kernel, Map.of("applicationId", "LA-2026-0042"));
        assertThat(live(started.state(), "withdrawn")).isTrue();

        // And when the screening ends, the handler's wait is dropped with the sub-process
        Transition done = answer(kernel, started, started.state(), "screen");
        assertThat(done.state().status()).isEqualTo(InstanceStatus.COMPLETED);
        assertThat(live(done.state(), "withdrawn")).isFalse();
    }

    // ---- PRE-326: compensation ---------------------------------------------------------------------------------

    /** A disbursement sub-process whose undo is {@code undo}; {@code undo} is empty for a sub-process with none. */
    private static String booking(String undo) {
        return HEAD + """
                <bpmn:startEvent id="start"/>
                <bpmn:subProcess id="disburse">
                  <bpmn:startEvent id="d-start"/>
                  <bpmn:serviceTask id="reserve-funds" horus:handler="reserve-funds" horus:compensate="release-funds"/>
                  <bpmn:endEvent id="d-end"/>
                  <bpmn:sequenceFlow id="d1" sourceRef="d-start" targetRef="reserve-funds"/>
                  <bpmn:sequenceFlow id="d2" sourceRef="reserve-funds" targetRef="d-end"/>
                  %s
                </bpmn:subProcess>
                <bpmn:endEvent id="end"/>
                <bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="disburse"/>
                <bpmn:sequenceFlow id="f2" sourceRef="disburse" targetRef="end"/>
                """.formatted(undo) + TAIL;
    }

    private static final String UNDO_ESP = """
            <bpmn:subProcess id="undo-disbursement" triggeredByEvent="true">
              <bpmn:startEvent id="undo-start"><bpmn:compensateEventDefinition/></bpmn:startEvent>
              <bpmn:serviceTask id="reverse-disbursement" horus:handler="reverse-disbursement">
                <bpmn:extensionElements><horus:input name="account" value="${instance.account}"/></bpmn:extensionElements>
              </bpmn:serviceTask>
              <bpmn:endEvent id="undo-end"/>
              <bpmn:sequenceFlow id="u1" sourceRef="undo-start" targetRef="reverse-disbursement"/>
              <bpmn:sequenceFlow id="u2" sourceRef="reverse-disbursement" targetRef="undo-end"/>
            </bpmn:subProcess>""";

    @Test
    void PRE_326_a_compensation_event_sub_process_pushes_the_undo_of_its_sub_process_when_it_completes() {
        // Given a disbursement sub-process with a compensation event sub-process and a step with its own undo
        DefaultExecutionKernel kernel = kernel(booking(UNDO_ESP));
        Transition started = start(kernel, Map.of("account", "ACC-0001"));
        assertThat(effects(started, Effect.PushCompensation.class)).isEmpty();
        assertThat(effects(started, Effect.CreateSubscription.class)).isEmpty();

        // When the step completes and the sub-process ends
        Transition done = answer(kernel, started, started.state(), "reserve-funds");

        // Then two entries are pushed, the step's first and the sub-process's after it, so a walk undoes the
        // sub-process first, with the inputs its undo task declares, in the scope the sub-process stands in
        assertThat(done.state().status()).isEqualTo(InstanceStatus.COMPLETED);
        List<Effect.PushCompensation> pushes = effects(done, Effect.PushCompensation.class);
        assertThat(pushes).extracting(Effect.PushCompensation::nodeId, Effect.PushCompensation::handler)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("reserve-funds", "release-funds"),
                        org.assertj.core.groups.Tuple.tuple("disburse", "reverse-disbursement"));
        Token root = done.state().tokens().stream().filter(t -> t.scopeTokenId() == null).findFirst().orElseThrow();
        assertThat(pushes.get(1).scopeTokenId()).isEqualTo(root.id());
        assertThat(pushes.get(1).inputSnapshot()).containsEntry("account", "ACC-0001");
    }

    private static final String UNDO_TWO_TASKS = """
            <bpmn:subProcess id="undo-disbursement" triggeredByEvent="true">
              <bpmn:startEvent id="undo-start"><bpmn:compensateEventDefinition/></bpmn:startEvent>
              <bpmn:serviceTask id="reverse-disbursement" horus:handler="reverse-disbursement">
                <bpmn:extensionElements><horus:input name="account" value="${instance.account}"/></bpmn:extensionElements>
              </bpmn:serviceTask>
              <bpmn:serviceTask id="notify-treasury" horus:handler="notify-treasury">
                <bpmn:extensionElements><horus:input name="amount" value="${instance.amount}"/></bpmn:extensionElements>
              </bpmn:serviceTask>
              <bpmn:endEvent id="undo-end"/>
              <bpmn:sequenceFlow id="u1" sourceRef="undo-start" targetRef="reverse-disbursement"/>
              <bpmn:sequenceFlow id="u2" sourceRef="reverse-disbursement" targetRef="notify-treasury"/>
              <bpmn:sequenceFlow id="u3" sourceRef="notify-treasury" targetRef="undo-end"/>
            </bpmn:subProcess>""";

    @Test
    void PRE_353_a_compensation_event_sub_process_with_several_tasks_undoes_them_in_flow_order() {
        // Given an undo of two service tasks: reverse the disbursement of 25,000,000 VND, then tell the treasury
        DefaultExecutionKernel kernel = kernel(booking(UNDO_TWO_TASKS));
        Transition started = start(kernel, Map.of("account", "0011-223344", "amount", 25_000_000));

        // When the sub-process completes
        Transition done = answer(kernel, started, started.state(), "reserve-funds");

        // Then the stack takes the step, then the last task, then the first, so the walk (newest first) runs
        // reverse-disbursement, then notify-treasury, then the step's own undo; each reads its own inputs
        List<Effect.PushCompensation> pushes = effects(done, Effect.PushCompensation.class);
        assertThat(pushes).extracting(Effect.PushCompensation::handler)
                .containsExactly("release-funds", "notify-treasury", "reverse-disbursement");
        assertThat(pushes.get(1).inputSnapshot()).containsEntry("amount", 25_000_000).doesNotContainKey("account");
        assertThat(pushes.get(2).inputSnapshot()).containsEntry("account", "0011-223344")
                .doesNotContainKey("amount");
        assertThat(pushes).extracting(Effect.PushCompensation::nodeId).containsExactly("reserve-funds", "disburse",
                "disburse");
        assertThat(pushes.get(1).undoNodeId()).isEqualTo("notify-treasury");
    }

    @Test
    void PRE_353_an_undo_that_branches_or_holds_anything_but_service_tasks_is_refused() {
        // A user task in the line, a second flow out of the first task, and a task with no flow into the line
        refused(booking(UNDO_TWO_TASKS.replaceAll("(?s)<bpmn:serviceTask id=\"notify-treasury\".*?</bpmn:serviceTask>",
                "<bpmn:task id=\"notify-treasury\"/>")), "COMPENSATION_EVENT_SUBPROCESS_SHAPE", "undo-disbursement");
        refused(booking(UNDO_TWO_TASKS.replace("<bpmn:sequenceFlow id=\"u3\"",
                "<bpmn:sequenceFlow id=\"u4\" sourceRef=\"reverse-disbursement\" targetRef=\"undo-end\"/>"
                        + "<bpmn:sequenceFlow id=\"u3\"")),
                "COMPENSATION_EVENT_SUBPROCESS_SHAPE", "undo-disbursement");
    }

    @Test
    void PRE_326_a_compensation_boundary_event_on_a_sub_process_or_a_call_activity_names_its_undo() {
        // Given a sub-process with a compensation boundary event and its compensation activity
        String boundary = """
                <bpmn:boundaryEvent id="undo-boundary" attachedToRef="disburse">
                  <bpmn:compensateEventDefinition/>
                </bpmn:boundaryEvent>
                <bpmn:serviceTask id="reverse-disbursement" isForCompensation="true" horus:handler="reverse-disbursement"/>
                <bpmn:association id="undo-link" associationDirection="One" sourceRef="undo-boundary"
                                  targetRef="reverse-disbursement"/>""";
        String xml = booking("").replace("</bpmn:process>", boundary + "</bpmn:process>");
        DefaultExecutionKernel kernel = kernel(xml);

        // When the step completes and the sub-process ends, Then its undo is pushed after the step's
        Transition started = start(kernel, Map.of());
        assertThat(effects(answer(kernel, started, started.state(), "reserve-funds"), Effect.PushCompensation.class))
                .extracting(Effect.PushCompensation::handler).containsExactly("release-funds", "reverse-disbursement");

        // And a call activity with the same boundary pushes its undo when the child completes
        String call = HEAD + """
                <bpmn:startEvent id="start"/>
                <bpmn:callActivity id="check-credit" calledElement="credit-check"/>
                <bpmn:boundaryEvent id="hold-boundary" attachedToRef="check-credit"><bpmn:compensateEventDefinition/>
                </bpmn:boundaryEvent>
                <bpmn:serviceTask id="cancel-hold" isForCompensation="true" horus:handler="cancel-credit-hold"/>
                <bpmn:association id="hold-link" associationDirection="One" sourceRef="hold-boundary"
                                  targetRef="cancel-hold"/>
                <bpmn:endEvent id="end"/>
                <bpmn:sequenceFlow id="c1" sourceRef="start" targetRef="check-credit"/>
                <bpmn:sequenceFlow id="c2" sourceRef="check-credit" targetRef="end"/>
                """ + TAIL;
        DefaultExecutionKernel callKernel = kernel(call);
        Transition calling = start(callKernel, Map.of());
        Effect.StartChildInstance child = effects(calling, Effect.StartChildInstance.class).get(0);
        Transition ended = callKernel.apply(calling.state(), new Command.ChildEnded(child.tokenId(), child.attemptId(),
                "01JD0000000000000000000900", Command.ChildOutcome.COMPLETED, Map.of(), null), CLOCK);
        assertThat(effects(ended, Effect.PushCompensation.class)).singleElement().satisfies(push -> {
            assertThat(push.nodeId()).isEqualTo("check-credit");
            assertThat(push.handler()).isEqualTo("cancel-credit-hold");
        });
    }

    @Test
    void PRE_326_a_compensation_event_sub_process_has_one_undo_task_in_a_sub_process_that_has_no_other_undo() {
        // One at the process level, one with two tasks, one with no handler, and one beside a compensation boundary
        refused(assessment(UNDO_ESP), "COMPENSATION_HANDLER_SCOPE", "undo-start");
        refused(booking(UNDO_ESP.replace("<bpmn:sequenceFlow id=\"u2\"", "<bpmn:task id=\"extra\"/>"
                + "<bpmn:sequenceFlow id=\"u2\"")), "COMPENSATION_EVENT_SUBPROCESS_SHAPE", "undo-disbursement");
        refused(booking(UNDO_ESP.replace(" horus:handler=\"reverse-disbursement\"", "")),
                "HANDLER_MISSING", "reverse-disbursement");
    }

    // ---- PRE-325: import, export and refusals --------------------------------------------------------------------

    private static byte[] example() throws IOException {
        try (InputStream in = EventSubProcessTest.class.getResourceAsStream("/bpmn/loan-application-event-subprocesses.bpmn")) {
            return in.readAllBytes();
        }
    }

    @Test
    void PRE_325_event_sub_processes_export_and_read_back_to_the_same_definition() throws IOException {
        // Given the worked example and every trigger of this test class
        List<byte[]> files = List.of(example(), assessment(timer(false) + signal(true)).getBytes(StandardCharsets.UTF_8),
                assessment(conditional(false) + message(true)).getBytes(StandardCharsets.UTF_8),
                disbursement(errorHandler("Err_limit")).getBytes(StandardCharsets.UTF_8),
                kyc(false).getBytes(StandardCharsets.UTF_8));
        for (byte[] file : files) {
            Process original = reader.read(file);

            // When it is exported and imported again, Then the canonical form, which the version hash is made of, is equal
            Process again = reader.read(new SubsetBpmnWriter().write(original));
            assertThat(CanonicalProcessWriter.canonicalText(again)).isEqualTo(CanonicalProcessWriter.canonicalText(original));
            new ProcessCompiler(BehaviorCatalog.registry(), 500).compile("D1", again);
        }
        assertThat(CanonicalProcessWriter.canonicalText(reader.read(example()))).contains("\"triggeredByEvent\":true")
                .contains("\"trigger\":\"timer\"").contains("\"interrupting\":false");
    }

    private void refused(String xml, String code, String elementId) {
        assertThatThrownBy(() -> kernel(xml)).isInstanceOfSatisfying(ValidationFailedException.class, e ->
                assertThat(e.problems()).extracting(DefinitionProblem::code, DefinitionProblem::elementId)
                        .contains(org.assertj.core.groups.Tuple.tuple(code, elementId)));
    }

    @Test
    void PRE_325_a_misplaced_or_unfinished_event_sub_process_is_refused_with_its_code() {
        // A timer start event on the process itself
        refused(HEAD + """
                <bpmn:startEvent id="start"><bpmn:timerEventDefinition><bpmn:timeDuration>PT1H</bpmn:timeDuration>
                </bpmn:timerEventDefinition></bpmn:startEvent>
                <bpmn:endEvent id="end"/>
                <bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="end"/>
                """ + TAIL, "START_TRIGGER_MISPLACED", "start");

        // An event sub-process with a flow in, one whose start event has no trigger, a message one with no key,
        // an error one that does not interrupt, and a boundary event on one
        String flows = """
                <bpmn:sequenceFlow id="x1" sourceRef="assess" targetRef="eh"/>
                <bpmn:subProcess id="eh" triggeredByEvent="true">
                  <bpmn:startEvent id="eh-start"/><bpmn:endEvent id="eh-end"/>
                  <bpmn:sequenceFlow id="eh1" sourceRef="eh-start" targetRef="eh-end"/>
                </bpmn:subProcess>""";
        refused(assessment(flows), "EVENT_SUBPROCESS_HAS_FLOW", "eh");
        refused(assessment(flows), "EVENT_SUBPROCESS_TRIGGER_REQUIRED", "eh-start");
        refused(assessment(message(true).replace(" horus:correlationKey=\"applicationId\"", "")),
                "CORRELATION_KEY_REQUIRED", "withdrawn");
        refused(assessment(errorHandler("Err_limit").replace("<bpmn:startEvent id=\"limit-start\">",
                "<bpmn:startEvent id=\"limit-start\" isInterrupting=\"false\">")),
                "EVENT_SUBPROCESS_ERROR_NOT_INTERRUPTING", "limit-start");
        refused(assessment(timer(true) + """
                <bpmn:boundaryEvent id="b" attachedToRef="reminder"><bpmn:errorEventDefinition/></bpmn:boundaryEvent>"""),
                "BOUNDARY_ATTACHED_INVALID", "b");
    }
}
