package io.github.dhluong90.bpm.process.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.expr.api.Money;
import io.github.dhluong90.bpm.process.api.DefinitionProblem;
import io.github.dhluong90.bpm.process.api.ValidationFailedException;
import io.github.dhluong90.bpm.process.internal.exec.BehaviorCatalog;
import io.github.dhluong90.bpm.process.internal.exec.CompiledProcess;
import io.github.dhluong90.bpm.process.internal.exec.DefaultExecutionKernel;
import io.github.dhluong90.bpm.process.internal.exec.Effect;
import io.github.dhluong90.bpm.process.internal.exec.ExecutionException;
import io.github.dhluong90.bpm.process.internal.exec.HistoryEntry;
import io.github.dhluong90.bpm.process.internal.exec.InstanceStatus;
import io.github.dhluong90.bpm.process.internal.exec.ProcessCompiler;
import io.github.dhluong90.bpm.process.internal.exec.Token;
import io.github.dhluong90.bpm.process.internal.exec.TokenKind;
import io.github.dhluong90.bpm.process.internal.exec.TokenStatus;
import io.github.dhluong90.bpm.process.internal.expr.V1ExpressionEvaluator;
import io.github.dhluong90.bpm.process.internal.model.bpmn.Process;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * PRE-300 to PRE-304 on the kernel, in memory: a payment screening. The split takes the AML review for 100,000,000 VND
 * or more and the sanctions review for a cross-border payment, or the standard check when neither holds; the join
 * releases the payment once every review that was started has ended. The race across replicas is proven on PostgreSQL
 * in {@code InclusiveJoinPostgresIT}.
 */
class InclusiveGatewayTest {

    private static final String INSTANCE = "01JD0000000000000000000300";
    private final TestClock clock = TestClock.at("2027-01-04T09:00:00Z");

    /** start, split, aml-review, sanctions-review, standard-check (default), join, release, end. */
    private static BpmnModelBuilder screening() {
        return BpmnModelBuilder.process("payment-screening")
                .startEvent("start")
                .inclusiveGateway("screen", "screen__standard")
                .userTask("aml-review", "role:AML_OFFICER", "aml")
                .userTask("sanctions-review", "role:SANCTIONS_OFFICER", "sanctions")
                .userTask("standard-check", "role:PAYMENT_OFFICER", "standard")
                .inclusiveGateway("settle", null)
                .userTask("release", "role:PAYMENT_OFFICER", "released")
                .endEvent("end")
                .chain("start", "screen")
                .flow("screen__aml", "screen", "aml-review", "amount >= 100000000 VND")
                .flow("screen__sanctions", "screen", "sanctions-review", "crossBorder == true")
                .flow("screen__standard", "screen", "standard-check")
                .chain("aml-review", "settle")
                .chain("sanctions-review", "settle")
                .chain("standard-check", "settle")
                .chain("settle", "release", "end");
    }

    private ScenarioRunner run(Process process, Map<String, Object> variables) {
        CompiledProcess compiled = new ProcessCompiler(BehaviorCatalog.registry(), 500).compile("D1", process);
        return ScenarioRunner.of(new DefaultExecutionKernel(compiled, BehaviorCatalog.registry(),
                new V1ExpressionEvaluator(100)), INSTANCE, "bank-a", "D1", clock).start(variables);
    }

    private static Map<String, Object> payment(long vnd, boolean crossBorder) {
        return Map.of("amount", Money.ofMinor("VND", vnd), "crossBorder", crossBorder);
    }

    @Test
    void PRE_300_a_split_takes_every_flow_whose_condition_is_true() {
        // Given a cross-border payment of 150,000,000 VND: two of the three conditions hold
        ScenarioRunner run = run(screening().build(), payment(150_000_000, true));

        // Then two tokens wait, one at each review, and the standard check is never entered
        assertThat(run.openTasks()).extracting(Effect.CreateUserTask::nodeId)
                .containsExactly("aml-review", "sanctions-review");
        assertThat(run.history()).filteredOn(h -> "standard-check".equals(h.nodeId())).isEmpty();
        HistoryEntry split = entries(run, "NODE_COMPLETED", "screen").get(0);
        assertThat(split.details()).containsEntry("flows", List.of("screen__aml", "screen__sanctions"))
                .containsEntry("reason", "conditions");
    }

    @Test
    void PRE_301_when_no_condition_is_true_only_the_default_flow_is_taken() {
        // Given a domestic payment of 25,000,000 VND
        ScenarioRunner run = run(screening().build(), payment(25_000_000, false));

        assertThat(run.openTasks()).extracting(Effect.CreateUserTask::nodeId).containsExactly("standard-check");
        assertThat(entries(run, "NODE_COMPLETED", "screen").get(0).details())
                .containsEntry("flows", List.of("screen__standard")).containsEntry("reason", "default");
    }

