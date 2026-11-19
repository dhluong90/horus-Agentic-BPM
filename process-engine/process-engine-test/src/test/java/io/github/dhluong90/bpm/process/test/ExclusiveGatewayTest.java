package io.github.dhluong90.bpm.process.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.horus.bpm.rules.api.Money;
import io.github.dhluong90.bpm.process.api.DefinitionProblem;
import io.github.dhluong90.bpm.process.api.ValidationFailedException;
import io.github.dhluong90.bpm.process.internal.exec.BehaviorCatalog;
import io.github.dhluong90.bpm.process.internal.exec.CompiledProcess;
import io.github.dhluong90.bpm.process.internal.exec.DefaultExecutionKernel;
import io.github.dhluong90.bpm.process.internal.exec.Effect;
import io.github.dhluong90.bpm.process.internal.exec.ExecutionException;
import io.github.dhluong90.bpm.process.internal.exec.HistoryEntry;
import io.github.dhluong90.bpm.process.internal.exec.ProcessCompiler;
import io.github.dhluong90.bpm.process.internal.exec.TokenKind;
import io.github.dhluong90.bpm.process.internal.exec.TokenStatus;
import io.github.dhluong90.bpm.process.internal.expr.V1ExpressionEvaluator;
import io.github.dhluong90.bpm.process.internal.model.bpmn.Expression;
import io.github.dhluong90.bpm.process.internal.model.bpmn.FlowElement;
import io.github.dhluong90.bpm.process.internal.model.bpmn.Process;
import io.github.dhluong90.bpm.process.internal.model.bpmn.SequenceFlow;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * PRE-004: the exclusive gateway of a loan origination routes to exactly one branch, the same one every time for the
 * same amount. Threshold 100,000,000 VND: at or above goes to senior approval, below takes the default flow to the
 * standard approval.
 */
class ExclusiveGatewayTest {

    private static final String INSTANCE = "01JD0000000000000000000000";
    private final TestClock clock = TestClock.at("2027-01-04T09:00:00Z");

    static BpmnModelBuilder loanOrigination(String defaultFlow) {
        return BpmnModelBuilder.process("loan-origination")
                .startEvent("start")
                .exclusiveGateway("amount-check", defaultFlow)
                .userTask("senior-approval", "role:SENIOR_CREDIT_OFFICER", "seniorReview")
                .userTask("auto-approve", "role:CREDIT_OFFICER", "review")
                .endEvent("end")
                .chain("start", "amount-check")
                .flow("amount-check__senior", "amount-check", "senior-approval", "amount >= 100000000 VND")
                .chain("senior-approval", "end")
                .chain("auto-approve", "end");
    }

    private static Process withDefault() {
        return loanOrigination("amount-check__auto").flow("amount-check__auto", "amount-check", "auto-approve").build();
    }

    private ScenarioRunner run(Process process, Map<String, Object> variables) {
        CompiledProcess compiled = new ProcessCompiler(BehaviorCatalog.registry(), 500).compile("D1", process);
        return ScenarioRunner.of(new DefaultExecutionKernel(compiled, BehaviorCatalog.registry(),
                new V1ExpressionEvaluator(100)), INSTANCE, "bank-a", "D1", clock).start(variables);
    }

    @Test
    void PRE_004_amount_above_the_threshold_activates_only_senior_approval() {
        // Given the gateway with a senior branch and a default; When 150,000,000 VND arrives
        ScenarioRunner run = run(withDefault(), Map.of("amount", Money.ofMinor("VND", 150_000_000)));

        // Then one flow token waits at senior-approval, and auto-approve is never entered
        assertThat(run.state().tokens()).filteredOn(t -> t.kind() == TokenKind.FLOW).singleElement()
                .satisfies(t -> {
                    assertThat(t.nodeId()).isEqualTo("senior-approval");
                    assertThat(t.status()).isEqualTo(TokenStatus.WAITING_USER);
                });
        assertThat(run.openTasks()).extracting(Effect.CreateUserTask::nodeId).containsExactly("senior-approval");
        assertThat(run.history()).filteredOn(h -> "auto-approve".equals(h.nodeId())).isEmpty();
        assertThat(gatewayChoice(run)).isEqualTo("amount-check__senior");
    }

