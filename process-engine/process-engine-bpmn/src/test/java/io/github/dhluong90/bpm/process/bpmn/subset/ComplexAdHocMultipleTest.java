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
import io.github.dhluong90.bpm.process.internal.exec.ExecutionException;
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
 * Complex gateway, ad-hoc sub-process and multiple events (#500), on the reader, the compiler and the kernel in memory:
 * a payment that is released once two of three approvers have signed, a customer onboarding whose checks the officer
 * starts in any order, and a wait for a rate change or a message.
 */
class ComplexAdHocMultipleTest {

    private static final String INSTANCE = "01JD0000000000000000000500";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-23T03:00:00Z"), ZoneOffset.UTC);
    private static final String HEAD = """
            <?xml version="1.0" encoding="UTF-8"?>
            <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                              xmlns:horus="urn:horus-bpm:bpmn:extensions:v1" id="d" targetNamespace="urn:t"
                              expressionLanguage="urn:horus-bpm:expr:v1">
              <bpmn:error id="Err_limit" name="Limit exceeded" errorCode="LIMIT_EXCEEDED"/>
              <bpmn:message id="Msg_funds" name="funds-received"/>
              <bpmn:signal id="Sig_rate" name="rate-card-updated"/>
              <bpmn:process id="p" isExecutable="true">
            """;
    private static final String TAIL = "</bpmn:process></bpmn:definitions>";

    private final SubsetBpmnReader reader = new SubsetBpmnReader(Limits.DEFAULTS);

    private Process read(String xml) {
        return reader.read(xml.getBytes(StandardCharsets.UTF_8));
    }

    private DefaultExecutionKernel kernel(String xml) {
        CompiledProcess compiled = new ProcessCompiler(BehaviorCatalog.registry(), 500).compile("D1", read(xml));
        return new DefaultExecutionKernel(compiled, BehaviorCatalog.registry(), new V1ExpressionEvaluator(100));
    }

    private static Transition start(DefaultExecutionKernel kernel, Map<String, Object> variables) {
        return kernel.apply(InstanceState.created(INSTANCE, "bank-a", "D1", "PAY-2026-0042"),
                new Command.Start(variables, "officer.hoa"), CLOCK);
    }

    private static List<Token> at(InstanceState state, String nodeId) {
        return state.tokens().stream().filter(t -> t.nodeId().equals(nodeId) && t.kind() == TokenKind.FLOW).toList();
    }

    private static Token open(InstanceState state, String nodeId) {
        return at(state, nodeId).stream().filter(t -> t.status().live()).findFirst().orElseThrow();
    }

    private static <E extends Effect> List<E> effects(Transition t, Class<E> type) {
        return t.effects().stream().filter(type::isInstance).map(type::cast).toList();
    }

    private static Transition complete(DefaultExecutionKernel kernel, InstanceState from, String nodeId,
            Map<String, Object> result) {
        Token task = open(from, nodeId);
        return kernel.apply(from, new Command.CompleteTask(task.activityInstanceId(), task.id(), result, "officer.lan"),
                CLOCK);
    }

    private static Transition startActivity(DefaultExecutionKernel kernel, InstanceState from, String activity) {
        Token scope = from.tokens().stream().filter(t -> t.kind() == TokenKind.SCOPE && t.nodeId().equals("onboarding")
                && t.status().live()).findFirst().orElseThrow();
        return kernel.apply(from, new Command.StartAdHocActivity(scope.id(), activity, "officer.lan"), CLOCK);
    }

    // ---- complex gateway ---------------------------------------------------------------------------------------

    private static final String THREE_APPROVERS = HEAD + """
            <bpmn:startEvent id="start"/>
            <bpmn:parallelGateway id="fork"/>
            <bpmn:userTask id="branch-a" horus:assignee="role:CHIEF_ACCOUNTANT" horus:resultVariable="a"/>
            <bpmn:userTask id="branch-b" horus:assignee="role:TREASURER" horus:resultVariable="b"/>
            <bpmn:userTask id="branch-c" horus:assignee="role:CFO" horus:resultVariable="c"/>
            <bpmn:complexGateway id="join">
              <bpmn:activationCondition>_activationCount &gt;= 2</bpmn:activationCondition>
            </bpmn:complexGateway>
            <bpmn:userTask id="release" horus:assignee="role:OPERATIONS" horus:resultVariable="released"/>
            <bpmn:endEvent id="end"/>
            <bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="fork"/>
            <bpmn:sequenceFlow id="f2" sourceRef="fork" targetRef="branch-a"/>
            <bpmn:sequenceFlow id="f3" sourceRef="fork" targetRef="branch-b"/>
            <bpmn:sequenceFlow id="f4" sourceRef="fork" targetRef="branch-c"/>
            <bpmn:sequenceFlow id="f5" sourceRef="branch-a" targetRef="join"/>
            <bpmn:sequenceFlow id="f6" sourceRef="branch-b" targetRef="join"/>
            <bpmn:sequenceFlow id="f7" sourceRef="branch-c" targetRef="join"/>
            <bpmn:sequenceFlow id="f8" sourceRef="join" targetRef="release"/>
            <bpmn:sequenceFlow id="f9" sourceRef="release" targetRef="end"/>
            """ + TAIL;

    @Test
    void PRE_330_a_complex_join_fires_once_when_its_condition_is_met_and_absorbs_the_late_token() {
        // Given a payment of VND 2,500,000,000 that three approvers may sign, released after any two
        DefaultExecutionKernel kernel = kernel(THREE_APPROVERS);
        Transition state = start(kernel, Map.of());

        // When the chief accountant signs, the join holds
        state = complete(kernel, state.state(), "branch-a", Map.of("ok", true));
        assertThat(at(state.state(), "release")).isEmpty();

        // When the treasurer signs, the condition holds and the payment is released once
        state = complete(kernel, state.state(), "branch-b", Map.of("ok", true));
        assertThat(at(state.state(), "release")).hasSize(1);

        // When the CFO signs late, the token is absorbed and nothing is released again
        state = complete(kernel, state.state(), "branch-c", Map.of("ok", true));
        assertThat(at(state.state(), "release")).hasSize(1);
        assertThat(state.state().tokens().stream().filter(t -> t.nodeId().equals("join"))
                .allMatch(t -> t.status() == TokenStatus.COMPLETED)).isTrue();
        state = complete(kernel, state.state(), "release", Map.of("ok", true));
        assertThat(state.state().status()).isEqualTo(InstanceStatus.COMPLETED);
    }

    @Test
    void PRE_330_a_complex_gateway_splits_on_its_outgoing_conditions_and_falls_back_to_the_default_flow() {
        // Given a transfer routed by amount: over VND 1,000,000,000 to a second review, otherwise automatic
        String routed = HEAD + """
                <bpmn:startEvent id="start"/>
                <bpmn:complexGateway id="route" default="to-auto"/>
                <bpmn:userTask id="review" horus:assignee="role:TREASURER" horus:resultVariable="reviewed"/>
                <bpmn:userTask id="auto" horus:assignee="role:OPERATIONS" horus:resultVariable="autoDone"/>
                <bpmn:endEvent id="end"/>
                <bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="route"/>
                <bpmn:sequenceFlow id="to-review" sourceRef="route" targetRef="review">
                  <bpmn:conditionExpression>amount &gt; 1000000000</bpmn:conditionExpression>
                </bpmn:sequenceFlow>
                <bpmn:sequenceFlow id="to-auto" sourceRef="route" targetRef="auto"/>
                <bpmn:sequenceFlow id="f4" sourceRef="review" targetRef="end"/>
                <bpmn:sequenceFlow id="f5" sourceRef="auto" targetRef="end"/>
                """ + TAIL;
        DefaultExecutionKernel kernel = kernel(routed);

        Transition large = start(kernel, Map.of("amount", 2_500_000_000L));
        assertThat(at(large.state(), "review")).hasSize(1);
        assertThat(at(large.state(), "auto")).isEmpty();

        Transition small = start(kernel, Map.of("amount", 5_000_000L));
        assertThat(at(small.state(), "review")).isEmpty();
        assertThat(at(small.state(), "auto")).hasSize(1);
    }

    @Test
    void PRE_330_a_complex_join_whose_condition_never_holds_fails_the_step_instead_of_stalling() {
        String never = THREE_APPROVERS.replace("_activationCount &gt;= 2", "_activationCount &gt; 3");
        DefaultExecutionKernel kernel = kernel(never);
        Transition state = start(kernel, Map.of());
        state = complete(kernel, state.state(), "branch-a", Map.of());
        Transition next = complete(kernel, state.state(), "branch-b", Map.of());
        InstanceState held = next.state();
        assertThatThrownBy(() -> complete(kernel, held, "branch-c", Map.of())).isInstanceOf(ExecutionException.class)
                .hasMessageContaining("activation condition");
    }

    // ---- ad-hoc sub-process ------------------------------------------------------------------------------------

    private static String onboarding(String ordering, String attributes) {
        return HEAD + """
                <bpmn:startEvent id="start"/>
                <bpmn:adHocSubProcess id="onboarding" %s %s>
                  <bpmn:userTask id="collect-id" horus:assignee="role:OFFICER" horus:resultVariable="idCheck"/>
                  <bpmn:userTask id="screen-sanctions" horus:assignee="role:COMPLIANCE" horus:resultVariable="sanctions"/>
                  <bpmn:userTask id="call-referee" horus:assignee="role:OFFICER" horus:resultVariable="referee"/>
                  <bpmn:completionCondition>idCheck.passed == true</bpmn:completionCondition>
                </bpmn:adHocSubProcess>
                <bpmn:userTask id="open-account" horus:assignee="role:OFFICER" horus:resultVariable="opened"/>
                <bpmn:endEvent id="end"/>
                <bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="onboarding"/>
                <bpmn:sequenceFlow id="f2" sourceRef="onboarding" targetRef="open-account"/>
                <bpmn:sequenceFlow id="f3" sourceRef="open-account" targetRef="end"/>
                """.formatted(ordering, attributes) + TAIL;
    }

    @Test
    void PRE_331_an_ad_hoc_sub_process_starts_nothing_until_the_host_starts_an_activity() {
        DefaultExecutionKernel kernel = kernel(onboarding("ordering=\"Parallel\"", ""));

        Transition entered = start(kernel, Map.of());

        assertThat(entered.state().tokens().stream().filter(t -> t.kind() == TokenKind.SCOPE
                && t.nodeId().equals("onboarding") && t.status().live())).hasSize(1);
        assertThat(entered.state().tokens().stream().filter(t -> t.kind() == TokenKind.FLOW && t.status().live()))
                .isEmpty();
    }

    @Test
    void PRE_331_with_parallel_ordering_the_condition_cancels_the_remaining_activities_and_the_flow_goes_on() {
        // Given the officer has started the identity check and the sanctions screening side by side
        DefaultExecutionKernel kernel = kernel(onboarding("ordering=\"Parallel\"", ""));
        Transition state = start(kernel, Map.of());
        state = startActivity(kernel, state.state(), "collect-id");
        state = startActivity(kernel, state.state(), "screen-sanctions");
        Token sanctions = open(state.state(), "screen-sanctions");

        // When the identity check passes, the completion condition holds
        Transition done = complete(kernel, state.state(), "collect-id", Map.of("passed", true));

        // Then the screening is cancelled with its task and the flow continues to account opening
        assertThat(effects(done, Effect.CancelUserTask.class)).extracting(Effect.CancelUserTask::taskId)
                .containsExactly(sanctions.activityInstanceId());
        assertThat(at(done.state(), "screen-sanctions").get(0).status()).isEqualTo(TokenStatus.CANCELLED);
        assertThat(open(done.state(), "open-account").status()).isEqualTo(TokenStatus.WAITING_USER);
    }

    @Test
    void PRE_331_a_false_condition_leaves_the_sub_process_open_and_an_activity_may_run_again() {
        DefaultExecutionKernel kernel = kernel(onboarding("ordering=\"Parallel\"", ""));
        Transition state = start(kernel, Map.of());
        state = startActivity(kernel, state.state(), "collect-id");

        state = complete(kernel, state.state(), "collect-id", Map.of("passed", false));

        assertThat(at(state.state(), "open-account")).isEmpty();
        state = startActivity(kernel, state.state(), "collect-id");
        state = complete(kernel, state.state(), "collect-id", Map.of("passed", true));
        assertThat(open(state.state(), "open-account")).isNotNull();
    }

    @Test
    void PRE_331_with_sequential_ordering_a_second_activity_is_not_enabled_while_one_runs() {
        DefaultExecutionKernel kernel = kernel(onboarding("ordering=\"Sequential\"", ""));
        Transition state = start(kernel, Map.of());
        InstanceState running = startActivity(kernel, state.state(), "collect-id").state();

        assertThatThrownBy(() -> startActivity(kernel, running, "screen-sanctions"))
                .isInstanceOfSatisfying(ExecutionException.class, e ->
                        assertThat(e.code()).isEqualTo("ACTIVITY_NOT_ENABLED"));
        assertThatThrownBy(() -> startActivity(kernel, running, "no-such-activity"))
                .isInstanceOfSatisfying(ExecutionException.class, e ->
                        assertThat(e.code()).isEqualTo("ACTIVITY_NOT_FOUND"));

        // Once it ends the next one is enabled
        Transition after = complete(kernel, running, "collect-id", Map.of("passed", false));
        assertThat(open(startActivity(kernel, after.state(), "screen-sanctions").state(), "screen-sanctions"))
                .isNotNull();
    }

    @Test
    void PRE_331_without_a_condition_the_sub_process_ends_when_every_activity_has_completed_once() {
        String plain = onboarding("", "").replace(
                "<bpmn:completionCondition>idCheck.passed == true</bpmn:completionCondition>", "");
        DefaultExecutionKernel kernel = kernel(plain);
        Transition state = start(kernel, Map.of());
        for (String activity : List.of("collect-id", "screen-sanctions", "call-referee")) {
            state = startActivity(kernel, state.state(), activity);
            assertThat(at(state.state(), "open-account")).isEmpty();
            state = complete(kernel, state.state(), activity, Map.of());
        }
        assertThat(open(state.state(), "open-account")).isNotNull();
    }

    // ---- multiple and parallel multiple events -----------------------------------------------------------------

    private static String wait(String attributes) {
        return HEAD + """
                <bpmn:startEvent id="start"/>
                <bpmn:intermediateCatchEvent id="wait" %s horus:correlationKey="paymentId">
                  <bpmn:signalEventDefinition signalRef="Sig_rate"/>
                  <bpmn:messageEventDefinition messageRef="Msg_funds"/>
                </bpmn:intermediateCatchEvent>
                <bpmn:endEvent id="end"/>
                <bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="wait"/>
                <bpmn:sequenceFlow id="f2" sourceRef="wait" targetRef="end"/>
                """.formatted(attributes) + TAIL;
    }

    @Test
    void PRE_332_a_multiple_catch_event_fires_on_the_first_definition_and_drops_the_other_wait() {
        // Given a payment that waits for a rate change or for the funds to arrive
        DefaultExecutionKernel kernel = kernel(wait(""));
        Transition started = start(kernel, Map.of("paymentId", "PAY-0042"));
        List<Effect.CreateSubscription> waits = effects(started, Effect.CreateSubscription.class);
        assertThat(waits).extracting(Effect.CreateSubscription::kind).containsExactlyInAnyOrder("SIGNAL", "MESSAGE");
        Effect.CreateSubscription funds = waits.stream().filter(w -> w.kind().equals("MESSAGE")).findFirst()
                .orElseThrow();

        // When the funds arrive
        Transition fired = kernel.apply(started.state(), new Command.CorrelateMessage(funds.subscriptionId(),
                "funds-received", Map.of()), CLOCK);

        // Then the payment moves on at once and the signal wait is deleted
        assertThat(fired.state().status()).isEqualTo(InstanceStatus.COMPLETED);
        assertThat(effects(fired, Effect.DeleteSubscription.class)).extracting(Effect.DeleteSubscription::subscriptionId)
                .contains(waits.stream().filter(w -> w.kind().equals("SIGNAL")).findFirst().orElseThrow()
                        .subscriptionId());
    }

    @Test
    void PRE_332_a_parallel_multiple_catch_event_waits_until_every_definition_has_occurred() {
        DefaultExecutionKernel kernel = kernel(wait("parallelMultiple=\"true\""));
        Transition started = start(kernel, Map.of("paymentId", "PAY-0042"));
        List<Effect.CreateSubscription> waits = effects(started, Effect.CreateSubscription.class);
        Effect.CreateSubscription signal = waits.stream().filter(w -> w.kind().equals("SIGNAL")).findFirst()
                .orElseThrow();
        Effect.CreateSubscription funds = waits.stream().filter(w -> w.kind().equals("MESSAGE")).findFirst()
                .orElseThrow();

        // When only the rate change occurs, the token waits
        Transition one = kernel.apply(started.state(), new Command.DeliverSignal(signal.tokenId(),
                signal.subscriptionId(), "rate-card-updated"), CLOCK);
        assertThat(one.state().status()).isEqualTo(InstanceStatus.RUNNING);
        assertThat(at(one.state(), "end")).isEmpty();

        // When the funds arrive too, it passes
        Transition two = kernel.apply(one.state(), new Command.CorrelateMessage(funds.subscriptionId(),
                "funds-received", Map.of()), CLOCK);
        assertThat(two.state().status()).isEqualTo(InstanceStatus.COMPLETED);
    }

    @Test
    void PRE_332_a_multiple_boundary_event_fires_on_its_timer_and_interrupts_the_activity() {
        String xml = HEAD + """
                <bpmn:startEvent id="start"/>
                <bpmn:serviceTask id="charge" horus:handler="charge-card"/>
                <bpmn:endEvent id="paid"/>
                <bpmn:boundaryEvent id="slow" attachedToRef="charge">
                  <bpmn:timerEventDefinition><bpmn:timeDuration>PT1H</bpmn:timeDuration></bpmn:timerEventDefinition>
                  <bpmn:errorEventDefinition errorRef="Err_limit"/>
                </bpmn:boundaryEvent>
                <bpmn:endEvent id="gave-up"/>
                <bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="charge"/>
                <bpmn:sequenceFlow id="f2" sourceRef="charge" targetRef="paid"/>
                <bpmn:sequenceFlow id="f3" sourceRef="slow" targetRef="gave-up"/>
                """ + TAIL;
        DefaultExecutionKernel kernel = kernel(xml);
        Transition started = start(kernel, Map.of());
        Effect.ScheduleJob job = effects(started, Effect.ScheduleJob.class).get(0);

        Transition fired = kernel.apply(started.state(), new Command.JobDue(job.jobId(), job.tokenId(), job.jobKind()),
                CLOCK);

        assertThat(at(fired.state(), "charge").get(0).status()).isEqualTo(TokenStatus.CANCELLED);
        assertThat(fired.state().status()).isEqualTo(InstanceStatus.COMPLETED);
        assertThat(at(fired.state(), "gave-up")).isNotEmpty();
    }

    @Test
    void PRE_332_a_multiple_start_of_an_event_sub_process_fires_on_one_trigger_and_drops_the_other() {
        String xml = HEAD + """
                <bpmn:startEvent id="start"/>
                <bpmn:userTask id="assess" horus:assignee="role:UNDERWRITER" horus:resultVariable="assessment"/>
                <bpmn:endEvent id="end"/>
                <bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="assess"/>
                <bpmn:sequenceFlow id="f2" sourceRef="assess" targetRef="end"/>
                <bpmn:subProcess id="withdrawal" triggeredByEvent="true">
                  <bpmn:startEvent id="withdrawn" horus:correlationKey="paymentId">
                    <bpmn:timerEventDefinition><bpmn:timeDuration>PT24H</bpmn:timeDuration></bpmn:timerEventDefinition>
                    <bpmn:messageEventDefinition messageRef="Msg_funds"/>
                  </bpmn:startEvent>
                  <bpmn:serviceTask id="w-do" horus:handler="close-file"/>
                  <bpmn:endEvent id="w-end"/>
                  <bpmn:sequenceFlow id="w1" sourceRef="withdrawn" targetRef="w-do"/>
                  <bpmn:sequenceFlow id="w2" sourceRef="w-do" targetRef="w-end"/>
                </bpmn:subProcess>
                """ + TAIL;
        DefaultExecutionKernel kernel = kernel(xml);
        Transition started = start(kernel, Map.of("paymentId", "PAY-0042"));
        Effect.ScheduleJob job = effects(started, Effect.ScheduleJob.class).get(0);

        Transition fired = kernel.apply(started.state(), new Command.JobDue(job.jobId(), job.tokenId(), job.jobKind()),
                CLOCK);

        assertThat(effects(fired, Effect.CancelUserTask.class)).hasSize(1);
        assertThat(effects(fired, Effect.DeleteSubscription.class)).hasSize(1);
        assertThat(effects(fired, Effect.InvokeService.class)).extracting(Effect.InvokeService::target)
                .containsExactly("close-file");
        assertThat(fired.history().stream().filter(h -> h.type().equals("EVENT_SUBPROCESS_STARTED")).findFirst()
                .orElseThrow().details()).containsEntry("trigger", "TIMER");
    }

    // ---- timer cycle on an event sub-process start --------------------------------------------------------------

    @Test
    void PRE_333_a_timer_cycle_start_repeats_a_non_interrupting_event_sub_process_for_its_count() {
        // Given a reminder every 24 hours, twice
        String xml = HEAD + """
                <bpmn:startEvent id="start"/>
                <bpmn:userTask id="assess" horus:assignee="role:UNDERWRITER" horus:resultVariable="assessment"/>
                <bpmn:endEvent id="end"/>
                <bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="assess"/>
                <bpmn:sequenceFlow id="f2" sourceRef="assess" targetRef="end"/>
                <bpmn:subProcess id="reminder" triggeredByEvent="true">
                  <bpmn:startEvent id="every-day" isInterrupting="false">
                    <bpmn:timerEventDefinition><bpmn:timeCycle>R2/PT24H</bpmn:timeCycle></bpmn:timerEventDefinition>
                  </bpmn:startEvent>
                  <bpmn:serviceTask id="remind" horus:handler="send-reminder"/>
                  <bpmn:endEvent id="r-end"/>
                  <bpmn:sequenceFlow id="r1" sourceRef="every-day" targetRef="remind"/>
                  <bpmn:sequenceFlow id="r2" sourceRef="remind" targetRef="r-end"/>
                </bpmn:subProcess>
                """ + TAIL;
        DefaultExecutionKernel kernel = kernel(xml);
        Transition started = start(kernel, Map.of());
        Effect.ScheduleJob first = effects(started, Effect.ScheduleJob.class).get(0);

        // When it fires the first time, the next one is scheduled
        Transition one = kernel.apply(started.state(), new Command.JobDue(first.jobId(), first.tokenId(),
                first.jobKind()), CLOCK);
        Effect.ScheduleJob second = effects(one, Effect.ScheduleJob.class).get(0);
        assertThat(second.dueAt()).isEqualTo(Instant.parse("2026-11-24T03:00:00Z"));

        // When it fires the second time, no further timer is scheduled
        Transition two = kernel.apply(one.state(), new Command.JobDue(second.jobId(), second.tokenId(),
                second.jobKind()), CLOCK);
        assertThat(effects(two, Effect.ScheduleJob.class)).isEmpty();
    }

    // ---- refusals and the file round trip -----------------------------------------------------------------------

    private void refused(String xml, String code, String elementId) {
        assertThatThrownBy(() -> kernel(xml)).isInstanceOfSatisfying(ValidationFailedException.class, e ->
                assertThat(e.problems()).extracting(DefinitionProblem::code, DefinitionProblem::elementId)
                        .contains(org.assertj.core.groups.Tuple.tuple(code, elementId)));
    }

    @Test
    void PRE_334_a_misplaced_ad_hoc_sub_process_or_multiple_event_is_refused_with_its_code() {
        // Sequence flow between the activities of an ad-hoc sub-process
        refused(onboarding("", "").replace("<bpmn:completionCondition>",
                "<bpmn:sequenceFlow id=\"x\" sourceRef=\"collect-id\" targetRef=\"screen-sanctions\"/>"
                        + "<bpmn:completionCondition>"), "AD_HOC_HAS_FLOW", "x");
        // A process start waits for messages and signals only: a timer or a condition cannot be held before an instance
        refused(HEAD + """
                <bpmn:startEvent id="start" parallelMultiple="true">
                  <bpmn:signalEventDefinition signalRef="Sig_rate"/>
                  <bpmn:timerEventDefinition><bpmn:timeDuration>PT1H</bpmn:timeDuration></bpmn:timerEventDefinition>
                </bpmn:startEvent>
                <bpmn:endEvent id="end"/>
                <bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="end"/>
                """ + TAIL, "MULTIPLE_EVENT_DEFINITION_INVALID", "start");
        // A throw event with several definitions throws each and goes on: an error end has no way on
        refused(HEAD + """
                <bpmn:startEvent id="start"/>
                <bpmn:endEvent id="close">
                  <bpmn:signalEventDefinition signalRef="Sig_rate"/>
                  <bpmn:terminateEventDefinition/>
                </bpmn:endEvent>
                <bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="close"/>
                """ + TAIL, "MULTIPLE_THROW_DEFINITION_INVALID", "close");
        // A timer cycle on a catch event stays refused
        refused(wait("").replace("<bpmn:signalEventDefinition signalRef=\"Sig_rate\"/>",
                "<bpmn:timerEventDefinition><bpmn:timeDuration>PT1H</bpmn:timeDuration></bpmn:timerEventDefinition>")
                .replace("<bpmn:startEvent id=\"start\"/>", "<bpmn:startEvent id=\"start\"/>")
                .replace("<bpmn:messageEventDefinition messageRef=\"Msg_funds\"/>",
                        "<bpmn:messageEventDefinition messageRef=\"Msg_funds\"/>"
                                + "<bpmn:messageEventDefinition messageRef=\"Msg_funds\"/>"),
                "MULTIPLE_EVENT_MESSAGE_ONCE", "wait");
    }

    @Test
    void PRE_335_the_export_of_each_element_reads_back_to_the_same_definition() {
        for (String xml : List.of(THREE_APPROVERS, onboarding("ordering=\"Sequential\"", "cancelRemainingInstances=\"false\""),
                wait("parallelMultiple=\"true\""))) {
            Process original = read(xml);
            Process again = reader.read(new SubsetBpmnWriter().write(original));
            assertThat(CanonicalProcessWriter.canonicalText(again)).isEqualTo(CanonicalProcessWriter.canonicalText(original));
            new ProcessCompiler(BehaviorCatalog.registry(), 500).compile("D1", again);
        }
        assertThat(CanonicalProcessWriter.canonicalText(read(THREE_APPROVERS))).contains("\"type\":\"complexGateway\"")
                .contains("\"activation\":\"_activationCount >= 2\"");
        assertThat(CanonicalProcessWriter.canonicalText(read(wait("parallelMultiple=\"true\""))))
                .contains("\"trigger\":\"parallelMultiple\"");
    }
}
