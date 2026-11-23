package io.github.dhluong90.bpm.process.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.process.internal.exec.BehaviorCatalog;
import io.github.dhluong90.bpm.process.internal.exec.CompiledProcess;
import io.github.dhluong90.bpm.process.internal.exec.DefaultExecutionKernel;
import io.github.dhluong90.bpm.process.internal.exec.ExecutionException;
import io.github.dhluong90.bpm.process.internal.exec.ProcessCompiler;
import io.github.dhluong90.bpm.process.internal.expr.V1ExpressionEvaluator;
import io.github.dhluong90.bpm.process.internal.model.bpmn.ActivityAttributes;
import io.github.dhluong90.bpm.process.internal.model.bpmn.ElementInfo;
import io.github.dhluong90.bpm.process.internal.model.bpmn.ExtensionBag;
import io.github.dhluong90.bpm.process.internal.model.bpmn.ManualTask;
import io.github.dhluong90.bpm.process.internal.model.bpmn.Process;
import io.github.dhluong90.bpm.process.internal.model.bpmn.ScriptTask;
import io.github.dhluong90.bpm.process.internal.model.bpmn.Task;
import io.github.dhluong90.bpm.process.internal.model.horus.ResultVariable;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * PRE-250 to PRE-253: plain, manual and script tasks (engine semantics). A card fee review runs a script that doubles the amount,
 * and a script cannot reach beyond the expression language.
 */
class TaskElementsTest {

    private static final String SCRIPT_FORMAT = "urn:horus-bpm:expr:v1";
    private final TestClock clock = TestClock.at("2027-01-04T09:00:00Z");

    private static ScriptTask script(String id, String text) {
        return new ScriptTask(id, null, List.of(), List.of(), ActivityAttributes.defaults(), SCRIPT_FORMAT, text,
                new ElementInfo(List.of(), new ExtensionBag(List.of(new ResultVariable("result")), List.of()), Map.of()));
    }

    private static Process process(io.github.dhluong90.bpm.process.internal.model.bpmn.FlowNode... middle) {
        BpmnModelBuilder b = BpmnModelBuilder.process("card-fee-review").startEvent("start");
        String[] ids = new String[middle.length + 2];
        ids[0] = "start";
        for (int i = 0; i < middle.length; i++) {
            b.node(middle[i]);
            ids[i + 1] = middle[i].id();
        }
        b.endEvent("end");
        ids[ids.length - 1] = "end";
        return b.chain(ids).build();
    }

    private ScenarioRunner run(Process process, Map<String, Object> variables) {
        CompiledProcess compiled = new ProcessCompiler(BehaviorCatalog.registry(), 500).compile("D1", process);
        return ScenarioRunner.of(new DefaultExecutionKernel(compiled, BehaviorCatalog.registry(),
                new V1ExpressionEvaluator(100)), "01JD0000000000000000000000", "bank-a", "D1", clock).start(variables);
    }

    @Test
    void PRE_250_a_script_task_stores_its_value_in_the_result_variable_and_the_timeline_names_it() {
        // Given a script "amount * 2" and a card fee of 1,500,000 VND in minor units of an amount
        ScenarioRunner run = run(process(script("double-fee", "amount * 2")), Map.of("amount", new BigDecimal("1500000")));

        // Then the instance ends with the product, and the history names the result variable
        run.assertCompleted();
        assertThat(run.state().variables().get("result")).isEqualTo(new BigDecimal("3000000"));
        assertThat(run.history()).filteredOn(h -> "double-fee".equals(h.nodeId()) && "NODE_COMPLETED".equals(h.type()))
                .singleElement().satisfies(h -> assertThat(h.details()).containsEntry("resultVariable", "result"));
    }

    @Test
    void PRE_251_a_manual_task_and_a_plain_task_pass_through_and_the_timeline_records_them() {
        Task plain = new Task("note", null, List.of(), List.of(), ActivityAttributes.defaults(), ElementInfo.empty());
        ManualTask manual = new ManualTask("post-letter", null, List.of(), List.of(), ActivityAttributes.defaults(),
                ElementInfo.empty());

        ScenarioRunner run = run(process(plain, manual), Map.of());

        run.assertCompleted();
        assertThat(run.history()).filteredOn(h -> "NODE_COMPLETED".equals(h.type()))
                .filteredOn(h -> "note".equals(h.nodeId()) || "post-letter".equals(h.nodeId()))
                .hasSize(2).allSatisfy(h -> assertThat(h.details()).containsEntry("by", "engine"));
    }

    @Test
    void PRE_253_a_script_cannot_call_host_code_or_name_a_host_class() {
        // A script that reaches for Java is refused when the definition is compiled, before any instance exists.
        for (String hostile : List.of("java.lang.Runtime.getRuntime().exec('rm -rf /')", "System.exit(0)",
                "amount.getClass().getName()", "load('file:///etc/passwd')", "while (true) {}", "amount = 0; amount")) {
            assertThatThrownBy(() -> new ProcessCompiler(BehaviorCatalog.registry(), 500).compile("D1",
                    process(script("evil", hostile))))
                    .as(hostile).isInstanceOf(RuntimeException.class);
        }
    }

    @Test
    void PRE_253_a_script_reads_variables_and_sets_only_its_result_variable() {
        ScenarioRunner run = run(process(script("tier", "amount >= 1000 and amount < 10000")),
                Map.of("amount", new BigDecimal("5000")));

        assertThat(run.state().variables()).containsOnlyKeys("amount", "result");
        assertThat(run.state().variables().get("amount")).isEqualTo(new BigDecimal("5000"));
        assertThat(run.state().variables().get("result")).isEqualTo(true);
    }

    @Test
    void PRE_253_a_script_over_its_step_budget_fails_the_step() {
        // nine groups of thirty terms: about 540 nodes, over the 500 steps a script may take, under the size limit
        String group = "(" + "1+".repeat(29) + "1)";
        String sum = String.join("+", java.util.Collections.nCopies(9, group));

        assertThatThrownBy(() -> run(process(script("big", sum)), Map.of()))
                .isInstanceOf(ExecutionException.class)
                .satisfies(e -> assertThat(((ExecutionException) e).code()).isEqualTo("SCRIPT_ERROR"));
    }

    @Test
    void PRE_253_a_script_longer_than_the_size_limit_is_refused_at_activation() {
        String tooLong = "1" + "+1".repeat(600);

        assertThatThrownBy(() -> new ProcessCompiler(BehaviorCatalog.registry(), 500).compile("D1",
                process(script("long", tooLong)))).isInstanceOf(RuntimeException.class);
    }
}