    @Test
    void PRE_004_amount_below_the_threshold_takes_the_default_to_auto_approve() {
        // When 99,999,999 VND arrives, one dong under the threshold
        ScenarioRunner run = run(withDefault(), Map.of("amount", Money.ofMinor("VND", 99_999_999)));

        // Then only the default branch is activated
        assertThat(run.openTasks()).extracting(Effect.CreateUserTask::nodeId).containsExactly("auto-approve");
        assertThat(run.history()).filteredOn(h -> "senior-approval".equals(h.nodeId())).isEmpty();
        assertThat(gatewayChoice(run)).isEqualTo("amount-check__auto");
    }

    @ParameterizedTest(name = "{0} VND -> {1}")
    @CsvSource({
        "0, amount-check__auto",
        "1, amount-check__auto",
        "99999999, amount-check__auto",
        "100000000, amount-check__senior",
        "100000001, amount-check__senior",
        "150000000, amount-check__senior",
        "9000000000000000, amount-check__senior"
    })
    void PRE_004_same_amount_takes_the_same_branch_on_every_run(long minor, String expectedFlow) {
        // When the same amount is routed three times on fresh instances
        List<List<String>> histories = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            ScenarioRunner run = run(withDefault(), Map.of("amount", Money.ofMinor("VND", minor)));
            assertThat(gatewayChoice(run)).isEqualTo(expectedFlow);
            histories.add(run.history().stream().map(h -> h.type() + "@" + h.nodeId() + "#" + h.tokenId()).toList());
        }

        // Then the branch and the whole history are identical
        assertThat(histories).allSatisfy(h -> assertThat(h).isEqualTo(histories.get(0)));
    }

    @Test
    void PRE_004_no_default_and_no_true_condition_refuses_the_start_with_NO_PATH() {
        // Given the same gateway whose second branch also has a condition, and no default
        Process noDefault = loanOrigination(null)
                .flow("amount-check__auto", "amount-check", "auto-approve", "amount < 50000000 VND").build();

        // When 70,000,000 VND arrives, which neither condition accepts; Then the step fails with NO_PATH
        assertThatThrownBy(() -> run(noDefault, Map.of("amount", Money.ofMinor("VND", 70_000_000))))
                .isInstanceOf(ExecutionException.class)
                .satisfies(e -> {
                    assertThat(((ExecutionException) e).code()).isEqualTo("NO_PATH");
                    assertThat(((ExecutionException) e).nodeId()).isEqualTo("amount-check");
                });
    }

    @Test
    void PRE_004_a_non_default_flow_with_an_empty_condition_is_refused_at_activation() {
        // Given a diverging gateway whose second non-default flow carries a condition element with no text
        List<FlowElement> elements = new ArrayList<>(loanOrigination(null)
                .flow("amount-check__auto", "amount-check", "auto-approve").build().flowElements());
        elements.replaceAll(e -> e instanceof SequenceFlow f && f.id().equals("amount-check__auto")
                ? new SequenceFlow(f.id(), f.name(), f.sourceRef(), f.targetRef(),
                        new Expression(null, BpmnModelBuilder.EXPR_V1, null), null, null)
                : e);
        Process process = withElements(elements);

        // When it is compiled; Then it is refused, so no instance can ever evaluate an empty condition
        assertThatThrownBy(() -> new ProcessCompiler(BehaviorCatalog.registry(), 500).compile("D1", process))
                .isInstanceOf(ValidationFailedException.class)
                .satisfies(e -> assertThat(((ValidationFailedException) e).problems())
                        .extracting(DefinitionProblem::code, DefinitionProblem::elementId)
                        .contains(org.assertj.core.groups.Tuple.tuple("CONDITION_MISSING", "amount-check__auto")));
    }

    private static Process withElements(List<FlowElement> elements) {
        Process p = loanOrigination(null).flow("amount-check__auto", "amount-check", "auto-approve").build();
        return new Process(p.id(), p.name(), p.processType(), p.isExecutable(), p.isClosed(), elements,
                p.artifacts(), p.laneSets(), p.info());
    }

    private static String gatewayChoice(ScenarioRunner run) {
        return run.history().stream()
                .filter(h -> "NODE_COMPLETED".equals(h.type()) && "amount-check".equals(h.nodeId()))
                .map(HistoryEntry::details).map(d -> String.valueOf(d.get("flow")))
                .reduce((a, b) -> {
                    throw new AssertionError("the gateway completed twice: " + a + ", " + b);
                }).orElseThrow();
    }
}