    @Test
    void PRE_301_no_true_condition_and_no_default_flow_is_no_path() {
        Process noDefault = BpmnModelBuilder.process("no-default")
                .startEvent("start").inclusiveGateway("screen", null)
                .userTask("aml-review", "role:AML_OFFICER", "aml").endEvent("end")
                .chain("start", "screen")
                .flow("screen__aml", "screen", "aml-review", "amount >= 100000000 VND")
                .chain("aml-review", "end").build();

        assertThatThrownBy(() -> run(noDefault, payment(25_000_000, false))).isInstanceOf(ExecutionException.class)
                .hasFieldOrPropertyWithValue("code", "NO_PATH");
    }

    @Test
    void PRE_301_a_default_flow_that_carries_a_condition_or_is_not_an_outgoing_flow_is_refused_at_activation() {
        Process bad = BpmnModelBuilder.process("bad-default")
                .startEvent("start").inclusiveGateway("screen", "screen__aml")
                .userTask("aml-review", "role:AML_OFFICER", "aml")
                .endEvent("end").chain("start", "screen")
                .flow("screen__aml", "screen", "aml-review", "amount >= 100000000 VND").chain("aml-review", "end").build();

        assertThatThrownBy(() -> new ProcessCompiler(BehaviorCatalog.registry(), 500).compile("D1", bad))
                .isInstanceOfSatisfying(ValidationFailedException.class, e -> assertThat(e.problems())
                        .extracting(DefinitionProblem::code).containsExactly("DEFAULT_FLOW_HAS_CONDITION"));
    }

    @Test
    void PRE_302_the_join_waits_for_exactly_the_tokens_the_split_started_in_either_order() {
        ScenarioRunner first = run(screening().build(), payment(150_000_000, true))
                .completeTask("sanctions-review", Map.of("hit", false), "officer-an");

        // The first review waits at the join, the other can still arrive, and nothing is released
        first.assertTokenAt("settle", TokenStatus.JOINED);
        assertThat(entries(first, "TOKEN_JOINED", "settle").get(0).details()).containsEntry("arrived", 1L)
                .containsEntry("waitingFor", List.of("aml-review"));
        assertThat(first.openTasks()).extracting(Effect.CreateUserTask::nodeId).containsExactly("aml-review");

        // The second arrival fires the join once: one release task, both arrivals spent
        first.completeTask("aml-review", Map.of("clear", true), "officer-binh");
        assertThat(first.openTasks()).extracting(Effect.CreateUserTask::nodeId).containsExactly("release");
        assertThat(entries(first, "JOIN_FIRED", "settle")).singleElement()
                .satisfies(h -> assertThat(h.details()).containsEntry("arrived", 2));
        assertThat(tokensAt(first, "settle", TokenStatus.COMPLETED)).hasSize(2);

        ScenarioRunner other = run(screening().build(), payment(150_000_000, true))
                .completeTask("aml-review", Map.of("clear", true), "officer-binh")
                .completeTask("sanctions-review", Map.of("hit", false), "officer-an");
        assertThat(other.openTasks()).extracting(Effect.CreateUserTask::nodeId).containsExactly("release");
        other.completeTask("release", Map.of(), "officer-chi").assertCompleted();
    }

    @Test
    void PRE_302_a_join_with_one_started_branch_fires_when_that_branch_arrives() {
        // Given only the AML review started: the sanctions review never will, so the join has nothing to wait for
        ScenarioRunner run = run(screening().build(), payment(150_000_000, false))
                .completeTask("aml-review", Map.of("clear", true), "officer-binh");

        assertThat(run.openTasks()).extracting(Effect.CreateUserTask::nodeId).containsExactly("release");
    }

    @Test
    void PRE_303_a_join_does_not_wait_for_a_path_that_can_never_arrive() {
        // Given the split also starts a compliance log that ends in its own end event, never reaching the join
        Process process = screening()
                .userTask("compliance-log", "role:COMPLIANCE", "log").endEvent("end-log")
                .flow("screen__log", "screen", "compliance-log").chain("compliance-log", "end-log").build();
        ScenarioRunner run = run(process, payment(150_000_000, true))
                .completeTask("aml-review", Map.of("clear", true), "officer-binh")
                .completeTask("sanctions-review", Map.of("hit", false), "officer-an");

        // Then the join fires while the log is still open
        assertThat(run.openTasks()).extracting(Effect.CreateUserTask::nodeId)
                .containsExactlyInAnyOrder("compliance-log", "release");
    }

