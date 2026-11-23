package io.github.dhluong90.bpm.process.bpmn.subset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.process.bpmn.xml.Limits;
import io.github.dhluong90.bpm.process.internal.exec.BehaviorCatalog;
import io.github.dhluong90.bpm.process.internal.exec.Command;
import io.github.dhluong90.bpm.process.internal.exec.CompiledProcess;
import io.github.dhluong90.bpm.process.internal.exec.DataStorePort;
import io.github.dhluong90.bpm.process.internal.exec.DefaultExecutionKernel;
import io.github.dhluong90.bpm.process.internal.exec.Effect;
import io.github.dhluong90.bpm.process.internal.exec.HistoryEntry;
import io.github.dhluong90.bpm.process.internal.exec.InstanceState;
import io.github.dhluong90.bpm.process.internal.exec.InstanceStatus;
import io.github.dhluong90.bpm.process.internal.exec.ProcessCompiler;
import io.github.dhluong90.bpm.process.internal.exec.StepResult;
import io.github.dhluong90.bpm.process.internal.exec.TokenStatus;
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
import java.util.HashMap;
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

    private record Outcome(InstanceState state, Set<String> visited, List<Effect> effects) { }

    private Process read(String file) throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/bpmn/conformance/" + file)) {
            return reader.read(in.readAllBytes());
        }
    }

    /** Runs the process, answering every call and every task and timer as it appears, until nothing waits. */
    private Outcome run(Process process, Map<String, Object> variables, Map<String, Map<String, Object>> answers) {
        return run(process, new Command.Start(variables, "officer.hoa"), answers, DataStorePort.NONE);
    }

    private Outcome run(Process process, Command.Start start, Map<String, Map<String, Object>> answers,
            DataStorePort stores) {
        CompiledProcess compiled = new ProcessCompiler(BehaviorCatalog.registry(), 500).compile("D1", process);
        DefaultExecutionKernel kernel = new DefaultExecutionKernel(compiled, BehaviorCatalog.registry(),
                new V1ExpressionEvaluator(100), DefaultExecutionKernel.DEFAULT_MAX_INSTANCES, stores);
        Transition t = kernel.apply(InstanceState.created("01JD0000000000000000000499", "bank-a", "D1", "K-499"),
                start, CLOCK);
        Set<String> visited = new LinkedHashSet<>();
        List<Effect> pending = new ArrayList<>(t.effects());
        List<Effect> all = new ArrayList<>(t.effects());
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
                    all.addAll(t.effects());
                    collect(t, visited);
                    progressed = true;
                    break;
                }
            }
        }
        return new Outcome(t.state(), visited, all);
    }

    private static void collect(Transition t, Set<String> visited) {
        for (HistoryEntry h : t.history()) {
            if (h.nodeId() != null) {
                visited.add(h.nodeId());
            }
        }
    }

    @Test
    void PRE_347_a_data_output_association_leaves_the_result_in_the_data_object_and_a_data_input_reads_it() throws IOException {
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
    void PRE_349_control_a_compensation_throw_that_does_not_wait_lets_the_flow_go_on_and_holds_the_end_until_the_undo_is_done()
            throws IOException {
        Process process = read("compensation-background.bpmn");

        Outcome out = run(process, Map.of(), Map.of());

        // The notice to the customer went out while the undo of the reservation was still to run, so the instance is
        // not COMPENSATING and is not over: a background walk token holds it open
        assertThat(out.visited()).contains("reserve-funds", "withdraw", "notify-customer", "end");
        assertThat(out.state().status()).isEqualTo(InstanceStatus.RUNNING);
        assertThat(out.effects()).filteredOn(Effect.StartCompensation.class::isInstance)
                .singleElement().satisfies(e -> {
                    Effect.StartCompensation started = (Effect.StartCompensation) e;
                    assertThat(started.activityId()).isEqualTo("reserve-funds");
                    assertThat(started.walkTokenId()).isNotNull();
                });
        String walk = ((Effect.StartCompensation) out.effects().stream()
                .filter(Effect.StartCompensation.class::isInstance).findFirst().orElseThrow()).walkTokenId();
        CompiledProcess compiled = new ProcessCompiler(BehaviorCatalog.registry(), 500).compile("D1", process);
        DefaultExecutionKernel kernel = new DefaultExecutionKernel(compiled, BehaviorCatalog.registry(),
                new V1ExpressionEvaluator(100));

        // When the shell reports the walk done
        Transition ended = kernel.apply(out.state(), new Command.EndBackgroundCompensation(walk), CLOCK);

        // Then the instance completes
        assertThat(ended.state().status()).isEqualTo(InstanceStatus.COMPLETED);
    }

    @Test
    void PRE_350_a_parallel_multiple_process_start_imports_compiles_and_runs_once_it_has_been_started() throws IOException {
        Outcome out = run(read("parallel-multiple-start.bpmn"), Map.of("customer", "Tran Thi Mai"), Map.of());

        assertThat(out.state().status()).isEqualTo(InstanceStatus.COMPLETED);
        assertThat(out.visited()).contains("start", "open-account", "end");
    }

    @Test
    void PRE_351_a_multiple_throw_and_a_multiple_end_event_throw_every_definition_and_then_go_on() throws IOException {
        Outcome out = run(read("multiple-throw-and-end.bpmn"), Map.of(), Map.of());

        assertThat(out.state().status()).isEqualTo(InstanceStatus.COMPLETED);
        assertThat(out.effects()).filteredOn(Effect.ThrowMessage.class::isInstance).hasSize(2);
        assertThat(out.effects()).filteredOn(Effect.BroadcastSignal.class::isInstance)
                .extracting(e -> ((Effect.BroadcastSignal) e).name())
                .containsExactly("credit-line-opened", "booking-closed");
        assertThat(out.visited()).contains("announce", "close");
    }

    @Test
    void PRE_352_an_instantiating_event_gateway_starts_after_the_catch_that_fired_and_waits_for_no_other() throws IOException {
        Process process = read("event-gateway-start.bpmn");

        Outcome message = run(process, new Command.Start(Map.of("applicant", "Tran Thi Mai"), "officer.hoa", "online"),
                Map.of(), DataStorePort.NONE);
        Outcome signal = run(process, new Command.Start(Map.of(), "system:signal", "branch"), Map.of(),
                DataStorePort.NONE);

        assertThat(message.state().status()).isEqualTo(InstanceStatus.COMPLETED);
        assertThat(message.visited()).contains("route-online", "end").doesNotContain("route-branch");
        assertThat(message.state().variables()).containsEntry("route", "online").containsEntry("applicant", "Tran Thi Mai");
        assertThat(message.effects()).noneMatch(e -> e instanceof Effect.CreateSubscription);
        assertThat(signal.visited()).contains("route-branch", "end").doesNotContain("route-online");
        assertThatThrownBy(() -> run(process, Map.of(), Map.of())).hasMessageContaining("event gateway intake");
        assertThatThrownBy(() -> run(process, new Command.Start(Map.of(), "x", "route-online"), Map.of(),
                DataStorePort.NONE)).hasMessageContaining("event gateway intake");
    }

    @Test
    void PRE_355_a_parallel_instantiating_event_gateway_starts_on_one_catch_and_awaits_every_other() throws IOException {
        Process process = read("event-gateway-parallel-start.bpmn");

        Outcome byMessage = run(process, new Command.Start(Map.of(), "officer.hoa", "kyc"), Map.of(), DataStorePort.NONE);
        Outcome bySignal = run(process, new Command.Start(Map.of(), "system:signal", "sanction"), Map.of(),
                DataStorePort.NONE);

        // The message starts it: its review runs, and the signal is still awaited, so the instance is not over
        assertThat(byMessage.state().status()).isEqualTo(InstanceStatus.RUNNING);
        assertThat(byMessage.visited()).contains("check-kyc").doesNotContain("clear-sanction");
        assertThat(byMessage.effects()).filteredOn(Effect.CreateSubscription.class::isInstance)
                .extracting(e -> ((Effect.CreateSubscription) e).name()).containsExactly("sanction-screening-done");
        // The signal starts it: the message is awaited under the key of the instance
        assertThat(bySignal.visited()).contains("clear-sanction").doesNotContain("check-kyc");
        assertThat(bySignal.effects()).filteredOn(Effect.CreateSubscription.class::isInstance)
                .singleElement().satisfies(e -> {
                    assertThat(((Effect.CreateSubscription) e).name()).isEqualTo("kyc-documents");
                    assertThat(((Effect.CreateSubscription) e).correlationKey()).isEqualTo("K-499");
                });
        // Nothing was cancelled
        assertThat(byMessage.state().tokens()).noneMatch(t -> t.status() == TokenStatus.CANCELLED);
    }

    @Test
    void PRE_353_a_compensation_event_sub_process_of_two_tasks_pushes_them_so_the_walk_runs_them_in_flow_order()
            throws IOException {
        Outcome out = run(read("compensation-event-subprocess.bpmn"), Map.of(), Map.of("reserve-funds", Map.of()));

        assertThat(out.state().status()).isEqualTo(InstanceStatus.COMPLETED);
        assertThat(out.effects().stream().filter(Effect.PushCompensation.class::isInstance)
                .map(Effect.PushCompensation.class::cast).map(Effect.PushCompensation::handler).toList())
                .containsExactly("release-funds", "notify-treasury", "reverse-disbursement");
    }

    @Test
    void PRE_354_a_data_store_association_reads_and_writes_through_the_host_and_only_for_the_tenant_of_the_instance()
            throws IOException {
        Map<String, Object> rows = new HashMap<>();
        rows.put("bank-a/limit", 50_000_000);
        rows.put("bank-b/limit", 5_000_000);
        List<DataStorePort.Access> seen = new ArrayList<>();
        DataStorePort stores = new DataStorePort() {
            @Override
            public Object read(Access access) {
                seen.add(access);
                return rows.get(access.tenantId() + "/" + access.item());
            }

            @Override
            public void write(Access access, Object value) {
                seen.add(access);
                rows.put(access.tenantId() + "/" + access.item(), value);
            }
        };

        // The variable tenantId names the other bank; the instance's own tenant is bank-a
        Outcome out = run(read("data-store.bpmn"), new Command.Start(Map.of("tenantId", "bank-b"), "officer.hoa"),
                Map.of("score", Map.of("score", 712)), stores);

        assertThat(out.state().status()).isEqualTo(InstanceStatus.COMPLETED);
        assertThat(seen).extracting(DataStorePort.Access::tenantId).containsOnly("bank-a");
        assertThat(seen).extracting(DataStorePort.Access::store).containsExactly("credit-limits", "score-ledger");
        assertThat(rows).containsEntry("bank-a/score", 712).doesNotContainKey("bank-b/score");
        assertThat(out.effects().stream().filter(Effect.InvokeService.class::isInstance)
                .map(Effect.InvokeService.class::cast).findFirst().orElseThrow().inputs()).containsEntry("limit", 50_000_000);
    }

    @Test
    void a_data_store_with_no_host_behind_it_fails_the_step_with_its_code() throws IOException {
        assertThatThrownBy(() -> run(read("data-store.bpmn"), Map.of(), Map.of()))
                .hasMessageContaining("credit-limits");
    }

    @Test
    void PRE_348_every_conformance_file_keeps_its_element_flow_count_and_hash_through_export_and_import() throws IOException {
        for (String file : List.of("data-objects.bpmn", "gateways.bpmn", "parallel-join.bpmn",
                "sub-process-and-timer.bpmn", "compensation-background.bpmn", "parallel-multiple-start.bpmn",
                "multiple-throw-and-end.bpmn", "event-gateway-start.bpmn", "event-gateway-parallel-start.bpmn", "compensation-event-subprocess.bpmn",
                "data-store.bpmn")) {
            Process source = read(file);

            Process again = reader.read(new SubsetBpmnWriter().write(source));

            assertThat(again.allFlowElements()).as(file).hasSameSizeAs(source.allFlowElements());
            assertThat(CanonicalProcessWriter.canonicalText(again)).as(file)
                    .isEqualTo(CanonicalProcessWriter.canonicalText(source));
        }
    }
}
