package io.github.dhluong90.bpm.process.bpmn.subset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.process.api.DefinitionProblem;
import io.github.dhluong90.bpm.process.api.ValidationFailedException;
import io.github.dhluong90.bpm.process.bpmn.xml.Limits;
import io.github.dhluong90.bpm.process.internal.json.CanonicalProcessWriter;
import io.github.dhluong90.bpm.process.internal.model.bpmn.CallActivity;
import io.github.dhluong90.bpm.process.internal.model.bpmn.FlowElement;
import io.github.dhluong90.bpm.process.internal.model.bpmn.Process;
import io.github.dhluong90.bpm.process.internal.model.horus.CalledVersion;
import io.github.dhluong90.bpm.process.internal.model.horus.InputMapping;
import io.github.dhluong90.bpm.process.internal.model.horus.OutputMapping;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * A {@code callActivity} is read as a {@code callProcess} step (#92, PRE-204, PD-127): the called key, the pinned version,
 * the inputs it passes and the outputs it brings back; passing all variables is refused; a call inside a saga declares
 * its undo; and the export reads back to the same definition hash. A BPMN diagram is read as a layout (#445, PRE-208).
 */
class CallActivityBpmnTest {

    private static final String HEAD = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            + "<bpmn:definitions xmlns:bpmn=\"http://www.omg.org/spec/BPMN/20100524/MODEL\" "
            + "xmlns:bpmndi=\"http://www.omg.org/spec/BPMN/20100524/DI\" xmlns:dc=\"http://www.omg.org/spec/DD/20100524/DC\" "
            + "xmlns:di=\"http://www.omg.org/spec/DD/20100524/DI\" "
            + "xmlns:horus=\"urn:horus-bpm:bpmn:extensions:v1\" id=\"d\" targetNamespace=\"urn:t\">"
            + "<bpmn:process id=\"account-opening\" name=\"Account opening\" isExecutable=\"true\">";
    private static final String TAIL = "</bpmn:process></bpmn:definitions>";
    private static final String FLOW = "<bpmn:startEvent id=\"start\"/>%s<bpmn:endEvent id=\"end\"/>"
            + "<bpmn:sequenceFlow id=\"f1\" sourceRef=\"start\" targetRef=\"kyc\"/>"
            + "<bpmn:sequenceFlow id=\"f2\" sourceRef=\"kyc\" targetRef=\"end\"/>";
    private static final String CALL = "<bpmn:callActivity id=\"kyc\" calledElement=\"kyc-check\" "
            + "horus:calledVersion=\"6\"><bpmn:extensionElements>"
            + "<horus:input name=\"customerId\" value=\"${instance.customerId}\"/>"
            + "<horus:output name=\"kycLevel\" from=\"kycLevel\"/></bpmn:extensionElements></bpmn:callActivity>";

    private final SubsetBpmnReader reader = new SubsetBpmnReader(Limits.DEFAULTS);

    private static byte[] file(String body) {
        return (HEAD + body + TAIL).getBytes(StandardCharsets.UTF_8);
    }

    private static CallActivity call(Process process) {
        return process.flowElements().stream().filter(CallActivity.class::isInstance).map(CallActivity.class::cast)
                .findFirst().orElseThrow();
    }

    @Test
    void PRE_204_a_call_activity_is_read_with_its_called_key_version_inputs_and_outputs() {
        // When a callActivity names the key, pins version 6, passes one input and brings one output back
        Process process = reader.read(file(FLOW.formatted(CALL)));

        // Then the step carries them, and the canonical form is a callProcess step
        CallActivity step = call(process);
        assertThat(step.calledElement()).isEqualTo("kyc-check");
        assertThat(step.extensions().first(CalledVersion.class)).get().extracting(CalledVersion::version).isEqualTo(6);
        assertThat(step.extensions().all(InputMapping.class)).singleElement()
                .satisfies(i -> assertThat(i.name()).isEqualTo("customerId"));
        assertThat(step.extensions().all(OutputMapping.class)).singleElement()
                .satisfies(o -> assertThat(o.from()).isEqualTo("kycLevel"));
        assertThat(CanonicalProcessWriter.write(process).toString()).contains("\"type\":\"callProcess\"")
                .contains("\"process\":\"kyc-check\"").contains("\"version\":6").contains("\"outputs\":{\"kycLevel\":\"kycLevel\"}");
    }

    @Test
    void PRE_204_the_export_of_a_call_activity_reads_back_to_the_same_definition_hash() {
        // Given a process with a call activity
        Process process = reader.read(file(FLOW.formatted(CALL)));

        // When it is exported and read again
        byte[] exported = new SubsetBpmnWriter().write(process);
        Process again = reader.read(exported);

        // Then the canonical text, and so the hash, is the same
        assertThat(CanonicalProcessWriter.canonicalText(again)).isEqualTo(CanonicalProcessWriter.canonicalText(process));
    }

    @Test
    void PRE_204_a_call_activity_without_a_called_key_or_passing_all_variables_is_refused_by_name() {
        // When a callActivity has no calledElement, and another uses horus:in="all"
        // Then each is 422 VALIDATION_FAILED naming the problem
        assertThatThrownBy(() -> reader.read(file(FLOW.formatted("<bpmn:callActivity id=\"kyc\"/>"))))
                .isInstanceOfSatisfying(ValidationFailedException.class, e -> assertThat(e.problems())
                        .extracting(DefinitionProblem::code).contains("CALLED_ELEMENT_INVALID"));
        assertThatThrownBy(() -> reader.read(file(FLOW.formatted(
                "<bpmn:callActivity id=\"kyc\" calledElement=\"kyc-check\" horus:in=\"all\"/>"))))
                .isInstanceOfSatisfying(ValidationFailedException.class, e -> assertThat(e.problems())
                        .extracting(DefinitionProblem::code).contains("CALL_INPUT_ALL_REFUSED"));
    }

    @Test
    void PRE_207_a_call_inside_a_saga_must_declare_its_undo_or_declare_it_manual() {
        // Given a saga with a callProcess step that has no horus:compensate
        String saga = "<bpmn:startEvent id=\"start\"/><bpmn:transaction id=\"onboard\"><bpmn:startEvent id=\"s2\"/>"
                + "%s<bpmn:endEvent id=\"e2\"/><bpmn:sequenceFlow id=\"a\" sourceRef=\"s2\" targetRef=\"kyc\"/>"
                + "<bpmn:sequenceFlow id=\"b\" sourceRef=\"kyc\" targetRef=\"e2\"/></bpmn:transaction>"
                + "<bpmn:endEvent id=\"end\"/><bpmn:sequenceFlow id=\"c\" sourceRef=\"start\" targetRef=\"onboard\"/>"
                + "<bpmn:sequenceFlow id=\"d\" sourceRef=\"onboard\" targetRef=\"end\"/>";

        // When it is imported
        // Then it is refused naming the step, and accepted with a handler or with manual
        assertThatThrownBy(() -> reader.read(file(saga.formatted(
                "<bpmn:callActivity id=\"kyc\" calledElement=\"kyc-check\"/>"))))
                .isInstanceOfSatisfying(ValidationFailedException.class, e -> assertThat(e.problems())
                        .extracting(DefinitionProblem::code).contains("COMPENSATION_REQUIRED"));
        assertThat(reader.read(file(saga.formatted(
                "<bpmn:callActivity id=\"kyc\" calledElement=\"kyc-check\" horus:compensate=\"reverse-kyc\"/>"))))
                .isNotNull();
        assertThat(reader.read(file(saga.formatted(
                "<bpmn:callActivity id=\"kyc\" calledElement=\"kyc-check\" horus:compensate=\"manual\"/>")))).isNotNull();
    }

    @Test
    void PRE_208_a_diagram_is_read_as_a_layout_of_the_steps_and_flows_it_draws_and_nothing_else() {
        // Given a diagram with shapes for two steps, one of an unknown element, a flow with two waypoints, and a shape
        // out of range
        String diagram = "<bpmndi:BPMNDiagram id=\"dg\"><bpmndi:BPMNPlane id=\"pl\" bpmnElement=\"account-opening\">"
                + "<bpmndi:BPMNShape id=\"a\" bpmnElement=\"start\"><dc:Bounds x=\"10\" y=\"20\" width=\"36\" height=\"36\"/>"
                + "</bpmndi:BPMNShape><bpmndi:BPMNShape id=\"b\" bpmnElement=\"kyc\"><dc:Bounds x=\"100.50\" y=\"20\" "
                + "width=\"100\" height=\"80\"/></bpmndi:BPMNShape><bpmndi:BPMNShape id=\"c\" bpmnElement=\"ghost\">"
                + "<dc:Bounds x=\"1\" y=\"1\" width=\"1\" height=\"1\"/></bpmndi:BPMNShape><bpmndi:BPMNShape id=\"d\" "
                + "bpmnElement=\"end\"><dc:Bounds x=\"999999\" y=\"1\" width=\"36\" height=\"36\"/></bpmndi:BPMNShape>"
                + "<bpmndi:BPMNEdge id=\"e\" bpmnElement=\"f1\"><di:waypoint x=\"46\" y=\"38\"/><di:waypoint x=\"100\" y=\"38\"/>"
                + "</bpmndi:BPMNEdge></bpmndi:BPMNPlane></bpmndi:BPMNDiagram>";
        byte[] bytes = (HEAD + FLOW.formatted(CALL) + "</bpmn:process>" + diagram + "</bpmn:definitions>")
                .getBytes(StandardCharsets.UTF_8);
        Process process = reader.read(bytes);

        // When the layout is read
        String layout = new BpmnLayoutReader(Limits.DEFAULTS).read(bytes, process);

        // Then it holds the two valid steps and the flow; the unknown and the out-of-range shapes are dropped
        assertThat(layout).startsWith("{\"schemaVersion\":1").contains("\"start\":{\"x\":10,\"y\":20,\"width\":36")
                .contains("\"kyc\":{\"x\":100.5,\"y\":20,\"width\":100,\"height\":80}")
                .contains("\"start->kyc\":[{\"x\":46,\"y\":38},{\"x\":100,\"y\":38}]")
                .doesNotContain("ghost").doesNotContain("\"end\":");
        // and a file with no diagram has none
        assertThat(new BpmnLayoutReader(Limits.DEFAULTS).read(file(FLOW.formatted(CALL)), process)).isNull();
    }
}
