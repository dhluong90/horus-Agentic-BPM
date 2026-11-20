package io.github.dhluong90.bpm.process.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.process.api.DefinitionProblem;
import io.github.dhluong90.bpm.process.api.ValidationFailedException;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * PRE-005 and PRE-044 on the kernel, in memory: a loan application forks into a credit bureau check and a fraud
 * check, which join at {@code join-checks} before underwriting. The race across replicas is proven on PostgreSQL in
 * {@code ParallelJoinPostgresIT}; these tests fix the counting rule the lock serialises.
 */
class ParallelGatewayTest {

    private static final String INSTANCE = "01JD0000000000000000000000";
    private final TestClock clock = TestClock.at("2027-01-04T09:00:00Z");

    /** start, fork-checks, credit-bureau and fraud-check (user tasks), join-checks, underwrite (user task), end. */
    static Process loanChecks() {
        return BpmnModelBuilder.process("loan-checks")
                .startEvent("start")
                .parallelGateway("fork-checks")
                .userTask("credit-bureau", "role:CREDIT_ANALYST", "bureau")
                .userTask("fraud-check", "role:FRAUD_ANALYST", "fraud")
                .parallelGateway("join-checks")
                .userTask("underwrite", "role:UNDERWRITER", "underwriting")
                .endEvent("end")
                .chain("start", "fork-checks")
                .chain("fork-checks", "credit-bureau", "join-checks")
                .chain("fork-checks", "fraud-check", "join-checks")
                .chain("join-checks", "underwrite", "end")
                .build();
    }

    private static DefaultExecutionKernel kernel(Process process) {
        CompiledProcess compiled = new ProcessCompiler(BehaviorCatalog.registry(), 500).compile("D1", process);
        return new DefaultExecutionKernel(compiled, BehaviorCatalog.registry(), new V1ExpressionEvaluator(100));
    }

    private ScenarioRunner started() {
        return ScenarioRunner.of(kernel(loanChecks()), INSTANCE, "bank-a", "D1", clock)
                .start(Map.of("applicationId", "LN-2027-000123"));
    }

