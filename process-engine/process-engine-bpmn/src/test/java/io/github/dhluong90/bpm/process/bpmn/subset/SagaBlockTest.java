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
import io.github.dhluong90.bpm.process.internal.model.bpmn.Transaction;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The {@code saga} block on the reader, the compiler and the kernel, in memory (#67, PRE-044, PRE-073, PRE-076): a
 * fund transfer holds 25,000,000 VND on account 0011-223344 and posts the debit inside the block. The walk of the
 * stack is proven on PostgreSQL in {@code SagaCompensationTest}.
 */
class SagaBlockTest {

    private static final String INSTANCE = "01JD0000000000000000000067";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-21T03:00:00Z"), ZoneOffset.UTC);
    private static final String HEAD = """
            <?xml version="1.0" encoding="UTF-8"?>
            <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                              xmlns:horus="urn:horus-bpm:bpmn:extensions:v1" id="d" targetNamespace="urn:t"
                              expressionLanguage="urn:horus-bpm:expr:v1">
              <bpmn:process id="fund-transfer" isExecutable="true">
            """;
    private static final String TAIL = "</bpmn:process></bpmn:definitions>";
    /** start, the block transfer (hold-funds, then post-debit with onFailure compensate), end. */
    private static final String TRANSFER = HEAD + """
                <bpmn:startEvent id="start"/>
                <bpmn:transaction id="transfer">
                  <bpmn:startEvent id="transfer-start"/>
                  <bpmn:serviceTask id="hold-funds" horus:handler="place-hold" horus:compensate="release-hold"
                                    horus:resultVariable="hold">
                    <bpmn:extensionElements>
                      <horus:input name="account" value="${instance.sourceAccount}"/>
                    </bpmn:extensionElements>
                  </bpmn:serviceTask>
                  <bpmn:serviceTask id="post-debit" horus:handler="post-debit" horus:onFailure="compensate"
                                    horus:resultVariable="debit"/>
                  <bpmn:endEvent id="transfer-end"/>
                  <bpmn:sequenceFlow id="t1" sourceRef="transfer-start" targetRef="hold-funds"/>
                  <bpmn:sequenceFlow id="t2" sourceRef="hold-funds" targetRef="post-debit"/>
                  <bpmn:sequenceFlow id="t3" sourceRef="post-debit" targetRef="transfer-end"/>
                </bpmn:transaction>
                <bpmn:endEvent id="end"/>
                <bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="transfer"/>
                <bpmn:sequenceFlow id="f2" sourceRef="transfer" targetRef="end"/>
            """ + TAIL;

    private final SubsetBpmnReader reader = new SubsetBpmnReader(Limits.DEFAULTS);

    private Process read(String xml) {
        return reader.read(xml.getBytes(StandardCharsets.UTF_8));
    }

    private static DefaultExecutionKernel kernel(Process process) {
        CompiledProcess compiled = new ProcessCompiler(BehaviorCatalog.registry(), 500).compile("D1", process);
        return new DefaultExecutionKernel(compiled, BehaviorCatalog.registry(), new V1ExpressionEvaluator(100));
    }

    private static Token token(InstanceState state, String nodeId, TokenKind kind) {
        return state.tokens().stream().filter(t -> t.nodeId().equals(nodeId) && t.kind() == kind).findFirst()
                .orElseThrow();
    }

    private static Effect.InvokeService call(Transition t, String nodeId) {
        return t.effects().stream().filter(Effect.InvokeService.class::isInstance).map(Effect.InvokeService.class::cast)
                .filter(e -> e.nodeId().equals(nodeId)).findFirst().orElseThrow();
    }

    /** Starts the transfer and completes hold-funds; post-debit waits for its result. */
    private static Transition heldAndPosting(DefaultExecutionKernel kernel) {
        Transition started = kernel.apply(InstanceState.created(INSTANCE, "bank-a", "D1", "TRF-2026-0001"),
                new Command.Start(Map.of("sourceAccount", "0011-223344", "amountMinor", 25_000_000L), "teller.hoa"),
                CLOCK);
        Effect.InvokeService hold = call(started, "hold-funds");
        return kernel.apply(started.state(), new Command.AsyncResult(hold.tokenId(), hold.attemptId(),
                new StepResult.Completed(Map.of("holdId", "HLD-1"))), CLOCK);
    }

    @Test
    void PRE_044_a_saga_block_runs_under_its_own_scope_token_and_pushes_its_undos_there() {
        // Given the transfer
        DefaultExecutionKernel kernel = kernel(read(TRANSFER));

        // When it starts and hold-funds completes
        Transition held = heldAndPosting(kernel);

        // Then the block has a SCOPE token under the root, hold-funds pushed its undo under it with its inputs and
        // result, and post-debit waits inside the block
        InstanceState state = held.state();
        Token root = state.tokens().stream().filter(t -> t.kind() == TokenKind.SCOPE && t.scopeTokenId() == null)
                .findFirst().orElseThrow();
        Token block = token(state, "transfer", TokenKind.SCOPE);
        assertThat(block.scopeTokenId()).isEqualTo(root.id());
        assertThat(block.status()).isEqualTo(TokenStatus.WAITING_SCOPE);
        assertThat(token(state, "post-debit", TokenKind.FLOW).scopeTokenId()).isEqualTo(block.id());
        assertThat(held.effects()).filteredOn(Effect.PushCompensation.class::isInstance).singleElement()
                .isInstanceOfSatisfying(Effect.PushCompensation.class, push -> {
                    assertThat(push.scopeTokenId()).isEqualTo(block.id());
                    assertThat(push.nodeId()).isEqualTo("hold-funds");
                    assertThat(push.handler()).isEqualTo("release-hold");
                    assertThat(push.inputSnapshot()).containsEntry("account", "0011-223344")
                            .containsEntry("forwardResult", Map.of("holdId", "HLD-1"));
                });
    }

    @Test
    void PRE_044_a_saga_block_that_ends_continues_at_its_outgoing_flow_and_the_instance_completes() {
        // Given the transfer with hold-funds done
        DefaultExecutionKernel kernel = kernel(read(TRANSFER));
        Transition held = heldAndPosting(kernel);
        Effect.InvokeService debit = call(held, "post-debit");

        // When post-debit completes
        Transition done = kernel.apply(held.state(), new Command.AsyncResult(debit.tokenId(), debit.attemptId(),
                new StepResult.Completed(Map.of("postingRef", "FT26325001"))), CLOCK);

        // Then the block's scope token is COMPLETED and the instance completes in the root scope; post-debit pushed
        // nothing, because it has no undo
        assertThat(token(done.state(), "transfer", TokenKind.SCOPE).status()).isEqualTo(TokenStatus.COMPLETED);
        assertThat(done.state().status()).isEqualTo(InstanceStatus.COMPLETED);
        assertThat(done.effects()).noneMatch(Effect.PushCompensation.class::isInstance);
    }

    @Test
    void PRE_073_a_business_error_with_on_failure_compensate_starts_the_compensation_of_its_scope() {
        // Given the transfer with hold-funds done
        DefaultExecutionKernel kernel = kernel(read(TRANSFER));
        Transition held = heldAndPosting(kernel);
        Effect.InvokeService debit = call(held, "post-debit");

        // When post-debit answers the business error ACCOUNT_CLOSED
        Transition failed = kernel.apply(held.state(), new Command.AsyncResult(debit.tokenId(), debit.attemptId(),
                new StepResult.BusinessError("ACCOUNT_CLOSED", "account closed")), CLOCK);

        // Then the instance is COMPENSATING with the block's scope to walk, its flow token is CANCELLED, and no
        // incident is raised
        Token block = token(failed.state(), "transfer", TokenKind.SCOPE);
        assertThat(failed.state().status()).isEqualTo(InstanceStatus.COMPENSATING);
        assertThat(failed.effects()).noneMatch(Effect.RaiseIncident.class::isInstance)
                .contains(new Effect.StartCompensation(block.id(), "post-debit", "ACCOUNT_CLOSED"));
        assertThat(token(failed.state(), "post-debit", TokenKind.FLOW).status()).isEqualTo(TokenStatus.CANCELLED);
        assertThat(failed.history()).extracting(h -> h.type()).contains("COMPENSATION_STARTED");

        // And a COMPENSATING instance takes only the end of its compensation
        assertThatThrownBy(() -> kernel.apply(failed.state(), new Command.Cancel("withdrawn", "ops.lan"), CLOCK))
                .isInstanceOfSatisfying(ExecutionException.class, e -> assertThat(e.code()).isEqualTo("INVALID_STATE"));
        Transition ended = kernel.apply(failed.state(), new Command.EndCompensation(block.id()), CLOCK);
        assertThat(ended.state().status()).isEqualTo(InstanceStatus.FAILED);
        assertThat(token(ended.state(), "transfer", TokenKind.SCOPE).status()).isEqualTo(TokenStatus.COMPENSATED);
        assertThat(ended.state().tokens()).noneMatch(t -> t.status().live());
        assertThat(ended.effects()).filteredOn(Effect.EmitEvent.class::isInstance).extracting(e ->
                ((Effect.EmitEvent) e).type()).containsExactly("processes.instance.failed.v1");
    }

    @Test
    void PRE_073_a_saga_block_reads_with_its_steps_and_its_hash_names_them() {
        // When the transfer is read
        Process process = read(TRANSFER);

        // Then the block holds its own steps, and the canonical form nests them with the undo and the failure action
        assertThat(process.flowElements()).filteredOn(Transaction.class::isInstance).singleElement()
                .isInstanceOfSatisfying(Transaction.class, block -> assertThat(block.flowElements()).hasSize(7));
        String canonical = CanonicalProcessWriter.canonicalText(process);
        assertThat(canonical).contains("\"type\":\"saga\"").contains("\"compensate\":\"release-hold\"")
                .contains("\"onFailure\":\"compensate\"");
        assertThat(CanonicalProcessWriter.canonicalText(read(TRANSFER.replace(" horus:onFailure=\"compensate\"", ""))))
                .doesNotContain("onFailure");
    }

    @Test
    void PRE_073_an_agent_step_names_its_undo_and_its_failure_action_like_a_service_step() {
        // Given an agent step that proposes a hold and is undone by the adapter release-hold
        String agent = HEAD + """
                    <bpmn:startEvent id="start"/>
                    <bpmn:serviceTask id="propose-hold" horus:type="agentTask" horus:agent="holds.propose"
                                      horus:compensate="release-hold" horus:onFailure="compensate"
                                      horus:resultVariable="proposal">
                      <bpmn:extensionElements>
                        <horus:outputSchema>{"type": "object"}</horus:outputSchema>
                        <horus:guardrail decision="hold-guardrails" onViolation="fail"/>
                      </bpmn:extensionElements>
                    </bpmn:serviceTask>
                    <bpmn:endEvent id="end"/>
                    <bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="propose-hold"/>
                    <bpmn:sequenceFlow id="f2" sourceRef="propose-hold" targetRef="end"/>
                """ + TAIL;

        // When it is read
        Process process = read(agent);

        // Then the step carries both, and the canonical form names them
        assertThat(CanonicalProcessWriter.canonicalText(process)).contains("\"type\":\"agentTask\"")
                .contains("\"compensate\":\"release-hold\"").contains("\"onFailure\":\"compensate\"");
    }

    @Test
    void PRE_073_an_unknown_failure_action_and_an_invalid_undo_name_are_refused_at_import() {
        String broken = TRANSFER.replace("horus:onFailure=\"compensate\"", "horus:onFailure=\"retry-forever\"")
                .replace("horus:compensate=\"release-hold\"", "horus:compensate=\"release hold\"");

        assertThatThrownBy(() -> read(broken)).isInstanceOfSatisfying(ValidationFailedException.class, e ->
                assertThat(e.problems()).extracting(DefinitionProblem::code, DefinitionProblem::elementId)
                        .containsExactlyInAnyOrder(org.assertj.core.groups.Tuple.tuple("COMPENSATE_INVALID",
                                "hold-funds"), org.assertj.core.groups.Tuple.tuple("ON_FAILURE_INVALID",
                                "post-debit")));
    }

    @Test
    void PRE_076_throw_compensation_belongs_inside_a_saga_block_and_flows_never_cross_its_boundary() {
        // Given a throwCompensation in the root scope, and a flow from inside the block to the root end
        String outside = HEAD + """
                    <bpmn:startEvent id="start"/>
                    <bpmn:intermediateThrowEvent id="undo-all"><bpmn:compensateEventDefinition/></bpmn:intermediateThrowEvent>
                    <bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="undo-all"/>
                    <bpmn:endEvent id="end"/>
                """ + TAIL;
        String crossing = TRANSFER.replace("<bpmn:sequenceFlow id=\"t3\" sourceRef=\"post-debit\" "
                + "targetRef=\"transfer-end\"/>", "<bpmn:sequenceFlow id=\"t3\" sourceRef=\"post-debit\" "
                + "targetRef=\"transfer-end\"/><bpmn:sequenceFlow id=\"t4\" sourceRef=\"post-debit\" targetRef=\"end\"/>");

        // Then the first is refused at import and the second when compiled, each naming the element
        assertThatThrownBy(() -> read(outside)).isInstanceOfSatisfying(ValidationFailedException.class, e ->
                assertThat(e.problems()).extracting(DefinitionProblem::code)
                        .containsExactly("THROW_COMPENSATION_OUTSIDE_SAGA"));
        Process process = read(crossing);
        assertThatThrownBy(() -> kernel(process)).isInstanceOfSatisfying(ValidationFailedException.class, e ->
                assertThat(e.problems()).extracting(DefinitionProblem::code, DefinitionProblem::elementId)
                        .contains(org.assertj.core.groups.Tuple.tuple("FLOW_CROSSES_SAGA", "t4")));
    }
}
