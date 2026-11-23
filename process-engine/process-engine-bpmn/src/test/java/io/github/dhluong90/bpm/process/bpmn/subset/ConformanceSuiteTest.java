package io.github.dhluong90.bpm.process.bpmn.subset;

import static org.assertj.core.api.Assertions.assertThat;

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
import io.github.dhluong90.bpm.process.internal.exec.Transition;
import io.github.dhluong90.bpm.process.internal.expr.V1ExpressionEvaluator;
import io.github.dhluong90.bpm.process.internal.json.CanonicalProcessWriter;
import io.github.dhluong90.bpm.process.internal.model.bpmn.Process;
import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Conformance suite (#499): each file under {@code bpmn/conformance} is imported, compiled and run in memory; the test
 * checks the path the token takes and the end state, and that export then import keeps the element and flow count and
 * the definition hash. The other supported elements have their own scenario files in the sibling tests of this package
 * (gateways, events, sub-processes, sagas, loops, ad-hoc); the table in the import guide lists which test covers which.
 */
class ConformanceSuiteTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-23T03:00:00Z"), ZoneOffset.UTC);
    private final SubsetBpmnReader reader = new SubsetBpmnReader(Limits.DEFAULTS);

    private record Outcome(InstanceState state, Set<String> visited) { }

    private Process read(String file) throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/bpmn/conformance/" + file)) {
            return reader.read(in.readAllBytes());
        }
    }

    /** Runs the process, answering every call and every task and timer as it appears, until nothing waits. */
    private Outcome run(Process process, Map<String, Object> variables, Map<String, Map<String, Object>> answers) {
        CompiledProcess compiled = new ProcessCompiler(BehaviorCatalog.registry(), 500).compile("D1", process);
        DefaultExecutionKernel kernel = new DefaultExecutionKernel(compiled, BehaviorCatalog.registry(),
                new V1ExpressionEvaluator(100));
        Transition t = kernel.apply(InstanceState.created("01JD0000000000000000000499", "bank-a", "D1", "K-499"),
                new Command.Start(variables, "officer.hoa"), CLOCK);
        Set<String> visited = new LinkedHashSet<>();
        List<Effect> pending = new ArrayList<>(t.effects());
        record Seen(Set<String> done) { }
        Set<String> handled = new LinkedHashSet<>();
        collect(t, visited);
        boolean progressed = true;
        while (progressed && t.state().status() == InstanceStatus.RUNNING) {
            progressed = false;
            for (Effect effect : new ArrayList<>(pending)) {
                Command next = switch (effect) {
                    case Effect.InvokeService call when handled.add(call.attemptId()) -> new Command.AsyncResult(
                            call.tokenId(), call.attemptId(), new StepResult.Completed(
                                    answers.getOrDefault(call.nodeId(), Map.of()), Map.of()));
                    case Effect.CreateUserTask task when handled.add(task.taskId()) -> new Command.CompleteTask(
                            task.taskId(), task.tokenId(), answers.getOrDefault(task.nodeId(), Map.of()), "officer.lan");
                    case Effect.ScheduleJob job when handled.add(job.jobId()) -> new Command.JobDue(job.jobId(),
                            job.tokenId(), job.jobKind());
                    default -> null;
                };
                if (next != null) {
                    t = kernel.apply(t.state(), next, CLOCK);
                    pending.addAll(t.effects());
                    collect(t, visited);
                    progressed = true;
                    break;
                }
            }
        }
        return new Outcome(t.state(), visited);
    }

    private static void collect(Transition t, Set<String> visited) {
        for (HistoryEntry h : t.history()) {
            if (h.nodeId() != null) {
                visited.add(h.nodeId());
            }
        }
    }

    @Test
    void a_data_output_association_leaves_the_result_in_the_data_object_and_a_data_input_reads_it() throws IOException {
        Process process = read("data-objects.bpmn");

        Outcome out = run(process, Map.of("creditScore", 0, "profile", Map.of("name", "Nguyen Van An")),
                Map.of("score", Map.of("score", 712, "band", "B")));

        assertThat(out.state().status()).isEqualTo(InstanceStatus.COMPLETED);
        assertThat(out.state().variables()).containsEntry("creditScore", 712);
        assertThat(out.visited()).contains("start", "score", "end");
    }

    @Test
    void an_exclusive_gateway_takes_the_matching_flow_and_otherwise_its_default() throws IOException {
        Process process = read("gateways.bpmn");

        Outcome large = run(process, Map.of("amount", 2_500_000), Map.of());
        Outcome small = run(process, Map.of("amount", 40_000), Map.of());

        assertThat(large.state().status()).isEqualTo(InstanceStatus.COMPLETED);
        assertThat(large.visited()).contains("large").doesNotContain("small");
        assertThat(large.state().variables()).containsEntry("route", "large");
        assertThat(small.visited()).contains("small").doesNotContain("large");
        assertThat(small.state().variables()).containsEntry("route", "small");
    }

    @Test
    void a_parallel_join_waits_for_both_branches_before_the_account_is_opened() throws IOException {
        Outcome out = run(read("parallel-join.bpmn"), Map.of(), Map.of());

        assertThat(out.state().status()).isEqualTo(InstanceStatus.COMPLETED);
        assertThat(out.visited()).contains("id-check", "aml-check", "join", "open-account", "end");
    }

    @Test
    void a_sub_process_runs_to_its_own_end_and_a_timer_catch_waits_then_goes_on() throws IOException {
        Outcome out = run(read("sub-process-and-timer.bpmn"), Map.of(), Map.of());

        assertThat(out.state().status()).isEqualTo(InstanceStatus.COMPLETED);
        assertThat(out.visited()).contains("print", "emboss", "cooling-off", "end");
    }

    @Test
    void every_conformance_file_keeps_its_element_flow_count_and_hash_through_export_and_import() throws IOException {
        for (String file : List.of("data-objects.bpmn", "gateways.bpmn", "parallel-join.bpmn",
                "sub-process-and-timer.bpmn")) {
            Process source = read(file);

            Process again = reader.read(new SubsetBpmnWriter().write(source));

            assertThat(again.allFlowElements()).as(file).hasSameSizeAs(source.allFlowElements());
            assertThat(CanonicalProcessWriter.canonicalText(again)).as(file)
                    .isEqualTo(CanonicalProcessWriter.canonicalText(source));
        }
    }
}