    @Test
    void PRE_005_fork_creates_one_token_per_branch_under_the_root_scope_token() {
        ScenarioRunner run = started();

        Token root = run.state().tokens().stream().filter(t -> t.kind() == TokenKind.SCOPE).findFirst().orElseThrow();
        assertThat(flowTokens(run.state())).extracting(Token::nodeId, Token::status, Token::scopeTokenId)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("credit-bureau", TokenStatus.WAITING_USER, root.id()),
                        org.assertj.core.groups.Tuple.tuple("fraud-check", TokenStatus.WAITING_USER, root.id()));
        assertThat(run.openTasks()).extracting(Effect.CreateUserTask::nodeId)
                .containsExactly("credit-bureau", "fraud-check");
    }

    @Test
    void PRE_005_the_first_branch_to_finish_waits_at_the_join() {
        ScenarioRunner run = started().completeTask("credit-bureau", Map.of("score", 712), "analyst-an");

        run.assertTokenAt("join-checks", TokenStatus.JOINED);
        run.assertTokenAt("fraud-check", TokenStatus.WAITING_USER);
        assertThat(flowTokens(run.state())).noneMatch(t -> t.nodeId().equals("underwrite"));
        assertThat(run.openTasks()).extracting(Effect.CreateUserTask::nodeId).containsExactly("fraud-check");
    }

    @Test
    void PRE_005_the_last_branch_fires_the_join_exactly_once() {
        ScenarioRunner run = started()
                .completeTask("credit-bureau", Map.of("score", 712), "analyst-an")
                .completeTask("fraud-check", Map.of("flagged", false), "analyst-binh");

        assertThat(flowTokens(run.state())).filteredOn(t -> t.status().live()).singleElement()
                .satisfies(t -> {
                    assertThat(t.nodeId()).isEqualTo("underwrite");
                    assertThat(t.status()).isEqualTo(TokenStatus.WAITING_USER);
                });
        assertThat(flowTokens(run.state())).filteredOn(t -> !t.status().live())
                .extracting(Token::status).containsOnly(TokenStatus.COMPLETED).hasSize(2);
        assertThat(run.openTasks()).extracting(Effect.CreateUserTask::nodeId).containsExactly("underwrite");
        assertThat(entries(run, "TOKEN_JOINED", "join-checks")).extracting(h -> h.details().get("arrived"))
                .containsExactly(1, 2);
        assertThat(entries(run, "NODE_COMPLETED", "join-checks")).hasSize(1);
    }

    @Test
    void PRE_005_either_completion_order_gives_the_same_single_join() {
        ScenarioRunner run = started()
                .completeTask("fraud-check", Map.of("flagged", false), "analyst-binh")
                .completeTask("credit-bureau", Map.of("score", 712), "analyst-an");

        assertThat(flowTokens(run.state())).filteredOn(t -> t.status().live()).extracting(Token::nodeId)
                .containsExactly("underwrite");
        run.completeTask("underwrite", Map.of("approved", true), "underwriter-chi").assertCompleted();
    }

    @Test
    void PRE_005_synchronous_branches_join_in_the_same_transaction() {
        // Given both checks as local service tasks: the fork and the join happen within one apply
        Process sync = BpmnModelBuilder.process("loan-checks-sync")
                .startEvent("start")
                .parallelGateway("fork-checks")
                .serviceTask("credit-bureau", "bureau.check", Map.of(), "bureau")
                .serviceTask("fraud-check", "fraud.check", Map.of(), "fraud")
                .parallelGateway("join-checks")
                .endEvent("end")
                .chain("start", "fork-checks")
                .chain("fork-checks", "credit-bureau", "join-checks")
                .chain("fork-checks", "fraud-check", "join-checks")
                .chain("join-checks", "end")
                .build();

        ScenarioRunner run = ScenarioRunner.of(kernel(sync), INSTANCE, "bank-a", "D1", clock)
                .onInvoke("bureau.check", in -> new StepResult.Completed(Map.of("score", 712)))
                .onInvoke("fraud.check", in -> new StepResult.Completed(Map.of("flagged", false)))
                .start(Map.of());

        run.assertCompleted();
        assertThat(entries(run, "NODE_COMPLETED", "join-checks")).hasSize(1);
        assertThat(run.history()).filteredOn(h -> "INSTANCE_ENDED".equals(h.type())).hasSize(1);
    }

    @Test
    void PRE_005_three_branches_join_only_when_all_three_arrived() {
        Process three = BpmnModelBuilder.process("loan-checks-three")
                .startEvent("start")
                .parallelGateway("fork-checks")
                .userTask("credit-bureau", "role:CREDIT_ANALYST", "bureau")
                .userTask("fraud-check", "role:FRAUD_ANALYST", "fraud")
                .userTask("collateral", "role:COLLATERAL_OFFICER", "collateralValue")
                .parallelGateway("join-checks")
                .endEvent("end")
                .chain("start", "fork-checks")
                .chain("fork-checks", "credit-bureau", "join-checks")
                .chain("fork-checks", "fraud-check", "join-checks")
                .chain("fork-checks", "collateral", "join-checks")
                .chain("join-checks", "end")
                .build();

        ScenarioRunner run = ScenarioRunner.of(kernel(three), INSTANCE, "bank-a", "D1", clock).start(Map.of())
                .completeTask("credit-bureau", Map.of(), "analyst-an")
                .completeTask("collateral", Map.of(), "officer-dung");
        assertThat(run.state().status()).isEqualTo(InstanceStatus.RUNNING);
        assertThat(entries(run, "NODE_COMPLETED", "join-checks")).isEmpty();

        run.completeTask("fraud-check", Map.of(), "analyst-binh").assertCompleted();
        assertThat(entries(run, "NODE_COMPLETED", "join-checks")).hasSize(1);
    }

    @Test
    void PRE_005_a_condition_on_a_parallel_outgoing_flow_is_refused_at_activation() {
        Process conditional = BpmnModelBuilder.process("loan-checks-conditional")
                .startEvent("start")
                .parallelGateway("fork-checks")
                .userTask("credit-bureau", "role:CREDIT_ANALYST", "bureau")
                .userTask("fraud-check", "role:FRAUD_ANALYST", "fraud")
                .endEvent("end")
                .chain("start", "fork-checks")
                .flow("fork-checks__bureau", "fork-checks", "credit-bureau", "amount >= 100000000 VND")
                .flow("fork-checks__fraud", "fork-checks", "fraud-check")
                .chain("credit-bureau", "end")
                .chain("fraud-check", "end")
                .build();

        assertThatThrownBy(() -> kernel(conditional))
                .isInstanceOf(ValidationFailedException.class)
                .satisfies(e -> assertThat(((ValidationFailedException) e).problems())
                        .extracting(DefinitionProblem::code, DefinitionProblem::elementId)
                        .containsExactly(org.assertj.core.groups.Tuple.tuple("CONDITION_ON_PARALLEL_FLOW",
                                "fork-checks__bureau")));
    }

    @Test
    void PRE_044_the_join_counts_only_tokens_of_the_arriving_token_scope() {
        // Given the fork done under the root scope token, and a token already JOINED at join-checks under another
        // scope token (as a saga block or subprocess holding the same gateway id would leave it)
        DefaultExecutionKernel kernel = kernel(loanChecks());
        ScenarioRunner run = started();
        InstanceState forked = run.state();
        Token root = forked.tokens().stream().filter(t -> t.kind() == TokenKind.SCOPE).findFirst().orElseThrow();
        Token otherScope = new Token(INSTANCE + "-S", TokenKind.SCOPE, TokenStatus.WAITING_SCOPE, root.id(), "disburse",
                null, Map.of());
        Token foreign = new Token(INSTANCE + "-X", TokenKind.FLOW, TokenStatus.JOINED, otherScope.id(), "join-checks",
                null, Map.of());
        List<Token> tokens = new ArrayList<>(forked.tokens());
        tokens.add(otherScope);
        tokens.add(foreign);
        InstanceState state = new InstanceState(forked.instanceId(), forked.tenantId(), forked.definitionId(),
                forked.businessKey(), forked.status(), tokens, forked.variables(), forked.participants(),
                forked.sequence());

        // When the first root branch arrives
        Transition first = kernel.apply(state, complete(run, "credit-bureau"), clock);

        // Then the foreign JOINED token does not complete the set
        assertThat(first.state().tokens()).noneMatch(t -> t.nodeId().equals("underwrite"));
        assertThat(first.state().tokens()).filteredOn(t -> t.id().equals(foreign.id())).singleElement()
                .satisfies(t -> assertThat(t.status()).isEqualTo(TokenStatus.JOINED));

        // When the second root branch arrives, Then exactly one token leaves, and the foreign token is untouched
        Transition second = kernel.apply(first.state(), complete(run, "fraud-check"), clock);
        assertThat(second.state().tokens()).filteredOn(t -> t.nodeId().equals("underwrite")).singleElement()
                .satisfies(t -> assertThat(t.scopeTokenId()).isEqualTo(root.id()));
        assertThat(second.state().tokens()).filteredOn(t -> t.id().equals(foreign.id())).singleElement()
                .satisfies(t -> assertThat(t.status()).isEqualTo(TokenStatus.JOINED));
    }

    @Test
    void PRE_044_two_instances_of_one_definition_share_no_token() {
        ScenarioRunner first = started();
        ScenarioRunner second = ScenarioRunner.of(kernel(loanChecks()), "01JD0000000000000000000001", "bank-a", "D1",
                clock).start(Map.of("applicationId", "LN-2027-000124"));

        assertThat(first.state().tokens()).extracting(Token::id)
                .doesNotContainAnyElementsOf(second.state().tokens().stream().map(Token::id).toList());
    }

    private static Command.CompleteTask complete(ScenarioRunner run, String nodeId) {
        Effect.CreateUserTask task = run.openTasks().stream().filter(t -> t.nodeId().equals(nodeId)).findFirst()
                .orElseThrow();
        return new Command.CompleteTask(task.taskId(), task.tokenId(), Map.of(), "analyst-an");
    }

    private static List<Token> flowTokens(InstanceState state) {
        return state.tokens().stream().filter(t -> t.kind() == TokenKind.FLOW).toList();
    }

    private static List<HistoryEntry> entries(ScenarioRunner run, String type, String nodeId) {
        return run.history().stream().filter(h -> type.equals(h.type()) && nodeId.equals(h.nodeId())).toList();
    }
}
