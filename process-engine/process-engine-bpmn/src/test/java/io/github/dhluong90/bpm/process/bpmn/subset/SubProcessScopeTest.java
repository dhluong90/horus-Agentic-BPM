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
import io.github.dhluong90.bpm.process.internal.model.bpmn.SubProcess;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The embedded {@code subProcess} on the reader, the compiler and the kernel, in memory (#486, ADR-0020): a customer
 * onboarding scores the customer inside a sub-process, runs a bureau check and a fraud check in parallel there, and
 * opens the account only after the sub-process ends.
 */
class SubProcessScopeTest {

    private static final String INSTANCE = "01JD0000000000000000000486";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-23T03:00:00Z"), ZoneOffset.UTC);
    private static final String HEAD = """
            <?xml version="1.0" encoding="UTF-8"?>
            <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                              xmlns:horus="urn:horus-bpm:bpmn:extensions:v1" id="d" targetNamespace="urn:t"
                              expressionLanguage="urn:horus-bpm:expr:v1">
              <bpmn:process id="customer-onboarding" isExecutable="true">
            """;
    private static final String TAIL = "</bpmn:process></bpmn:definitions>";
    /** start, the sub-process onboard (score, then bureau and fraud in parallel), open-account, end. */
    private static final String ONBOARDING = HEAD + """
                <bpmn:startEvent id="start"/>
                <bpmn:subProcess id="onboard" name="Onboard the customer">
                  <bpmn:incoming>f1</bpmn:incoming>
                  <bpmn:outgoing>f2</bpmn:outgoing>
                  <bpmn:startEvent id="onboard-start"/>
                  <bpmn:scriptTask id="score" scriptFormat="urn:horus-bpm:expr:v1" horus:resultVariable="risk">
                    <bpmn:script>amount * 2</bpmn:script>
                  </bpmn:scriptTask>
                  <bpmn:parallelGateway id="fork"/>
                  <bpmn:serviceTask id="bureau" horus:handler="credit-bureau" horus:resultVariable="bureauResult"/>
                  <bpmn:serviceTask id="fraud" horus:handler="fraud-check" horus:resultVariable="fraudResult"/>
                  <bpmn:parallelGateway id="join"/>
                  <bpmn:endEvent id="onboard-end"/>
                  <bpmn:sequenceFlow id="s1" sourceRef="onboard-start" targetRef="score"/>
                  <bpmn:sequenceFlow id="s2" sourceRef="score" targetRef="fork"/>
                  <bpmn:sequenceFlow id="s3" sourceRef="fork" targetRef="bureau"/>
                  <bpmn:sequenceFlow id="s4" sourceRef="fork" targetRef="fraud"/>
                  <bpmn:sequenceFlow id="s5" sourceRef="bureau" targetRef="join"/>
                  <bpmn:sequenceFlow id="s6" sourceRef="fraud" targetRef="join"/>
                  <bpmn:sequenceFlow id="s7" sourceRef="join" targetRef="onboard-end"/>
                </bpmn:subProcess>
                <bpmn:serviceTask id="open-account" horus:handler="open-account"/>
                <bpmn:endEvent id="end"/>
                <bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="onboard"/>
                <bpmn:sequenceFlow id="f2" sourceRef="onboard" targetRef="open-account"/>
                <bpmn:sequenceFlow id="f3" sourceRef="open-account" targetRef="end"/>
            """ + TAIL;
    /** An account opened in the root scope, then a sub-process that places a hold and posts the debit. */
    private static final String FUNDING = HEAD + """
                <bpmn:startEvent id="start"/>
                <bpmn:serviceTask id="open" horus:handler="open-account" horus:compensate="close-account"/>
                <bpmn:subProcess id="fund">
                  <bpmn:startEvent id="fund-start"/>
                  <bpmn:serviceTask id="hold" horus:handler="place-hold" horus:compensate="release-hold"/>
                  <bpmn:serviceTask id="debit" horus:handler="post-debit" horus:onFailure="compensate"/>
                  <bpmn:endEvent id="fund-end"/>
                  <bpmn:sequenceFlow id="s1" sourceRef="fund-start" targetRef="hold"/>
                  <bpmn:sequenceFlow id="s2" sourceRef="hold" targetRef="debit"/>
                  <bpmn:sequenceFlow id="s3" sourceRef="debit" targetRef="fund-end"/>
                </bpmn:subProcess>
                <bpmn:endEvent id="end"/>
                <bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="open"/>
                <bpmn:sequenceFlow id="f2" sourceRef="open" targetRef="fund"/>
                <bpmn:sequenceFlow id="f3" sourceRef="fund" targetRef="end"/>
            """ + TAIL;

    private final SubsetBpmnReader reader = new SubsetBpmnReader(Limits.DEFAULTS);

    private Process read(String xml) {
        return reader.read(xml.getBytes(StandardCharsets.UTF_8));
    }

    private static DefaultExecutionKernel kernel(Process process) {
        CompiledProcess compiled = new ProcessCompiler(BehaviorCatalog.registry(), 500).compile("D1", process);
        return new DefaultExecutionKernel(compiled, BehaviorCatalog.registry(), new V1ExpressionEvaluator(100));
    }

    private static Transition start(DefaultExecutionKernel kernel, Map<String, Object> variables) {
        return kernel.apply(InstanceState.created(INSTANCE, "bank-a", "D1", "ONB-2026-0001"),
                new Command.Start(variables, "teller.hoa"), CLOCK);
    }

    private static Token scope(InstanceState state, String nodeId) {
        return state.tokens().stream().filter(t -> t.nodeId().equals(nodeId) && t.kind() == TokenKind.SCOPE)
                .findFirst().orElseThrow();
    }

    private static Token flow(InstanceState state, String nodeId) {
        return state.tokens().stream().filter(t -> t.nodeId().equals(nodeId) && t.kind() == TokenKind.FLOW)
                .findFirst().orElseThrow();
    }

    private static Effect.InvokeService call(Transition t, String nodeId) {
        return t.effects().stream().filter(Effect.InvokeService.class::isInstance).map(Effect.InvokeService.class::cast)
                .filter(e -> e.nodeId().equals(nodeId)).findFirst().orElseThrow();
    }

    /** Completes the call that {@code scheduled} asked for on a state that is {@code from}. */
    private static Transition complete(DefaultExecutionKernel kernel, Transition scheduled, Transition from,
            String nodeId, Map<String, Object> result) {
        Effect.InvokeService call = call(scheduled, nodeId);
        return kernel.apply(from.state(), new Command.AsyncResult(call.tokenId(), call.attemptId(),
                new StepResult.Completed(result)), CLOCK);
    }

    private static Transition complete(DefaultExecutionKernel kernel, Transition from, String nodeId,
            Map<String, Object> result) {
        return complete(kernel, from, from, nodeId, result);
    }

    @Test
    void PRE_260_the_parent_continues_only_after_the_inner_end_and_the_scope_closes() {
        // Given the onboarding of a customer whose declared amount is 1,500,000 VND
        DefaultExecutionKernel kernel = kernel(read(ONBOARDING));

        // When it starts
        Transition started = start(kernel, Map.of("amount", 1_500_000L));

        // Then the sub-process has its own SCOPE token under the root, both checks run inside it, and the parent's
        // next step has no token yet
        InstanceState state = started.state();
        Token root = state.tokens().stream().filter(t -> t.kind() == TokenKind.SCOPE && t.scopeTokenId() == null)
                .findFirst().orElseThrow();
        Token onboard = scope(state, "onboard");
        assertThat(onboard.scopeTokenId()).isEqualTo(root.id());
        assertThat(onboard.status()).isEqualTo(TokenStatus.WAITING_SCOPE);
        assertThat(flow(state, "bureau").scopeTokenId()).isEqualTo(onboard.id());
        assertThat(flow(state, "fraud").scopeTokenId()).isEqualTo(onboard.id());
        assertThat(state.tokens()).noneMatch(t -> t.nodeId().equals("open-account"));
        assertThat(started.history()).extracting(h -> h.type()).contains("SCOPE_ENTERED");

        // And the sub-process read the parent's variable and wrote its result to the instance
        assertThat(state.variables().get("risk")).hasToString("3000000");

        // When one check completes first
        Transition bureauDone = complete(kernel, started, "bureau", Map.of("score", 712));

        // Then the join waits for the other branch of the same scope, and the parent has not moved
        assertThat(flow(bureauDone.state(), "join").status()).isEqualTo(TokenStatus.JOINED);
        assertThat(bureauDone.state().tokens()).noneMatch(t -> t.nodeId().equals("open-account"));

        // When the other check completes
        Transition fraudDone = complete(kernel, started, bureauDone, "fraud", Map.of("flagged", false));

        // Then the scope is COMPLETED, no token is left live inside it, and the parent continues in the root scope
        InstanceState after = fraudDone.state();
        assertThat(scope(after, "onboard").status()).isEqualTo(TokenStatus.COMPLETED);
        assertThat(after.tokens()).filteredOn(t -> t.scopeTokenId() != null && t.scopeTokenId().equals(onboard.id()))
                .noneMatch(t -> t.status().live());
        assertThat(flow(after, "open-account").scopeTokenId()).isEqualTo(root.id());
        assertThat(after.status()).isEqualTo(InstanceStatus.RUNNING);

        // And the instance completes when the parent's step does, with the sub-process's variable still there
        Transition ended = complete(kernel, fraudDone, "open-account", Map.of("accountNo", "0011-223344"));
        assertThat(ended.state().status()).isEqualTo(InstanceStatus.COMPLETED);
        assertThat(ended.state().tokens()).noneMatch(t -> t.status().live());
        assertThat(ended.state().variables().get("risk")).hasToString("3000000");
    }

    @Test
    void PRE_261_a_cancel_inside_the_sub_process_cancels_every_inner_token_and_job() {
        // Given the onboarding with both checks waiting inside the sub-process
        DefaultExecutionKernel kernel = kernel(read(ONBOARDING));
        Transition started = start(kernel, Map.of("amount", 1_500_000L));
        Effect.InvokeService bureau = call(started, "bureau");
        Effect.InvokeService fraud = call(started, "fraud");

        // When the instance is cancelled
        Transition cancelled = kernel.apply(started.state(), new Command.Cancel("customer withdrew", "ops.lan"), CLOCK);

        // Then the instance is CANCELLED, no token of any scope is live, and both calls are cancelled
        assertThat(cancelled.state().status()).isEqualTo(InstanceStatus.CANCELLED);
        assertThat(cancelled.state().tokens()).noneMatch(t -> t.status().live());
        assertThat(scope(cancelled.state(), "onboard").status()).isEqualTo(TokenStatus.CANCELLED);
        assertThat(cancelled.effects()).filteredOn(Effect.CancelJob.class::isInstance).extracting(e ->
                ((Effect.CancelJob) e).jobId()).containsExactlyInAnyOrder(bureau.attemptId(), fraud.attemptId());
    }

    @Test
    void PRE_262_an_undo_started_inside_the_sub_process_walks_that_scope_and_never_the_enclosing_one() {
        // Given a funding whose account was opened in the root scope and whose hold was placed in the sub-process
        DefaultExecutionKernel kernel = kernel(read(FUNDING));
        Transition started = start(kernel, Map.of("accountNo", "0011-223344"));
        Transition opened = complete(kernel, started, "open", Map.of("accountNo", "0011-223344"));
        Transition held = complete(kernel, opened, "hold", Map.of("holdId", "HLD-1"));
        Token root = state(held).tokens().stream().filter(t -> t.kind() == TokenKind.SCOPE && t.scopeTokenId() == null)
                .findFirst().orElseThrow();
        Token fund = scope(state(held), "fund");

        // Then each undo was pushed under the scope its step ran in
        assertThat(opened.effects()).filteredOn(Effect.PushCompensation.class::isInstance).singleElement()
                .isInstanceOfSatisfying(Effect.PushCompensation.class, push -> {
                    assertThat(push.nodeId()).isEqualTo("open");
                    assertThat(push.scopeTokenId()).isEqualTo(root.id());
                });
        assertThat(held.effects()).filteredOn(Effect.PushCompensation.class::isInstance).singleElement()
                .isInstanceOfSatisfying(Effect.PushCompensation.class, push -> {
                    assertThat(push.nodeId()).isEqualTo("hold");
                    assertThat(push.scopeTokenId()).isEqualTo(fund.id());
                });

        // When the debit answers a business error
        Effect.InvokeService debit = call(held, "debit");
        Transition failed = kernel.apply(held.state(), new Command.AsyncResult(debit.tokenId(), debit.attemptId(),
                new StepResult.BusinessError("INSUFFICIENT_FUNDS", "balance too low")), CLOCK);

        // Then the compensation is that of the sub-process's scope, not the root's
        assertThat(state(failed).status()).isEqualTo(InstanceStatus.COMPENSATING);
        assertThat(failed.effects()).contains(new Effect.StartCompensation(fund.id(), "debit", "INSUFFICIENT_FUNDS"))
                .noneMatch(e -> e instanceof Effect.StartCompensation s && s.scopeTokenId().equals(root.id()));

        // And ending it leaves that scope COMPENSATED and the instance FAILED
        Transition ended = kernel.apply(failed.state(), new Command.EndCompensation(fund.id()), CLOCK);
        assertThat(state(ended).status()).isEqualTo(InstanceStatus.FAILED);
        assertThat(scope(state(ended), "fund").status()).isEqualTo(TokenStatus.COMPENSATED);
    }

    private static InstanceState state(Transition t) {
        return t.state();
    }

    @Test
    void PRE_263_a_sub_process_reads_with_its_steps_and_exports_back_to_the_same_definition() {
        // When the onboarding is read
        Process process = read(ONBOARDING);

        // Then the sub-process holds its own steps and flows, and the canonical form nests them
        assertThat(process.flowElements()).filteredOn(SubProcess.class::isInstance).singleElement()
                .isInstanceOfSatisfying(SubProcess.class, block -> {
                    assertThat(block.flowElements()).hasSize(14);
                    assertThat(block.triggeredByEvent()).isFalse();
                    assertThat(block.outgoing()).containsExactly("f2");
                });
        assertThat(CanonicalProcessWriter.canonicalText(process)).contains("\"type\":\"subProcess\"");

        // And the export reads back to the same hash, with the inner steps nested
        byte[] exported = new SubsetBpmnWriter().write(process);
        assertThat(CanonicalProcessWriter.canonicalText(reader.read(exported)))
                .isEqualTo(CanonicalProcessWriter.canonicalText(process));
        assertThat(new String(exported, StandardCharsets.UTF_8)).contains("<bpmn:subProcess id=\"onboard\"");
    }

    @Test
    void PRE_263_an_event_sub_process_is_refused_with_its_stage_and_a_flow_across_the_boundary_is_refused() {
        // Given an event sub-process, and a flow from inside the sub-process to the root end
        String byEvent = ONBOARDING.replace("<bpmn:subProcess id=\"onboard\"", "<bpmn:subProcess id=\"onboard\""
                + " triggeredByEvent=\"true\"");
        String crossing = ONBOARDING.replace("<bpmn:sequenceFlow id=\"s7\"", "<bpmn:sequenceFlow id=\"s8\""
                + " sourceRef=\"fraud\" targetRef=\"end\"/><bpmn:sequenceFlow id=\"s7\"");

        // Then the first is named unsupported at stage S1, the second is refused when compiled
        assertThatThrownBy(() -> read(byEvent)).hasMessageContaining("subProcess with triggeredByEvent");
        Process process = read(crossing);
        assertThatThrownBy(() -> kernel(process)).isInstanceOfSatisfying(ValidationFailedException.class, e ->
                assertThat(e.problems()).extracting(DefinitionProblem::code, DefinitionProblem::elementId)
                        .contains(org.assertj.core.groups.Tuple.tuple("FLOW_CROSSES_SAGA", "s8")));
    }

    @Test
    void PRE_263_a_sub_process_needs_one_start_event_and_an_end_event_of_its_own() {
        String noEnd = ONBOARDING.replace("<bpmn:endEvent id=\"onboard-end\"/>", "")
                .replace("<bpmn:sequenceFlow id=\"s7\" sourceRef=\"join\" targetRef=\"onboard-end\"/>", "");
        Process process = read(noEnd);

        assertThatThrownBy(() -> kernel(process)).isInstanceOfSatisfying(ValidationFailedException.class, e ->
                assertThat(e.problems()).extracting(DefinitionProblem::code, DefinitionProblem::elementId)
                        .contains(org.assertj.core.groups.Tuple.tuple("NO_END_EVENT", "onboard")));
    }
}