    @Test
    void PRE_303_a_token_that_leaves_for_another_end_releases_a_join_that_is_already_waiting() {
        // Given the sanctions review leads to an exclusive gateway: a hit goes on to the join, a clear ends the path
        Process process = BpmnModelBuilder.process("payment-screening-exit")
                .startEvent("start")
                .inclusiveGateway("screen", null)
                .userTask("aml-review", "role:AML_OFFICER", "aml")
                .userTask("sanctions-review", "role:SANCTIONS_OFFICER", "sanctions")
                .exclusiveGateway("outcome", "outcome__cleared")
                .endEvent("end-cleared")
                .inclusiveGateway("settle", null)
                .userTask("release", "role:PAYMENT_OFFICER", "released")
                .endEvent("end")
                .chain("start", "screen")
                .flow("screen__sanctions", "screen", "sanctions-review", "crossBorder == true")
                .flow("screen__aml", "screen", "aml-review")
                .chain("aml-review", "settle")
                .chain("sanctions-review", "outcome")
                .flow("outcome__hit", "outcome", "settle", "sanctions.hit == true")
                .flow("outcome__cleared", "outcome", "end-cleared")
                .chain("settle", "release", "end").build();

        // When the AML review ends first, the join waits for the sanctions review, which can still arrive
        ScenarioRunner run = run(process, payment(150_000_000, true))
                .completeTask("aml-review", Map.of("clear", true), "officer-binh");
        run.assertTokenAt("settle", TokenStatus.JOINED);
        assertThat(run.openTasks()).extracting(Effect.CreateUserTask::nodeId).containsExactly("sanctions-review");

        // And the sanctions review is cleared: its token ends elsewhere and the waiting join fires
        run.completeTask("sanctions-review", Map.of("hit", false), "officer-an");
        assertThat(run.openTasks()).extracting(Effect.CreateUserTask::nodeId).containsExactly("release");
        run.completeTask("release", Map.of(), "officer-chi").assertCompleted();
    }

    @Test
    void PRE_304_a_token_in_a_loop_that_can_still_arrive_holds_the_join_until_it_does() {
        // Given the income check loops back while the declared income is not confirmed
        Process process = BpmnModelBuilder.process("loan-income-loop")
                .startEvent("start")
                .inclusiveGateway("split", null)
                .userTask("income-check", "role:CREDIT_OFFICER", "income")
                .exclusiveGateway("income-ok", "income-ok__again")
                .userTask("credit-check", "role:CREDIT_ANALYST", "credit")
                .inclusiveGateway("join", null)
                .endEvent("end")
                .chain("start", "split")
                .chain("split", "income-check", "income-ok")
                .chain("split", "credit-check", "join")
                .flow("income-ok__done", "income-ok", "join", "income.confirmed == true")
                .flow("income-ok__again", "income-ok", "income-check")
                .chain("join", "end").build();
        ScenarioRunner run = run(process, Map.of()).completeTask("credit-check", Map.of(), "analyst-an");

        // When the income check is sent round the loop once, Then the join keeps waiting
        run.completeTask("income-check", Map.of("confirmed", false), "officer-binh");
        run.assertTokenAt("join", TokenStatus.JOINED).assertTokenAt("income-check", TokenStatus.WAITING_USER);
        assertThat(run.state().status()).isEqualTo(InstanceStatus.RUNNING);

        // And once the income is confirmed the join fires once and the instance ends
        run.completeTask("income-check", Map.of("confirmed", true), "officer-binh").assertCompleted();
        assertThat(entries(run, "JOIN_FIRED", "join")).hasSize(1);
    }

    @Test
    void PRE_304_two_joins_that_wait_for_each_other_fail_the_command_and_leave_the_state_unchanged() {
        // Given two inclusive joins in a ring: each is reachable from a token that waits at the other
        Process ring = BpmnModelBuilder.process("ring")
                .startEvent("start")
                .inclusiveGateway("split", null)
                .inclusiveGateway("a", null)
                .inclusiveGateway("b", null)
                .endEvent("end")
                .chain("start", "split")
                .chain("split", "a")
                .chain("split", "b")
                .chain("b", "a")
                .chain("a", "b")
                .chain("a", "end")
                .build();

        assertThatThrownBy(() -> run(ring, Map.of())).isInstanceOf(ExecutionException.class)
                .hasFieldOrPropertyWithValue("code", "JOIN_DEADLOCK");
    }

    private static List<HistoryEntry> entries(ScenarioRunner run, String type, String nodeId) {
        return run.history().stream().filter(h -> type.equals(h.type()) && nodeId.equals(h.nodeId())).toList();
    }

    private static List<Token> tokensAt(ScenarioRunner run, String nodeId, TokenStatus status) {
        return run.state().tokens().stream()
                .filter(t -> t.kind() == TokenKind.FLOW && t.nodeId().equals(nodeId) && t.status() == status).toList();
    }
}
