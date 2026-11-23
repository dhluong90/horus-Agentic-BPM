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
import io.github.dhluong90.bpm.process.internal.exec.ProcessCompiler;
import io.github.dhluong90.bpm.process.internal.exec.Transition;
import io.github.dhluong90.bpm.process.internal.expr.V1ExpressionEvaluator;
import io.github.dhluong90.bpm.process.internal.json.CanonicalProcessWriter;
import io.github.dhluong90.bpm.process.internal.model.bpmn.Process;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** PRE-230, PRE-235 and PRE-237: message elements import, export, compile and send as the SPEC says (#491). */
class MessageElementsImportTest {

    private static final String HEAD = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<bpmn:definitions xmlns:bpmn=\"http://www.omg.org/spec/BPMN/20100524/MODEL\" "
            + "xmlns:horus=\"urn:horus-bpm:bpmn:extensions:v1\" id=\"d\" targetNamespace=\"urn:t\">\n"
            + "<bpmn:message id=\"m\" name=\"payment-received\"/>\n"
            + "<bpmn:process id=\"order-payment\" isExecutable=\"true\">\n<bpmn:startEvent id=\"s\"/>"
            + "<bpmn:endEvent id=\"e\"/>";
    private static final String TAIL = "</bpmn:process></bpmn:definitions>";

    private final SubsetBpmnReader reader = new SubsetBpmnReader(Limits.DEFAULTS);

    private static byte[] example() throws IOException {
        try (InputStream in = MessageElementsImportTest.class.getResourceAsStream("/bpmn/order-payment.bpmn")) {
            return in.readAllBytes();
        }
    }

    private static byte[] file(String body) {
        return (HEAD + body + TAIL).getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void PRE_237_the_worked_example_reads_exports_to_the_same_definition_and_compiles() throws IOException {
        Process process = reader.read(example());

        byte[] exported = new SubsetBpmnWriter().write(process);

        assertThat(CanonicalProcessWriter.canonicalText(reader.read(exported)))
                .isEqualTo(CanonicalProcessWriter.canonicalText(process));
        assertThat(CanonicalProcessWriter.canonicalText(process)).contains("\"message\":\"payment-received\"")
                .contains("\"correlationKey\":\"orderId\"").contains("\"type\":\"receiveTask\"");
        new ProcessCompiler(BehaviorCatalog.registry(), 500).compile("D1", process);
    }

    @Test
    void PRE_237_a_message_that_is_not_declared_or_a_waiting_step_without_a_key_is_refused_with_its_code() {
        byte[] noMessage = file("<bpmn:receiveTask id=\"w\" messageRef=\"nope\" horus:correlationKey=\"orderId\"/>");
        byte[] noKey = file("<bpmn:receiveTask id=\"w\" messageRef=\"m\"/>");

        assertThatThrownBy(() -> reader.read(noMessage)).isInstanceOfSatisfying(ValidationFailedException.class, e ->
                assertThat(e.problems()).extracting(DefinitionProblem::code).containsExactly("MESSAGE_REF_UNRESOLVED"));
        assertThatThrownBy(() -> reader.read(noKey)).isInstanceOfSatisfying(ValidationFailedException.class, e ->
                assertThat(e.problems()).extracting(DefinitionProblem::code).containsExactly("CORRELATION_KEY_REQUIRED"));
    }

    @Test
    void PRE_235_a_retried_send_step_hands_over_the_same_message_id() throws IOException {
        CompiledProcess compiled = new ProcessCompiler(BehaviorCatalog.registry(), 500).compile("D1",
                reader.read(example()));
        DefaultExecutionKernel kernel = new DefaultExecutionKernel(compiled, BehaviorCatalog.registry(),
                new V1ExpressionEvaluator(100));
        Clock clock = Clock.fixed(Instant.parse("2027-03-01T02:00:00Z"), ZoneOffset.UTC);
        Command start = new Command.Start(Map.of("orderId", "ORD-20481", "amount", "1250000"), "officer-lan");

        // The step transaction rolled back and the start runs again from the same state
        Transition first = kernel.apply(InstanceState.created("01JD0000000000000000000000", "bank-a", "D1", null),
                start, clock);
        Transition retry = kernel.apply(InstanceState.created("01JD0000000000000000000000", "bank-a", "D1", null),
                start, clock);

        Effect.ThrowMessage sent = first.effects().stream().filter(Effect.ThrowMessage.class::isInstance)
                .map(Effect.ThrowMessage.class::cast).findFirst().orElseThrow();
        assertThat(sent.messageName()).isEqualTo("payment-requested");
        assertThat(sent.correlationKey()).isEqualTo("ORD-20481");
        assertThat(sent.payload()).containsExactly(Map.entry("amount", "1250000"));
        assertThat(retry.effects()).contains(sent);
        assertThat(first.effects().stream().filter(Effect.ThrowMessage.class::isInstance)).hasSize(1);
    }
}
