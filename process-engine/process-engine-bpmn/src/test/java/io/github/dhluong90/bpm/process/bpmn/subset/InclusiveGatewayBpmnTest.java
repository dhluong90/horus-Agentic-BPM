package io.github.dhluong90.bpm.process.bpmn.subset;

import static org.assertj.core.api.Assertions.assertThat;

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
import io.github.dhluong90.bpm.process.internal.exec.TokenKind;
import io.github.dhluong90.bpm.process.internal.exec.TokenStatus;
import io.github.dhluong90.bpm.process.internal.exec.Transition;
import io.github.dhluong90.bpm.process.internal.expr.V1ExpressionEvaluator;
import io.github.dhluong90.bpm.process.internal.json.CanonicalProcessWriter;
import io.github.dhluong90.bpm.process.internal.model.bpmn.InclusiveGateway;
import io.github.dhluong90.bpm.process.internal.model.bpmn.Process;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * PRE-300 and PRE-304 on the reader and on a scope: an inclusive gateway imports, exports to the same definition, and
 * a join inside a {@code saga} block's scope token counts the block as one token that can still arrive.
 */
class InclusiveGatewayBpmnTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-23T03:00:00Z"), ZoneOffset.UTC);
    private static final String INSTANCE = "01JD0000000000000000000304";
    private static final String HEAD = """
            <?xml version="1.0" encoding="UTF-8"?>
            <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                              xmlns:horus="urn:horus-bpm:bpmn:extensions:v1" id="d" targetNamespace="urn:t"
                              expressionLanguage="urn:horus-bpm:expr:v1">
              <bpmn:process id="onboarding" isExecutable="true">
            """;
    private static final String TAIL = "</bpmn:process></bpmn:definitions>";
    /** The split starts the identity check, a block (its own scope), and the sanctions screening; both meet at join. */
    private static final String ONBOARDING = HEAD + """
                <bpmn:startEvent id="start"/>
                <bpmn:inclusiveGateway id="split"/>
                <bpmn:transaction id="identity-block">
                  <bpmn:startEvent id="identity-start"/>
                  <bpmn:serviceTask id="verify-id" horus:handler="verify-id" horus:resultVariable="identity"/>
                  <bpmn:endEvent id="identity-end"/>
                  <bpmn:sequenceFlow id="i1" sourceRef="identity-start" targetRef="verify-id"/>
                  <bpmn:sequenceFlow id="i2" sourceRef="verify-id" targetRef="identity-end"/>
                </bpmn:transaction>
                <bpmn:serviceTask id="screen-sanctions" horus:handler="screen-sanctions" horus:resultVariable="sanctions"/>
                <bpmn:inclusiveGateway id="join"/>
                <bpmn:serviceTask id="open-account" horus:handler="open-account" horus:resultVariable="account"/>
                <bpmn:endEvent id="end"/>
                <bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="split"/>
                <bpmn:sequenceFlow id="f2" sourceRef="split" targetRef="identity-block"/>
                <bpmn:sequenceFlow id="f3" sourceRef="split" targetRef="screen-sanctions"/>
                <bpmn:sequenceFlow id="f4" sourceRef="identity-block" targetRef="join"/>
                <bpmn:sequenceFlow id="f5" sourceRef="screen-sanctions" targetRef="join"/>
                <bpmn:sequenceFlow id="f6" sourceRef="join" targetRef="open-account"/>
                <bpmn:sequenceFlow id="f7" sourceRef="open-account" targetRef="end"/>
            """ + TAIL;

    private final SubsetBpmnReader reader = new SubsetBpmnReader(Limits.DEFAULTS);

    private static DefaultExecutionKernel kernel(Process process) {
        CompiledProcess compiled = new ProcessCompiler(BehaviorCatalog.registry(), 500).compile("D1", process);
        return new DefaultExecutionKernel(compiled, BehaviorCatalog.registry(), new V1ExpressionEvaluator(100));
    }

    private static Effect.InvokeService call(Transition t, String nodeId) {
        return t.effects().stream().filter(Effect.InvokeService.class::isInstance).map(Effect.InvokeService.class::cast)
                .filter(e -> e.nodeId().equals(nodeId)).findFirst().orElseThrow();
    }

    private static Transition done(DefaultExecutionKernel kernel, InstanceState state, Effect.InvokeService call) {
        return kernel.apply(state, new Command.AsyncResult(call.tokenId(), call.attemptId(),
                new StepResult.Completed(Map.of("ok", true))), CLOCK);
    }

    @Test
    void PRE_300_an_inclusive_gateway_with_its_default_imports_and_exports_to_the_same_definition() throws Exception {
        byte[] file;
        try (java.io.InputStream in = getClass().getResourceAsStream("/bpmn/payment-screening.bpmn")) {
            file = in.readAllBytes();
        }

        Process process = reader.read(file);

        InclusiveGateway split = process.flowElements().stream().filter(InclusiveGateway.class::isInstance)
                .map(InclusiveGateway.class::cast).filter(g -> g.id().equals("screen")).findFirst().orElseThrow();
        assertThat(split.defaultFlow()).isEqualTo("screen__standard");
        assertThat(split.outgoing()).containsExactly("screen__aml", "screen__sanctions", "screen__standard");
        byte[] exported = new SubsetBpmnWriter().write(process);
        assertThat(new String(exported, StandardCharsets.UTF_8)).contains("<bpmn:inclusiveGateway id=\"screen\"")
                .contains("default=\"screen__standard\"");
        assertThat(CanonicalProcessWriter.canonicalText(reader.read(exported)))
                .isEqualTo(CanonicalProcessWriter.canonicalText(process));
        assertThat(new ProcessCompiler(BehaviorCatalog.registry(), 500).compile("D1", process).node("settle")).isPresent();
    }

    @Test
    void PRE_304_a_block_that_can_still_reach_the_join_is_one_token_that_holds_it() {
        // Given the split started the identity block and the sanctions screening
        DefaultExecutionKernel kernel = kernel(reader.read(ONBOARDING.getBytes(StandardCharsets.UTF_8)));
        Transition started = kernel.apply(InstanceState.created(INSTANCE, "bank-a", "D1", "KYC-2026-0001"),
                new Command.Start(Map.of(), "teller.hoa"), CLOCK);

        // When the screening ends first, the join waits: the block's scope token stands for everything inside it
        Transition screened = done(kernel, started.state(), call(started, "screen-sanctions"));
        assertThat(screened.state().tokens()).filteredOn(t -> t.kind() == TokenKind.FLOW && t.nodeId().equals("join"))
                .singleElement().satisfies(t -> assertThat(t.status()).isEqualTo(TokenStatus.JOINED));
        assertThat(screened.effects()).noneMatch(e -> e instanceof Effect.InvokeService i
                && i.nodeId().equals("open-account"));

        // And when the block ends, its token continues in the parent scope, the join fires once and the account opens
        Transition verified = done(kernel, screened.state(), call(started, "verify-id"));
        Effect.InvokeService open = call(verified, "open-account");
        assertThat(verified.history()).filteredOn(h -> "JOIN_FIRED".equals(h.type())).hasSize(1);
        Transition opened = done(kernel, verified.state(), open);
        assertThat(opened.state().status()).isEqualTo(InstanceStatus.COMPLETED);
        assertThat(opened.state().tokens()).filteredOn(t -> t.nodeId().equals("identity-block")
                && t.kind() == TokenKind.SCOPE).singleElement()
                .satisfies(t -> assertThat(t.status()).isEqualTo(TokenStatus.COMPLETED));
        assertThat(opened.state().tokens()).noneMatch(t -> t.status().live());
    }
}
