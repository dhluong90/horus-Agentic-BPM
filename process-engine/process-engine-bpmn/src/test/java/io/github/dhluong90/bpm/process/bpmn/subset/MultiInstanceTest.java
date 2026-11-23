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
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Standard loops and multi-instance steps on the reader, the compiler and the kernel, in memory (#496, ADR-0020):
 * a loan with four co-signers, owners screened one after another, a payment retried up to three times, and the
 * limits and failures around them.
 */
class MultiInstanceTest {

    private static final String INSTANCE = "01JD0000000000000000000496";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-23T03:00:00Z"), ZoneOffset.UTC);
    private static final String HEAD = """
            <?xml version="1.0" encoding="UTF-8"?>
            <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                              xmlns:horus="urn:horus-bpm:bpmn:extensions:v1" id="d" targetNamespace="urn:t"
                              expressionLanguage="urn:horus-bpm:expr:v1">
              <bpmn:process id="loan-cosigning" isExecutable="true">
                <bpmn:startEvent id="start"/>
            """;
    private static final String TAIL = """
                <bpmn:serviceTask id="book" horus:handler="book-loan"/>
                <bpmn:endEvent id="end"/>
                <bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="step"/>
                <bpmn:sequenceFlow id="f2" sourceRef="step" targetRef="book"/>
                <bpmn:sequenceFlow id="f3" sourceRef="book" targetRef="end"/>
              </bpmn:process></bpmn:definitions>
            """;

    private static String process(String step) {
        return HEAD + step + TAIL;
    }

    /** Four co-signers each approve in parallel; the decisions are collected in co-signer order. */
    private static final String PARALLEL_COSIGNERS = process("""
            <bpmn:userTask id="step" name="Co-signer approval" horus:assignee="role:CREDIT_OFFICER"
                           horus:resultVariable="verdict">
              <bpmn:multiInstanceLoopCharacteristics horus:collection="cosigners" horus:elementVariable="cosigner"
                                                     horus:outputCollection="verdicts"
                                                     horus:outputElement="verdict.decision"/>
            </bpmn:userTask>
            """);
    /** A loan that ends its co-signing once half of the co-signers have approved. */
    private static final String HALF_DONE = process("""
            <bpmn:userTask id="step" name="Co-signer approval" horus:assignee="role:CREDIT_OFFICER"
                           horus:resultVariable="verdict">
              <bpmn:multiInstanceLoopCharacteristics horus:collection="cosigners" horus:elementVariable="cosigner"
                                                     horus:outputCollection="verdicts"
                                                     horus:outputElement="verdict.decision">
                <bpmn:completionCondition>nrOfCompletedInstances * 2 &gt;= nrOfInstances</bpmn:completionCondition>
              </bpmn:multiInstanceLoopCharacteristics>
            </bpmn:userTask>
            """);
    /** Same, ending as soon as two have approved. */
    private static final String FIRST_TWO = process("""
            <bpmn:userTask id="step" name="Co-signer approval" horus:assignee="role:CREDIT_OFFICER"
                           horus:resultVariable="verdict">
              <bpmn:multiInstanceLoopCharacteristics horus:collection="cosigners" horus:elementVariable="cosigner"
                                                     horus:outputCollection="verdicts"
                                                     horus:outputElement="verdict.decision">
                <bpmn:completionCondition>nrOfCompletedInstances &gt;= 2</bpmn:completionCondition>
              </bpmn:multiInstanceLoopCharacteristics>
            </bpmn:userTask>
            """);
    /** Owners are screened one after another, each call getting its owner as input. */
    private static final String SEQUENTIAL_SCREENING = process("""
            <bpmn:serviceTask id="step" horus:handler="sanctions-screen" horus:resultVariable="screen">
              <bpmn:extensionElements><horus:input name="party" value="${instance.owner}"/></bpmn:extensionElements>
              <bpmn:multiInstanceLoopCharacteristics isSequential="true" horus:collection="owners"
                                                     horus:elementVariable="owner" horus:outputCollection="flags"
                                                     horus:outputElement="screen.flagged"/>
            </bpmn:serviceTask>
            """);
    private static final String PARALLEL_SCREENING = process("""
            <bpmn:serviceTask id="step" horus:handler="sanctions-screen" horus:resultVariable="screen">
              <bpmn:multiInstanceLoopCharacteristics horus:collection="owners" horus:elementVariable="owner"
                                                     horus:outputCollection="flags" horus:outputElement="screen.flagged"/>
            </bpmn:serviceTask>
            """);
    /** A card payment tried at most three times while it is not PAID, the condition tested after each try. */
    private static final String PAYMENT_RETRIES = process("""
            <bpmn:serviceTask id="step" horus:handler="charge-card" horus:resultVariable="payment">
              <bpmn:standardLoopCharacteristics loopMaximum="3">
                <bpmn:loopCondition>payment.status != 'PAID'</bpmn:loopCondition>
              </bpmn:standardLoopCharacteristics>
            </bpmn:serviceTask>
            """);
    private static final String TEST_BEFORE = process("""
            <bpmn:serviceTask id="step" horus:handler="charge-card" horus:resultVariable="payment">
              <bpmn:standardLoopCharacteristics testBefore="true" loopMaximum="3">
                <bpmn:loopCondition>amountDue &gt; 0</bpmn:loopCondition>
              </bpmn:standardLoopCharacteristics>
            </bpmn:serviceTask>
            """);
    /** Each branch of a split disbursement runs a sub-process: reserve funds, then post. */
    private static final String SUB_PROCESS_PER_BRANCH = process("""
            <bpmn:subProcess id="step">
              <bpmn:multiInstanceLoopCharacteristics horus:collection="branches" horus:elementVariable="branch"/>
              <bpmn:startEvent id="s-start"/>
              <bpmn:serviceTask id="reserve" horus:handler="reserve-funds"/>
              <bpmn:serviceTask id="post" horus:handler="post-debit"/>
              <bpmn:endEvent id="s-end"/>
              <bpmn:sequenceFlow id="s1" sourceRef="s-start" targetRef="reserve"/>
              <bpmn:sequenceFlow id="s2" sourceRef="reserve" targetRef="post"/>
              <bpmn:sequenceFlow id="s3" sourceRef="post" targetRef="s-end"/>
            </bpmn:subProcess>
            """);
    private static final String CALL_PER_ACCOUNT = process("""
            <bpmn:callActivity id="step" calledElement="account-check">
              <bpmn:extensionElements><horus:input name="account" value="${instance.account}"/>
                <horus:output name="status" from="status"/></bpmn:extensionElements>
              <bpmn:multiInstanceLoopCharacteristics horus:collection="accounts" horus:elementVariable="account"
                                                     horus:outputCollection="statuses" horus:outputElement="status">
                <bpmn:completionCondition>nrOfCompletedInstances &gt;= 1</bpmn:completionCondition>
              </bpmn:multiInstanceLoopCharacteristics>
            </bpmn:callActivity>
            """);

    private final SubsetBpmnReader reader = new SubsetBpmnReader(Limits.DEFAULTS);

    private Process read(String xml) {
        return reader.read(xml.getBytes(StandardCharsets.UTF_8));
    }

    private DefaultExecutionKernel kernel(String xml) {
        return kernel(xml, DefaultExecutionKernel.DEFAULT_MAX_INSTANCES);
    }

    private DefaultExecutionKernel kernel(String xml, int maxInstances) {
        CompiledProcess compiled = new ProcessCompiler(BehaviorCatalog.registry(), 500).compile("D1", read(xml));
        return new DefaultExecutionKernel(compiled, BehaviorCatalog.registry(), new V1ExpressionEvaluator(100),
                maxInstances);
    }

    private static Transition start(DefaultExecutionKernel kernel, Map<String, Object> variables) {
        return kernel.apply(InstanceState.created(INSTANCE, "bank-a", "D1", "LN-2026-0042"),
                new Command.Start(variables, "teller.hoa"), CLOCK);
    }

    private static List<Effect.CreateUserTask> tasks(Transition t) {
        return t.effects().stream().filter(Effect.CreateUserTask.class::isInstance).map(Effect.CreateUserTask.class::cast)
                .toList();
    }

    private static List<Effect.InvokeService> calls(Transition t, String nodeId) {
        return t.effects().stream().filter(Effect.InvokeService.class::isInstance).map(Effect.InvokeService.class::cast)
                .filter(e -> e.nodeId().equals(nodeId)).toList();
    }

    private static Transition done(DefaultExecutionKernel kernel, Transition from, Effect.CreateUserTask task,
            String decision) {
        return kernel.apply(from.state(), new Command.CompleteTask(task.taskId(), task.tokenId(),
                Map.of("decision", decision), "officer.lan"), CLOCK);
    }

    private static Transition answer(DefaultExecutionKernel kernel, Transition from, Effect.InvokeService call,
            Map<String, Object> result) {
        return kernel.apply(from.state(), new Command.AsyncResult(call.tokenId(), call.attemptId(),
                new StepResult.Completed(result)), CLOCK);
    }

    private static Token body(InstanceState state, String nodeId) {
        return state.tokens().stream().filter(t -> t.kind() == TokenKind.MI_BODY && t.nodeId().equals(nodeId))
                .findFirst().orElseThrow();
    }

    private static List<Token> instances(InstanceState state, String nodeId) {
        Token body = body(state, nodeId);
        return state.tokens().stream().filter(t -> body.id().equals(t.scopeTokenId())).toList();
    }

    private static boolean reached(InstanceState state, String nodeId) {
        return state.tokens().stream().anyMatch(t -> t.kind() == TokenKind.FLOW && t.nodeId().equals(nodeId));
    }

    private static final List<String> COSIGNERS = List.of("Lan", "Minh", "Huong", "Nam");

    // ---- PRE-290 parallel and sequential --------------------------------------------------------------------

    @Test
    void PRE_337_a_parallel_step_opens_every_instance_at_once_and_continues_once_after_the_last() {
        // Given a loan with four co-signers
        DefaultExecutionKernel kernel = kernel(PARALLEL_COSIGNERS);

        // When it starts
        Transition started = start(kernel, Map.of("cosigners", COSIGNERS));

        // Then four tasks are open together, one per co-signer in order, and the loan is not booked
        assertThat(tasks(started)).hasSize(4);
        assertThat(instances(started.state(), "step")).extracting(t -> t.locals().get("cosigner"))
                .containsExactlyElementsOf(COSIGNERS);
        assertThat(instances(started.state(), "step")).extracting(t -> t.locals().get("loopCounter"))
                .containsExactly(1, 2, 3, 4);
        assertThat(reached(started.state(), "book")).isFalse();

        // When they complete out of order: Huong, Lan, Nam, then Minh
        List<Effect.CreateUserTask> open = tasks(started);
        Transition t3 = done(kernel, started, open.get(2), "APPROVE");
        Transition t1 = done(kernel, t3, open.get(0), "APPROVE");
        Transition t4 = done(kernel, t1, open.get(3), "REJECT");
        assertThat(reached(t4.state(), "book")).isFalse();
        Transition t2 = done(kernel, t4, open.get(1), "APPROVE");

        // Then the loan is booked once, and the output list is in co-signer order, not completion order
        assertThat(t2.state().tokens().stream().filter(t -> t.kind() == TokenKind.FLOW && t.nodeId().equals("book")))
                .hasSize(1);
        assertThat(t2.state().variables().get("verdicts")).isEqualTo(List.of("APPROVE", "APPROVE", "APPROVE", "REJECT"));
        assertThat(body(t2.state(), "step").status()).isEqualTo(TokenStatus.COMPLETED);
    }

    @Test
    void PRE_337_a_sequential_step_runs_one_instance_at_a_time_with_its_own_element_as_input() {
        // Given three owners to screen, one after another
        DefaultExecutionKernel kernel = kernel(SEQUENTIAL_SCREENING);
        Transition started = start(kernel, Map.of("owners", List.of("Tran Van An", "Le Thi Binh", "Pham Quoc Cuong")));

        // Then only the first owner is being screened, and the call carries that owner
        assertThat(calls(started, "step")).singleElement().satisfies(c ->
                assertThat(c.inputs()).containsEntry("party", "Tran Van An"));
        assertThat(instances(started.state(), "step")).hasSize(1);

        // When the first is clear, the second starts and not the third
        Transition first = answer(kernel, started, calls(started, "step").get(0), Map.of("flagged", false));
        assertThat(calls(first, "step")).singleElement().satisfies(c ->
                assertThat(c.inputs()).containsEntry("party", "Le Thi Binh"));
        assertThat(instances(first.state(), "step")).hasSize(2);

        // When the second is flagged and the third is clear, the loan is booked with the flags in owner order
        Transition second = answer(kernel, first, calls(first, "step").get(0), Map.of("flagged", true));
        assertThat(calls(second, "step")).singleElement().satisfies(c ->
                assertThat(c.inputs()).containsEntry("party", "Pham Quoc Cuong"));
        Transition third = answer(kernel, second, calls(second, "step").get(0), Map.of("flagged", false));
        assertThat(reached(third.state(), "book")).isTrue();
        assertThat(third.state().variables().get("flags")).isEqualTo(List.of(false, true, false));
    }

    // ---- PRE-291 completion condition --------------------------------------------------------------------------

    @Test
    void PRE_338_a_completion_condition_that_holds_cancels_the_instances_still_running() {
        // Given four co-signers and a loan that needs only two approvals
        DefaultExecutionKernel kernel = kernel(FIRST_TWO);
        Transition started = start(kernel, Map.of("cosigners", COSIGNERS));
        List<Effect.CreateUserTask> open = tasks(started);

        // When the first one completes, nothing else changes
        Transition one = done(kernel, started, open.get(1), "APPROVE");
        assertThat(reached(one.state(), "book")).isFalse();

        // And when the second completes
        Transition two = done(kernel, one, open.get(3), "APPROVE");

        // Then the other two tasks are cancelled, the loan continues, and the output keeps a null where no one answered
        assertThat(two.effects()).filteredOn(Effect.CancelUserTask.class::isInstance).extracting(e ->
                ((Effect.CancelUserTask) e).taskId()).containsExactlyInAnyOrder(open.get(0).taskId(),
                open.get(2).taskId());
        assertThat(instances(two.state(), "step")).extracting(t -> t.status()).containsExactly(TokenStatus.CANCELLED,
                TokenStatus.COMPLETED, TokenStatus.CANCELLED, TokenStatus.COMPLETED);
        assertThat(reached(two.state(), "book")).isTrue();
        assertThat(two.state().variables().get("verdicts")).isEqualTo(java.util.Arrays.asList(null, "APPROVE", null,
                "APPROVE"));
        assertThat(two.history()).extracting(h -> h.type()).contains("MI_INSTANCE_CANCELLED", "MI_COMPLETED");
    }

    // ---- PRE-292 counters --------------------------------------------------------------------------------------

    @Test
    void PRE_339_the_counters_count_instances_active_and_completed_and_a_condition_can_read_them() {
        // Given four co-signers, and a condition on the share completed: nrOfCompletedInstances * 2 >= nrOfInstances
        DefaultExecutionKernel kernel = kernel(HALF_DONE);
        Transition started = start(kernel, Map.of("cosigners", COSIGNERS));

        // Then all four are active and none completed, and every instance has its own loopCounter
        Token body = body(started.state(), "step");
        assertThat(body.locals()).containsEntry("nrOfInstances", 4).containsEntry("nrOfActiveInstances", 4)
                .containsEntry("nrOfCompletedInstances", 0);

        // When one completes, three are active and one completed, and the condition is not yet true
        Transition one = done(kernel, started, tasks(started).get(0), "APPROVE");
        assertThat(body(one.state(), "step").locals()).containsEntry("nrOfActiveInstances", 3)
                .containsEntry("nrOfCompletedInstances", 1);
        assertThat(reached(one.state(), "book")).isFalse();
        Effect.CreateUserTask first = tasks(started).get(0);
        assertThat(first.context()).containsEntry("loopCounter", 1).doesNotContainKey("cosigner");

        // When a second completes, 2 * 2 >= 4 holds and the loop ends
        Transition two = done(kernel, one, tasks(started).get(1), "APPROVE");
        assertThat(reached(two.state(), "book")).isTrue();
        assertThat(two.history().stream().filter(h -> h.type().equals("MI_INSTANCE_COMPLETED")).findFirst()
                .orElseThrow().details()).containsEntry("nrOfCompletedInstances", 2).containsEntry("nrOfInstances", 4)
                .containsEntry("loopCounter", 2);
    }

    @Test
    void PRE_339_an_empty_collection_continues_at_once_with_an_empty_output() {
        // Given a loan with no co-signers
        DefaultExecutionKernel kernel = kernel(PARALLEL_COSIGNERS);

        // When it starts
        Transition started = start(kernel, Map.of("cosigners", List.of()));

        // Then no task opens, the output list is empty, and the loan is booked
        assertThat(tasks(started)).isEmpty();
        assertThat(started.state().variables().get("verdicts")).isEqualTo(List.of());
        assertThat(reached(started.state(), "book")).isTrue();
    }

    // ---- PRE-293 the cap ---------------------------------------------------------------------------------------

    @Test
    void PRE_340_a_collection_over_the_cap_opens_an_incident_and_creates_no_task() {
        // Given a cap of 3 repetitions per activity and a loan with four co-signers
        DefaultExecutionKernel kernel = kernel(PARALLEL_COSIGNERS, 3);

        // When it starts
        Transition started = start(kernel, Map.of("cosigners", COSIGNERS));

        // Then an incident opens on the step, no task or body exists, and the instance still runs
        assertThat(started.effects()).filteredOn(Effect.RaiseIncident.class::isInstance).singleElement()
                .isInstanceOfSatisfying(Effect.RaiseIncident.class, i -> {
                    assertThat(i.errorCode()).isEqualTo("MULTI_INSTANCE_LIMIT");
                    assertThat(i.nodeId()).isEqualTo("step");
                });
        assertThat(tasks(started)).isEmpty();
        assertThat(started.state().tokens()).noneMatch(t -> t.kind() == TokenKind.MI_BODY);
        assertThat(started.state().status()).isEqualTo(InstanceStatus.RUNNING);
    }

    @Test
    void PRE_340_a_collection_that_is_not_a_list_opens_an_incident_and_a_retry_after_the_fix_starts_the_step() {
        // Given a loan whose co-signers variable holds a text
        DefaultExecutionKernel kernel = kernel(PARALLEL_COSIGNERS);
        Transition started = start(kernel, Map.of("cosigners", "Lan, Minh"));
        Effect.RaiseIncident incident = started.effects().stream().filter(Effect.RaiseIncident.class::isInstance)
                .map(Effect.RaiseIncident.class::cast).findFirst().orElseThrow();
        assertThat(incident.errorCode()).isEqualTo("MULTI_INSTANCE_COLLECTION_INVALID");
        assertThat(tasks(started)).isEmpty();

        // When the token is retried with the variable still a text (an operator fixes it first in real use)
        Token parked = started.state().tokens().stream().filter(t -> t.id().equals(incident.tokenId())).findFirst()
                .orElseThrow();
        assertThat(parked.status()).isEqualTo(TokenStatus.WAITING_ASYNC);
        Transition retried = kernel.apply(started.state(), new Command.RetryStep(parked.id(),
                parked.activityInstanceId(), "ops.lan"), CLOCK);

        // Then it is refused again, still without a task
        assertThat(retried.effects()).filteredOn(Effect.RaiseIncident.class::isInstance).hasSize(1);
        assertThat(tasks(retried)).isEmpty();
    }

    // ---- PRE-294 a failure inside one instance ---------------------------------------------------------------

    @Test
    void PRE_341_a_failure_in_one_parallel_instance_holds_the_loop_and_the_others_still_finish() {
        // Given three owners screened in parallel
        DefaultExecutionKernel kernel = kernel(PARALLEL_SCREENING);
        Transition started = start(kernel, Map.of("owners", List.of("An", "Binh", "Cuong")));
        List<Effect.InvokeService> screens = calls(started, "step");
        assertThat(screens).hasSize(3);

        // When the second answers a business error and the other two complete
        Transition failed = kernel.apply(started.state(), new Command.AsyncResult(screens.get(1).tokenId(),
                screens.get(1).attemptId(), new StepResult.BusinessError("SCREENING_UNAVAILABLE", "timeout")), CLOCK);
        assertThat(failed.effects()).filteredOn(Effect.RaiseIncident.class::isInstance).singleElement()
                .isInstanceOfSatisfying(Effect.RaiseIncident.class, i -> assertThat(i.tokenId())
                        .isEqualTo(screens.get(1).tokenId()));
        Transition a = answer(kernel, failed, screens.get(0), Map.of("flagged", false));
        Transition c = answer(kernel, a, screens.get(2), Map.of("flagged", true));

        // Then the loop has not ended and the loan is not booked, with two completed and one active
        assertThat(reached(c.state(), "book")).isFalse();
        assertThat(body(c.state(), "step").locals()).containsEntry("nrOfCompletedInstances", 2)
                .containsEntry("nrOfActiveInstances", 1);

        // When the failed instance is retried and answers, the loan is booked with the flags in owner order
        Token parked = c.state().tokens().stream().filter(t -> t.id().equals(screens.get(1).tokenId())).findFirst()
                .orElseThrow();
        Transition retried = kernel.apply(c.state(), new Command.RetryStep(parked.id(), parked.activityInstanceId(),
                "ops.lan"), CLOCK);
        Transition last = answer(kernel, retried, calls(retried, "step").get(0), Map.of("flagged", false));
        assertThat(reached(last.state(), "book")).isTrue();
        assertThat(last.state().variables().get("flags")).isEqualTo(List.of(false, false, true));
    }

    @Test
    void PRE_341_a_failure_in_a_sequential_instance_keeps_the_next_one_from_starting() {
        // Given two owners screened one after another, the first of which fails
        DefaultExecutionKernel kernel = kernel(SEQUENTIAL_SCREENING);
        Transition started = start(kernel, Map.of("owners", List.of("An", "Binh")));
        Effect.InvokeService first = calls(started, "step").get(0);

        // When it answers a business error
        Transition failed = kernel.apply(started.state(), new Command.AsyncResult(first.tokenId(), first.attemptId(),
                new StepResult.BusinessError("SCREENING_UNAVAILABLE", "timeout")), CLOCK);

        // Then an incident opens and the second owner is not screened
        assertThat(failed.effects()).filteredOn(Effect.RaiseIncident.class::isInstance).hasSize(1);
        assertThat(calls(failed, "step")).isEmpty();
        assertThat(instances(failed.state(), "step")).hasSize(1);
    }

    // ---- PRE-295 standard loop ----------------------------------------------------------------------------------

    @Test
    void PRE_342_a_standard_loop_repeats_while_its_condition_holds_and_stops_at_its_maximum() {
        // Given a card payment tried while it is not PAID, at most three times
        DefaultExecutionKernel kernel = kernel(PAYMENT_RETRIES);
        Transition started = start(kernel, Map.of());
        assertThat(calls(started, "step")).hasSize(1);

        // When the first try is DECLINED, a second one starts
        Transition declined = answer(kernel, started, calls(started, "step").get(0), Map.of("status", "DECLINED"));
        assertThat(calls(declined, "step")).hasSize(1);
        assertThat(reached(declined.state(), "book")).isFalse();

        // And when the second is PAID, the loop ends after two tries
        Transition paid = answer(kernel, declined, calls(declined, "step").get(0), Map.of("status", "PAID"));
        assertThat(reached(paid.state(), "book")).isTrue();
        assertThat(instances(paid.state(), "step")).hasSize(2);

        // But a payment declined three times stops at the maximum and goes on
        Transition t1 = answer(kernel, started, calls(started, "step").get(0), Map.of("status", "DECLINED"));
        Transition t2 = answer(kernel, t1, calls(t1, "step").get(0), Map.of("status", "DECLINED"));
        Transition t3 = answer(kernel, t2, calls(t2, "step").get(0), Map.of("status", "DECLINED"));
        assertThat(calls(t3, "step")).isEmpty();
        assertThat(reached(t3.state(), "book")).isTrue();
        assertThat(instances(t3.state(), "step")).extracting(t -> t.locals().get("loopCounter")).containsExactly(1, 2, 3);
    }

    @Test
    void PRE_342_a_loop_tested_before_its_first_run_may_not_run_at_all() {
        // Given a payment loop tested before each try, and nothing due
        DefaultExecutionKernel kernel = kernel(TEST_BEFORE);

        // When it starts with an amount due of 0
        Transition none = start(kernel, Map.of("amountDue", 0));

        // Then no call is made and the loan is booked
        assertThat(calls(none, "step")).isEmpty();
        assertThat(reached(none.state(), "book")).isTrue();

        // And with 1,200,000 VND due, the first try is made
        Transition some = start(kernel, Map.of("amountDue", 1_200_000));
        assertThat(calls(some, "step")).hasSize(1);
    }

    // ---- sub-processes and call activities ---------------------------------------------------------------------

    @Test
    void PRE_337_a_multi_instance_sub_process_runs_its_own_steps_once_per_item_and_continues_after_the_last() {
        // Given two branches of a split disbursement, each a sub-process of reserve then post
        DefaultExecutionKernel kernel = kernel(SUB_PROCESS_PER_BRANCH);

        // When it starts
        Transition started = start(kernel, Map.of("branches", List.of("HCM-01", "HN-02")));

        // Then both reserve steps run, each under its own scope of the body
        List<Effect.InvokeService> reserves = calls(started, "reserve");
        assertThat(reserves).hasSize(2);
        assertThat(instances(started.state(), "step")).extracting(t -> t.locals().get("branch"))
                .containsExactly("HCM-01", "HN-02");

        // When both reserve and post, the loan is booked once
        Transition r1 = answer(kernel, started, reserves.get(0), Map.of());
        Transition r2 = answer(kernel, r1, reserves.get(1), Map.of());
        Transition p1 = answer(kernel, r2, calls(r1, "post").get(0), Map.of());
        assertThat(reached(p1.state(), "book")).isFalse();
        Transition p2 = answer(kernel, p1, calls(r2, "post").get(0), Map.of());
        assertThat(p2.state().tokens().stream().filter(t -> t.kind() == TokenKind.FLOW && t.nodeId().equals("book")))
                .hasSize(1);
        assertThat(body(p2.state(), "step").locals()).containsEntry("nrOfCompletedInstances", 2);
    }

    @Test
    void PRE_338_a_completion_condition_on_a_call_activity_cancels_the_children_still_running() {
        // Given three accounts checked by a called process, and a loan that needs one answer
        DefaultExecutionKernel kernel = kernel(CALL_PER_ACCOUNT);
        Transition started = start(kernel, Map.of("accounts", List.of("0011-1", "0011-2", "0011-3")));
        List<Effect.StartChildInstance> children = started.effects().stream()
                .filter(Effect.StartChildInstance.class::isInstance).map(Effect.StartChildInstance.class::cast).toList();
        assertThat(children).hasSize(3);
        assertThat(children).extracting(c -> c.variables().get("account")).containsExactly("0011-1", "0011-2", "0011-3");

        // When the second child ends
        Effect.StartChildInstance second = children.get(1);
        Transition ended = kernel.apply(started.state(), new Command.ChildEnded(second.tokenId(), second.attemptId(),
                "01JD0000000000000000000777", Command.ChildOutcome.COMPLETED, Map.of("status", "CLEAR"), null), CLOCK);

        // Then the other two children are cancelled and the loan continues with the one status
        assertThat(ended.effects()).filteredOn(Effect.CancelChildInstance.class::isInstance).extracting(e ->
                ((Effect.CancelChildInstance) e).tokenId()).containsExactlyInAnyOrder(children.get(0).tokenId(),
                children.get(2).tokenId());
        assertThat(reached(ended.state(), "book")).isTrue();
        assertThat(ended.state().variables().get("statuses")).isEqualTo(java.util.Arrays.asList(null, "CLEAR", null));
    }

    // ---- import, export and refusals ----------------------------------------------------------------------------

    @Test
    void PRE_343_a_loop_reads_with_its_settings_and_exports_back_to_the_same_definition() {
        for (String xml : List.of(FIRST_TWO, SEQUENTIAL_SCREENING, PAYMENT_RETRIES, TEST_BEFORE, SUB_PROCESS_PER_BRANCH,
                CALL_PER_ACCOUNT)) {
            // When the file is read and exported
            Process process = read(xml);
            byte[] exported = new SubsetBpmnWriter().write(process);

            // Then the canonical form states the loop, and the export reads back to the same form
            assertThat(CanonicalProcessWriter.canonicalText(process)).contains("\"loop\":{");
            assertThat(CanonicalProcessWriter.canonicalText(reader.read(exported)))
                    .isEqualTo(CanonicalProcessWriter.canonicalText(process));
        }
        assertThat(CanonicalProcessWriter.canonicalText(read(FIRST_TWO)))
                .contains("\"completionCondition\":\"nrOfCompletedInstances >= 2\"").contains("\"kind\":\"parallel\"");
    }

    @Test
    void PRE_343_a_loop_that_cannot_end_or_has_no_source_is_refused_at_import() {
        // Given a loop without a maximum, a multi-instance step with neither a collection nor a cardinality, and a
        // looped step that declares an undo
        String unbounded = PAYMENT_RETRIES.replace(" loopMaximum=\"3\"", "");
        String noSource = PARALLEL_COSIGNERS.replace(" horus:collection=\"cosigners\"", "");
        String undoLoop = PAYMENT_RETRIES.replace("horus:resultVariable=\"payment\"", "horus:resultVariable=\"payment\""
                + " horus:compensate=\"refund\"");

        // Then each is refused with its own code
        assertThat(codes(unbounded)).contains("LOOP_UNBOUNDED");
        assertThat(codes(noSource)).contains("MULTI_INSTANCE_SOURCE");
        assertThat(codes(undoLoop)).contains("LOOP_COMPENSATION_UNSUPPORTED");
    }

    private List<String> codes(String xml) {
        Process process = read(xml);
        return org.assertj.core.api.Assertions.catchThrowableOfType(ValidationFailedException.class,
                () -> kernel(process)).problems().stream().map(DefinitionProblem::code).toList();
    }

    private DefaultExecutionKernel kernel(Process process) {
        return new DefaultExecutionKernel(new ProcessCompiler(BehaviorCatalog.registry(), 500).compile("D1", process),
                BehaviorCatalog.registry(), new V1ExpressionEvaluator(100));
    }

    @Test
    void PRE_343_a_loop_condition_in_another_language_is_refused_with_its_stage() {
        String feel = PAYMENT_RETRIES.replace("<bpmn:loopCondition>", "<bpmn:loopCondition language=\"urn:feel\">");
        assertThatThrownBy(() -> read(feel)).hasMessageContaining("language");
    }
}
