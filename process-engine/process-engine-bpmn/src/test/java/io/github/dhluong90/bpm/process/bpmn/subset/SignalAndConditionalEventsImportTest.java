package io.github.dhluong90.bpm.process.bpmn.subset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.process.api.DefinitionProblem;
import io.github.dhluong90.bpm.process.api.ValidationFailedException;
import io.github.dhluong90.bpm.process.bpmn.xml.BpmnNamespaces;
import io.github.dhluong90.bpm.process.bpmn.xml.Limits;
import io.github.dhluong90.bpm.process.internal.json.CanonicalProcessWriter;
import io.github.dhluong90.bpm.process.internal.model.bpmn.ConditionalEventDefinition;
import io.github.dhluong90.bpm.process.internal.model.bpmn.FlowNode;
import io.github.dhluong90.bpm.process.internal.model.bpmn.IntermediateCatchEvent;
import io.github.dhluong90.bpm.process.internal.model.bpmn.Process;
import io.github.dhluong90.bpm.process.internal.model.bpmn.SignalEventDefinition;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** Signal and conditional events import with their signal name resolved, are refused when broken, and export back (#492). */
class SignalAndConditionalEventsImportTest {

    private static final String HEAD = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            + "<bpmn:definitions xmlns:bpmn=\"http://www.omg.org/spec/BPMN/20100524/MODEL\" "
            + "xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" "
            + "xmlns:horus=\"urn:horus-bpm:bpmn:extensions:v1\" id=\"d\" targetNamespace=\"urn:t\" "
            + "expressionLanguage=\"urn:horus-bpm:expr:v1\">";
    private static final String WATCHER = "<bpmn:process id=\"rate-watcher\" isExecutable=\"true\">"
            + "<bpmn:startEvent id=\"s\"/>"
            + "<bpmn:intermediateCatchEvent id=\"wait\"><bpmn:signalEventDefinition signalRef=\"Signal_rate\"/>"
            + "</bpmn:intermediateCatchEvent>"
            + "<bpmn:intermediateCatchEvent id=\"limit\"><bpmn:conditionalEventDefinition>"
            + "<bpmn:condition xsi:type=\"bpmn:tFormalExpression\">limit &gt; 0</bpmn:condition>"
            + "</bpmn:conditionalEventDefinition></bpmn:intermediateCatchEvent>"
            + "<bpmn:endEvent id=\"e\"><bpmn:signalEventDefinition signalRef=\"Signal_rate\"/></bpmn:endEvent>"
            + "<bpmn:sequenceFlow id=\"f1\" sourceRef=\"s\" targetRef=\"wait\"/>"
            + "<bpmn:sequenceFlow id=\"f2\" sourceRef=\"wait\" targetRef=\"limit\"/>"
            + "<bpmn:sequenceFlow id=\"f3\" sourceRef=\"limit\" targetRef=\"e\"/></bpmn:process>";
    private static final String SIGNAL = "<bpmn:signal id=\"Signal_rate\" name=\"rate-card-updated\"/>";
    private static final String TAIL = "</bpmn:definitions>";

    private final SubsetBpmnReader reader = new SubsetBpmnReader(Limits.DEFAULTS);
    private final SubsetBpmnWriter writer = new SubsetBpmnWriter();

    private Process read(String document) {
        return reader.read(document.getBytes(StandardCharsets.UTF_8));
    }

    private static FlowNode node(Process process, String id) {
        return process.flowElements().stream().filter(e -> e.id().equals(id)).map(FlowNode.class::cast).findFirst()
                .orElseThrow();
    }

    @Test
    void PRE_275_a_signal_event_carries_the_name_of_the_signal_it_refers_to_wherever_the_signal_is_declared() {
        Process process = read(HEAD + WATCHER + SIGNAL + TAIL);

        assertThat(((IntermediateCatchEvent) node(process, "wait")).eventDefinitions()).singleElement()
                .isInstanceOfSatisfying(SignalEventDefinition.class,
                        d -> assertThat(d.signalRef()).isEqualTo("rate-card-updated"));
        assertThat(((IntermediateCatchEvent) node(process, "limit")).eventDefinitions()).singleElement()
                .isInstanceOf(ConditionalEventDefinition.class);
    }

    @Test
    void PRE_275_a_signal_reference_to_no_signal_and_a_broken_condition_are_refused_by_name() {
        String broken = WATCHER.replace("Signal_rate", "Signal_missing").replace("limit &gt; 0", "limit &gt;&gt; (");

        assertThatThrownBy(() -> read(HEAD + broken + SIGNAL + TAIL)).isInstanceOfSatisfying(
                ValidationFailedException.class, e -> assertThat(e.problems()).extracting(DefinitionProblem::code)
                        .contains("SIGNAL_REF_INVALID", "CONDITION_INVALID"));
    }

    @Test
    void PRE_275_a_signal_name_outside_the_allowed_characters_is_refused() {
        assertThatThrownBy(() -> read(HEAD + WATCHER + "<bpmn:signal id=\"Signal_rate\" name=\"rate card!\"/>" + TAIL))
                .isInstanceOfSatisfying(ValidationFailedException.class, e -> assertThat(e.problems())
                        .extracting(DefinitionProblem::code).contains("SIGNAL_NAME_INVALID"));
    }

    @Test
    void PRE_275_the_worked_example_reads_and_compiles() throws java.io.IOException {
        byte[] example;
        try (java.io.InputStream in = getClass().getResourceAsStream("/bpmn/rate-card-watch.bpmn")) {
            example = in.readAllBytes();
        }

        Process process = reader.read(example);

        var compiled = new io.github.dhluong90.bpm.process.internal.exec.ProcessCompiler(
                io.github.dhluong90.bpm.process.internal.exec.BehaviorCatalog.registry(), 500).compile("D1", process);
        assertThat(compiled.node("wait-rate-card")).isPresent();
        assertThat(compiled.node("end")).isPresent();
    }

    @Test
    void PRE_275_the_export_of_signal_and_conditional_events_reads_back_to_the_same_definition() {
        Process source = read(HEAD + WATCHER + SIGNAL + TAIL);

        Process again = reader.read(writer.write(source));

        assertThat(CanonicalProcessWriter.canonicalText(again)).isEqualTo(CanonicalProcessWriter.canonicalText(source));
        assertThat(CanonicalProcessWriter.canonicalText(source)).contains("\"type\":\"signalCatch\"")
                .contains("\"signal\":\"rate-card-updated\"").contains("\"type\":\"conditionalCatch\"");
    }

    @Test
    void PRE_275_a_conditional_event_in_feel_reads_hashes_as_its_v1_spelling_and_exports_back() {
        String feel = WATCHER.replace("<bpmn:condition xsi:type=\"bpmn:tFormalExpression\">limit &gt; 0",
                "<bpmn:condition xsi:type=\"bpmn:tFormalExpression\" language=\"" + BpmnNamespaces.FEEL
                        + "\">limit &gt; 0 and limit != 5");
        String v1 = WATCHER.replace("limit &gt; 0", "limit &gt; 0 and limit != 5");

        Process source = read(HEAD + feel + SIGNAL + TAIL);

        assertThat(CanonicalProcessWriter.canonicalText(source))
                .isEqualTo(CanonicalProcessWriter.canonicalText(read(HEAD + v1 + SIGNAL + TAIL)));
        assertThat(CanonicalProcessWriter.canonicalText(reader.read(writer.write(source))))
                .isEqualTo(CanonicalProcessWriter.canonicalText(source));
        assertThatThrownBy(() -> read(HEAD + WATCHER.replace("limit &gt; 0", "limit &gt; 0 and (") + SIGNAL + TAIL))
                .isInstanceOf(ValidationFailedException.class);
    }
}
