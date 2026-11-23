package io.github.dhluong90.bpm.process.bpmn.subset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.process.api.ValidationFailedException;
import io.github.dhluong90.bpm.process.api.BpmnUnsupportedElementException;
import io.github.dhluong90.bpm.process.bpmn.xml.Limits;
import io.github.dhluong90.bpm.process.internal.exec.BehaviorCatalog;
import io.github.dhluong90.bpm.process.internal.exec.Command;
import io.github.dhluong90.bpm.process.internal.exec.CompiledProcess;
import io.github.dhluong90.bpm.process.internal.exec.DefaultExecutionKernel;
import io.github.dhluong90.bpm.process.internal.exec.Effect;
import io.github.dhluong90.bpm.process.internal.exec.ExecutionException;
import io.github.dhluong90.bpm.process.internal.exec.HistoryEntry;
import io.github.dhluong90.bpm.process.internal.exec.InstanceState;
import io.github.dhluong90.bpm.process.internal.exec.InstanceStatus;
import io.github.dhluong90.bpm.process.internal.exec.ProcessCompiler;
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
 * Message, signal and conditional boundary events and the event-based gateway (#490, #495) on the reader, the compiler
 * and the kernel, in memory: a payment review that a cancellation message interrupts, a dispute desk that a signal
 * only notifies, a screening that a risk score interrupts, and a payment wait that a message or a two-hour timer
 * resolves, whichever comes first. The race of two events on two replicas is the database integration test's.
 */
@SuppressWarnings("unchecked")
class EventBoundariesAndGatewayTest {

    private static final String INSTANCE = "01JD0000000000000000000490";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-23T03:00:00Z"), ZoneOffset.UTC);
    private static final String HEAD = """
            <?xml version="1.0" encoding="UTF-8"?>
            <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                              xmlns:horus="urn:horus-bpm:bpmn:extensions:v1" id="d" targetNamespace="urn:t"
                              expressionLanguage="urn:horus-bpm:expr:v1">
              <bpmn:message id="M_cancel" name="payment-cancelled"/>
              <bpmn:message id="M_paid" name="payment-received"/>
              <bpmn:signal id="S_freeze" name="account-frozen"/>
              <bpmn:process id="p" isExecutable="true">
            """;
    private static final String TAIL = "</bpmn:process></bpmn:definitions>";

    /** An officer reviews a payment; {@code boundary} is the boundary event on it. */
    private static String review(String boundary) {
        return HEAD + """
                <bpmn:startEvent id="start"/>
                <bpmn:userTask id="review" horus:assignee="role:PAYMENT_OFFICER" horus:resultVariable="outcome"/>
                %s
                <bpmn:endEvent id="end"/>
                <bpmn:endEvent id="handled"/>
                <bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="review"/>
                <bpmn:sequenceFlow id="f2" sourceRef="review" targetRef="end"/>
                <bpmn:sequenceFlow id="f3" sourceRef="watch" targetRef="handled"/>
                """.formatted(boundary) + TAIL;
    }

    private static String messageBoundary(String attributes) {
        return """
                <bpmn:boundaryEvent id="watch" attachedToRef="review" %s horus:correlationKey="paymentId">
                  <bpmn:messageEventDefinition messageRef="M_cancel"/>
                </bpmn:boundaryEvent>
                """.formatted(attributes);
    }

    private static String signalBoundary(String attributes) {
        return """
                <bpmn:boundaryEvent id="watch" attachedToRef="review" %s>
                  <bpmn:signalEventDefinition signalRef="S_freeze"/>
                </bpmn:boundaryEvent>
                """.formatted(attributes);
    }

    /** Two branches: a review that a risk score of 80 or more interrupts, and a scoring task that sets the score. */
    private static String screening(String attributes) {
        return HEAD + """
                <bpmn:startEvent id="start"/>
                <bpmn:parallelGateway id="fork"/>
                <bpmn:userTask id="review" horus:assignee="role:PAYMENT_OFFICER" horus:resultVariable="outcome"/>
                <bpmn:boundaryEvent id="watch" attachedToRef="review" %s>
                  <bpmn:conditionalEventDefinition><bpmn:condition>risk.score &gt;= 80</bpmn:condition></bpmn:conditionalEventDefinition>
                </bpmn:boundaryEvent>
                <bpmn:userTask id="scoring" horus:assignee="role:RISK_ANALYST" horus:resultVariable="risk"/>
                <bpmn:endEvent id="reviewed"/>
                <bpmn:endEvent id="scored"/>
                <bpmn:endEvent id="escalated"/>
                <bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="fork"/>
                <bpmn:sequenceFlow id="f2" sourceRef="fork" targetRef="review"/>
                <bpmn:sequenceFlow id="f3" sourceRef="fork" targetRef="scoring"/>
                <bpmn:sequenceFlow id="f4" sourceRef="review" targetRef="reviewed"/>
                <bpmn:sequenceFlow id="f5" sourceRef="scoring" targetRef="scored"/>
                <bpmn:sequenceFlow id="f6" sourceRef="watch" targetRef="escalated"/>
                """.formatted(attributes) + TAIL;
    }

    /** A payment wait: the payment message or two hours, whichever comes first. */
    private static final String PAYMENT_WAIT = HEAD + """
            <bpmn:startEvent id="start"/>
            <bpmn:eventBasedGateway id="gw"/>
            <bpmn:intermediateCatchEvent id="paid" horus:correlationKey="orderId">
              <bpmn:messageEventDefinition messageRef="M_paid"/>
            </bpmn:intermediateCatchEvent>
            <bpmn:intermediateCatchEvent id="two-hours">
              <bpmn:timerEventDefinition><bpmn:timeDuration>PT2H</bpmn:timeDuration></bpmn:timerEventDefinition>
            </bpmn:intermediateCatchEvent>
            <bpmn:intermediateCatchEvent id="frozen">
              <bpmn:signalEventDefinition signalRef="S_freeze"/>
            </bpmn:intermediateCatchEvent>
            <bpmn:endEvent id="settled"/>
            <bpmn:endEvent id="expired"/>
            <bpmn:endEvent id="blocked"/>
            <bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="gw"/>
            <bpmn:sequenceFlow id="f2" sourceRef="gw" targetRef="paid"/>
            <bpmn:sequenceFlow id="f3" sourceRef="gw" targetRef="two-hours"/>
            <bpmn:sequenceFlow id="f4" sourceRef="gw" targetRef="frozen"/>
            <bpmn:sequenceFlow id="f5" sourceRef="paid" targetRef="settled"/>
            <bpmn:sequenceFlow id="f6" sourceRef="two-hours" targetRef="expired"/>
            <bpmn:sequenceFlow id="f7" sourceRef="frozen" targetRef="blocked"/>
            """ + TAIL;

    private final SubsetBpmnReader reader = new SubsetBpmnReader(Limits.DEFAULTS);

    private Process read(String xml) {
        return reader.read(xml.getBytes(StandardCharsets.UTF_8));
    }

    private DefaultExecutionKernel kernel(String xml) {
        CompiledProcess compiled = new ProcessCompiler(BehaviorCatalog.registry(), 500).compile("D1", read(xml));
        return new DefaultExecutionKernel(compiled, BehaviorCatalog.registry(), new V1ExpressionEvaluator(100));
    }

    private static Transition start(DefaultExecutionKernel kernel, Map<String, Object> variables) {
        return kernel.apply(InstanceState.created(INSTANCE, "bank-a", "D1", "PAY-2026-0001"),
                new Command.Start(variables, "officer.hoa"), CLOCK);
    }

    private static Token on(InstanceState state, String nodeId) {
        return state.tokens().stream().filter(t -> t.nodeId().equals(nodeId) && t.kind() == TokenKind.FLOW)
                .findFirst().orElseThrow();
    }

    private static <E extends Effect> List<E> effects(Transition t, Class<E> type) {
        return t.effects().stream().filter(type::isInstance).map(type::cast).toList();
    }

    private static Effect.CreateSubscription subscription(Transition t, String nodeId) {
        return effects(t, Effect.CreateSubscription.class).stream().filter(e -> e.nodeId().equals(nodeId)).findFirst()
                .orElseThrow();
    }

    private static List<HistoryEntry> history(Transition t, String type) {
        return t.history().stream().filter(h -> h.type().equals(type)).toList();
    }

    private static Transition complete(DefaultExecutionKernel kernel, InstanceState state, String nodeId, Map<String, Object> result) {
        Token task = on(state, nodeId);
        return kernel.apply(state, new Command.CompleteTask(task.activityInstanceId(), task.id(), result,
                "officer.lan"), CLOCK);
    }

    @Test
    void PRE_290_an_interrupting_message_boundary_cancels_the_task_when_the_message_of_its_key_arrives() {
        // Given a payment review for PAY-1001 with an interrupting boundary on payment-cancelled
        DefaultExecutionKernel kernel = kernel(review(messageBoundary("")));
        Transition started = start(kernel, Map.of("paymentId", "PAY-1001"));

        // Then the task is open and the boundary holds one subscription of its own name and key
        Effect.CreateSubscription sub = subscription(started, "watch");
        assertThat(sub.kind()).isEqualTo("MESSAGE");
        assertThat(sub.name()).isEqualTo("payment-cancelled");
        assertThat(sub.correlationKey()).isEqualTo("PAY-1001");
        assertThat(on(started.state(), "review").status()).isEqualTo(TokenStatus.WAITING_USER);
        assertThat(on(started.state(), "watch").status()).isEqualTo(TokenStatus.WAITING_EVENT);

        // When the cancellation arrives, correlated by that subscription
        Token task = on(started.state(), "review");
        Transition fired = kernel.apply(started.state(), new Command.CorrelateMessage(sub.subscriptionId(),
                "payment-cancelled", Map.of()), CLOCK);

        // Then the task and its work item are cancelled, the boundary's flow ends the instance, and the timeline says why
        assertThat(on(fired.state(), "review").status()).isEqualTo(TokenStatus.CANCELLED);
        assertThat(effects(fired, Effect.CancelUserTask.class)).extracting(Effect.CancelUserTask::taskId)
                .containsExactly(task.activityInstanceId());
        assertThat(effects(fired, Effect.DeleteSubscription.class)).extracting(Effect.DeleteSubscription::subscriptionId)
                .contains(sub.subscriptionId());
        assertThat(fired.state().status()).isEqualTo(InstanceStatus.COMPLETED);
        assertThat(history(fired, "BOUNDARY_FIRED").get(0).details()).containsEntry("trigger", "MESSAGE")
                .containsEntry("message", "payment-cancelled").containsEntry("interrupting", true);

        // And a second delivery to the same subscription is refused: nothing waits on it any more
        assertThatThrownBy(() -> kernel.apply(fired.state(), new Command.CorrelateMessage(sub.subscriptionId(),
                "payment-cancelled", Map.of()), CLOCK)).isInstanceOf(ExecutionException.class)
                .extracting("code").isEqualTo("INVALID_STATE");
    }

    @Test
    void PRE_290_the_subscription_of_a_boundary_is_removed_when_the_task_completes_first() {
        DefaultExecutionKernel kernel = kernel(review(messageBoundary("")));
        Transition started = start(kernel, Map.of("paymentId", "PAY-1002"));
        Effect.CreateSubscription sub = subscription(started, "watch");

        Transition done = complete(kernel, started.state(), "review", Map.of("approved", true));

        assertThat(done.state().status()).isEqualTo(InstanceStatus.COMPLETED);
        assertThat(on(done.state(), "watch").status()).isEqualTo(TokenStatus.CANCELLED);
        assertThat(effects(done, Effect.DeleteSubscription.class)).extracting(Effect.DeleteSubscription::subscriptionId)
                .containsExactly(sub.subscriptionId());
        assertThat(history(done, "BOUNDARY_FIRED")).isEmpty();
    }

    @Test
    void PRE_291_a_non_interrupting_message_or_signal_boundary_leaves_the_task_open_and_stays_armed() {
        // Given a review with a non-interrupting message boundary
        DefaultExecutionKernel kernel = kernel(review(messageBoundary("cancelActivity=\"false\"")));
        Transition started = start(kernel, Map.of("paymentId", "PAY-1003"));
        Effect.CreateSubscription sub = subscription(started, "watch");

        // When the message arrives twice
        Transition first = kernel.apply(started.state(), new Command.CorrelateMessage(sub.subscriptionId(),
                "payment-cancelled", Map.of()), CLOCK);
        Transition second = kernel.apply(first.state(), new Command.CorrelateMessage(sub.subscriptionId(),
                "payment-cancelled", Map.of()), CLOCK);

        // Then the task is still open, the subscription was never deleted, and each message ran the flow
        assertThat(on(second.state(), "review").status()).isEqualTo(TokenStatus.WAITING_USER);
        assertThat(on(second.state(), "watch").status()).isEqualTo(TokenStatus.WAITING_EVENT);
        assertThat(effects(first, Effect.DeleteSubscription.class)).isEmpty();
        assertThat(second.state().status()).isEqualTo(InstanceStatus.RUNNING);
        assertThat(history(first, "BOUNDARY_FIRED")).hasSize(1);
        assertThat(history(second, "BOUNDARY_FIRED").get(0).details()).containsEntry("interrupting", false);

        // And completing the task removes the boundary and ends the instance
        Transition done = complete(kernel, second.state(), "review", Map.of("approved", true));
        assertThat(done.state().status()).isEqualTo(InstanceStatus.COMPLETED);
        assertThat(effects(done, Effect.DeleteSubscription.class)).extracting(Effect.DeleteSubscription::subscriptionId)
                .containsExactly(sub.subscriptionId());

        // And an interrupting signal boundary cancels the task on its broadcast, deleting its subscription
        DefaultExecutionKernel signals = kernel(review(signalBoundary("")));
        Transition waiting = start(signals, Map.of());
        Effect.CreateSubscription signal = subscription(waiting, "watch");
        assertThat(signal.kind()).isEqualTo("SIGNAL");
        Transition frozen = signals.apply(waiting.state(), new Command.DeliverSignal(on(waiting.state(), "watch").id(),
                signal.subscriptionId(), "account-frozen"), CLOCK);
        assertThat(on(frozen.state(), "review").status()).isEqualTo(TokenStatus.CANCELLED);
        assertThat(history(frozen, "BOUNDARY_FIRED").get(0).details()).containsEntry("trigger", "SIGNAL")
                .containsEntry("signal", "account-frozen");
        assertThat(frozen.state().status()).isEqualTo(InstanceStatus.COMPLETED);
    }

    @Test
    void PRE_292_a_conditional_boundary_fires_once_when_a_variable_change_makes_its_condition_true() {
        // Given a review with an interrupting conditional boundary on risk.score >= 80, and a scoring task beside it
        DefaultExecutionKernel kernel = kernel(screening(""));
        Transition started = start(kernel, Map.of());
        assertThat(on(started.state(), "watch").status()).isEqualTo(TokenStatus.WAITING_EVENT);

        // When the scoring task completes with a score of 60, the condition stays false and the review stays open
        // (nothing evaluated here fails the unrelated step that set the variable)
        Transition low = complete(kernel, started.state(), "scoring", Map.of("score", 60));
        assertThat(on(low.state(), "review").status()).isEqualTo(TokenStatus.WAITING_USER);
        assertThat(history(low, "BOUNDARY_FIRED")).isEmpty();

        // When a score of 90 is recorded, the boundary fires in that same transaction
        DefaultExecutionKernel again = kernel(screening(""));
        Transition fresh = start(again, Map.of());
        Transition high = complete(again, fresh.state(), "scoring", Map.of("score", 90));
        assertThat(on(high.state(), "review").status()).isEqualTo(TokenStatus.CANCELLED);
        assertThat(effects(high, Effect.CancelUserTask.class)).hasSize(1);
        assertThat(history(high, "BOUNDARY_FIRED")).hasSize(1);
        assertThat(history(high, "BOUNDARY_FIRED").get(0).details()).containsEntry("trigger", "CONDITION")
                .containsEntry("interrupting", true);
        assertThat(high.state().status()).isEqualTo(InstanceStatus.COMPLETED);
    }

    @Test
    void PRE_292_a_non_interrupting_conditional_boundary_fires_once_and_leaves_the_task_open() {
        DefaultExecutionKernel kernel = kernel(screening("cancelActivity=\"false\""));
        Transition started = start(kernel, Map.of());

        Transition high = complete(kernel, started.state(), "scoring", Map.of("score", 90));

        assertThat(on(high.state(), "review").status()).isEqualTo(TokenStatus.WAITING_USER);
        assertThat(history(high, "BOUNDARY_FIRED")).hasSize(1);
        assertThat(high.state().tokens().stream().filter(t -> t.nodeId().equals("watch") && t.status().live())).isEmpty();
        // A later change of any variable does not fire it again
        Transition done = complete(kernel, high.state(), "review", Map.of("approved", true));
        assertThat(history(done, "BOUNDARY_FIRED")).isEmpty();
        assertThat(done.state().status()).isEqualTo(InstanceStatus.COMPLETED);
    }

    @Test
    void PRE_293_an_event_based_gateway_runs_the_first_event_and_cancels_the_others_with_their_subscriptions_and_timers() {
        // Given an order waiting on the payment message, two hours, or a freeze signal
        DefaultExecutionKernel kernel = kernel(PAYMENT_WAIT);
        Transition started = start(kernel, Map.of("orderId", "ORD-20481"));
        Effect.CreateSubscription paid = subscription(started, "paid");
        Effect.CreateSubscription frozen = subscription(started, "frozen");
        Effect.ScheduleJob timer = effects(started, Effect.ScheduleJob.class).get(0);
        assertThat(paid.correlationKey()).isEqualTo("ORD-20481");
        assertThat(timer.dueAt()).isEqualTo(Instant.parse("2026-11-23T05:00:00Z"));

        // When the payment message arrives first
        Transition won = kernel.apply(started.state(), new Command.CorrelateMessage(paid.subscriptionId(),
                "payment-received", Map.of()), CLOCK);

        // Then the settled branch runs, the timer and the signal wait are removed, and history names winner and losers
        assertThat(won.state().status()).isEqualTo(InstanceStatus.COMPLETED);
        assertThat(effects(won, Effect.CancelJob.class)).extracting(Effect.CancelJob::jobId).containsExactly(timer.jobId());
        assertThat(effects(won, Effect.DeleteSubscription.class)).extracting(Effect.DeleteSubscription::subscriptionId)
                .contains(frozen.subscriptionId());
        HistoryEntry resolved = history(won, "EVENT_GATEWAY_RESOLVED").get(0);
        assertThat(resolved.details()).containsEntry("gatewayId", "gw").containsEntry("winner", "paid");
        assertThat(List.copyOf((List<String>) resolved.details().get("cancelled"))).containsExactlyInAnyOrder("two-hours", "frozen");

        // And the timer that was already due is refused when its job still arrives: the instance has moved on
        assertThatThrownBy(() -> kernel.apply(won.state(), new Command.JobDue(timer.jobId(), timer.tokenId(),
                timer.jobKind()), CLOCK)).isInstanceOf(ExecutionException.class);

        // When the timer fires first instead, the payment subscription goes and the expired branch runs
        Transition expired = kernel.apply(started.state(), new Command.JobDue(timer.jobId(), timer.tokenId(),
                timer.jobKind()), CLOCK);
        assertThat(history(expired, "EVENT_GATEWAY_RESOLVED").get(0).details()).containsEntry("winner", "two-hours");
        assertThat(effects(expired, Effect.DeleteSubscription.class)).extracting(Effect.DeleteSubscription::subscriptionId)
                .contains(paid.subscriptionId(), frozen.subscriptionId());
        assertThatThrownBy(() -> kernel.apply(expired.state(), new Command.CorrelateMessage(paid.subscriptionId(),
                "payment-received", Map.of()), CLOCK)).isInstanceOf(ExecutionException.class)
                .extracting("code").isEqualTo("INVALID_STATE");
    }

    @Test
    void PRE_293_cancelling_an_instance_that_waits_at_the_gateway_removes_every_subscription_and_timer() {
        DefaultExecutionKernel kernel = kernel(PAYMENT_WAIT);
        Transition started = start(kernel, Map.of("orderId", "ORD-20482"));

        Transition cancelled = kernel.apply(started.state(), new Command.Cancel("customer withdrew", "officer.hoa"), CLOCK);

        assertThat(cancelled.state().status()).isEqualTo(InstanceStatus.CANCELLED);
        assertThat(effects(cancelled, Effect.CancelJob.class)).hasSize(1);
        assertThat(effects(cancelled, Effect.DeleteSubscription.class)).hasSize(2);
    }

    @Test
    void PRE_293_a_condition_that_is_true_on_arrival_wins_at_once_and_the_other_catches_never_wait() {
        String xml = HEAD + """
                <bpmn:startEvent id="start"/>
                <bpmn:eventBasedGateway id="gw"/>
                <bpmn:intermediateCatchEvent id="limit-ok">
                  <bpmn:conditionalEventDefinition><bpmn:condition>limit &gt; 0</bpmn:condition></bpmn:conditionalEventDefinition>
                </bpmn:intermediateCatchEvent>
                <bpmn:receiveTask id="wait-docs" messageRef="M_paid" horus:correlationKey="orderId"/>
                <bpmn:endEvent id="a"/>
                <bpmn:endEvent id="b"/>
                <bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="gw"/>
                <bpmn:sequenceFlow id="f2" sourceRef="gw" targetRef="limit-ok"/>
                <bpmn:sequenceFlow id="f3" sourceRef="gw" targetRef="wait-docs"/>
                <bpmn:sequenceFlow id="f4" sourceRef="limit-ok" targetRef="a"/>
                <bpmn:sequenceFlow id="f5" sourceRef="wait-docs" targetRef="b"/>
                """ + TAIL;
        Transition started = start(kernel(xml), Map.of("limit", 5000, "orderId", "ORD-1"));

        assertThat(started.state().status()).isEqualTo(InstanceStatus.COMPLETED);
        assertThat(effects(started, Effect.CreateSubscription.class)).isEmpty();
        assertThat(history(started, "EVENT_GATEWAY_RESOLVED").get(0).details()).containsEntry("winner", "limit-ok");
    }

    @Test
    void PRE_295_the_new_boundaries_and_the_gateway_export_and_read_back_to_the_same_definition() {
        for (String xml : List.of(review(messageBoundary("cancelActivity=\"false\"")), review(signalBoundary("")),
                screening("cancelActivity=\"false\""), PAYMENT_WAIT)) {
            Process original = read(xml);

            Process again = read(new String(new SubsetBpmnWriter().write(original), StandardCharsets.UTF_8));

            assertThat(io.github.dhluong90.bpm.process.internal.json.CanonicalProcessWriter.canonicalText(again))
                    .isEqualTo(io.github.dhluong90.bpm.process.internal.json.CanonicalProcessWriter.canonicalText(original));
        }
    }

    @Test
    void PRE_295_a_message_boundary_needs_its_key_and_a_gateway_must_lead_to_catch_events_that_only_it_enters() {
        // A message boundary without a correlation key
        String noKey = review("""
                <bpmn:boundaryEvent id="watch" attachedToRef="review">
                  <bpmn:messageEventDefinition messageRef="M_cancel"/>
                </bpmn:boundaryEvent>
                """);
        assertThatThrownBy(() -> read(noKey)).isInstanceOf(ValidationFailedException.class)
                .hasMessageContaining("needs horus:correlationKey");

        // A gateway that leads to a task, and a catch event with a second incoming flow
        String toTask = PAYMENT_WAIT.replace("targetRef=\"frozen\"", "targetRef=\"settled\"");
        assertThatThrownBy(() -> kernel(toTask)).isInstanceOf(ValidationFailedException.class);
        String shared = PAYMENT_WAIT.replace("</bpmn:process>",
                "<bpmn:sequenceFlow id=\"f8\" sourceRef=\"start\" targetRef=\"paid\"/></bpmn:process>");
        assertThatThrownBy(() -> kernel(shared)).isInstanceOf(ValidationFailedException.class);
    }

    @Test
    void PRE_295_a_parallel_event_based_gateway_is_refused_with_its_stage() {
        String instantiating = PAYMENT_WAIT.replace("<bpmn:eventBasedGateway id=\"gw\"/>",
                "<bpmn:eventBasedGateway id=\"gw\" eventGatewayType=\"Parallel\"/>");

        assertThatThrownBy(() -> read(instantiating)).isInstanceOf(BpmnUnsupportedElementException.class)
                .hasMessageContaining("S3");
    }

    @Test
    void PRE_295_the_worked_example_file_reads_compiles_and_waits_where_it_should() throws Exception {
        byte[] file;
        try (java.io.InputStream in = getClass().getResourceAsStream("/bpmn/payment-review.bpmn")) {
            file = in.readAllBytes();
        }
        Process process = reader.read(file);
        CompiledProcess compiled = new ProcessCompiler(BehaviorCatalog.registry(), 500).compile("D1", process);
        DefaultExecutionKernel kernel = new DefaultExecutionKernel(compiled, BehaviorCatalog.registry(),
                new V1ExpressionEvaluator(100));

        Transition started = start(kernel, Map.of("paymentId", "PAY-4001", "riskScore", 20));

        assertThat(on(started.state(), "review").status()).isEqualTo(TokenStatus.WAITING_USER);
        assertThat(effects(started, Effect.CreateSubscription.class)).extracting(Effect.CreateSubscription::kind)
                .containsExactlyInAnyOrder("MESSAGE", "SIGNAL");
    }
}
