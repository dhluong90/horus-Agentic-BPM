package io.github.dhluong90.bpm.process.bpmn.subset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import io.github.dhluong90.bpm.process.api.BpmnUnsupportedElementException;
import io.github.dhluong90.bpm.process.api.DefinitionProblem;
import io.github.dhluong90.bpm.process.api.ValidationFailedException;
import io.github.dhluong90.bpm.process.bpmn.xml.Limits;
import io.github.dhluong90.bpm.process.internal.exec.BehaviorCatalog;
import io.github.dhluong90.bpm.process.internal.exec.ProcessCompiler;
import io.github.dhluong90.bpm.process.internal.json.CanonicalProcessWriter;
import io.github.dhluong90.bpm.process.internal.model.bpmn.BoundaryEvent;
import io.github.dhluong90.bpm.process.internal.model.bpmn.Process;
import io.github.dhluong90.bpm.process.internal.model.bpmn.ReceiveTask;
import io.github.dhluong90.bpm.process.internal.model.bpmn.ServiceTask;
import io.github.dhluong90.bpm.process.internal.model.horus.HandlerRef;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The BPMN refusals that #632 lifts, on the reader, the compiler and the writer: a start event that is not
 * interrupting (PRE-382), a compensation activity that is not a service task (PRE-383), a receive task that starts the
 * process (PRE-384) and a service task that names its handler by {@code implementation} (PRE-385). The running
 * behaviour has its scenarios in the conformance suite and the PostgreSQL integration test.
 */
class RefusalsLiftedTest {

    private static final String HEAD = """
            <?xml version="1.0" encoding="UTF-8"?>
            <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                              xmlns:horus="urn:horus-bpm:bpmn:extensions:v1" id="d" targetNamespace="urn:t"
                              expressionLanguage="urn:horus-bpm:expr:v1">
              <bpmn:message id="m-app" name="loan-application"/>
              <bpmn:signal id="s-alert" name="alert"/>
              <bpmn:process id="loan" isExecutable="true">
            """;
    private static final String TAIL = "</bpmn:process></bpmn:definitions>";

    private final SubsetBpmnReader reader = new SubsetBpmnReader(Limits.DEFAULTS);

    private Process read(String xml) {
        return reader.read(xml.getBytes(StandardCharsets.UTF_8));
    }

    private List<String> codes(String xml) {
        try {
            new ProcessCompiler(BehaviorCatalog.registry(), 500).compile("D1", read(xml));
            return List.of();
        } catch (ValidationFailedException e) {
            return e.problems().stream().map(DefinitionProblem::code).toList();
        }
    }

    private void assertRoundTrip(Process process) {
        Process again = reader.read(new SubsetBpmnWriter().write(process));
        assertThat(CanonicalProcessWriter.canonicalText(again)).isEqualTo(CanonicalProcessWriter.canonicalText(process));
    }

    private static final String LINE = """
            <bpmn:endEvent id="end"/>
            <bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="work"/>
            <bpmn:sequenceFlow id="f2" sourceRef="work" targetRef="end"/>
            """;

    // ---- PRE-382 isInterrupting ---------------------------------------------------------------------------------

    @Test
    void PRE_382_isInterrupting_false_on_a_start_event_outside_an_event_sub_process_is_read_and_kept() {
        String xml = HEAD + """
                <bpmn:startEvent id="start" isInterrupting="false"/>
                <bpmn:task id="work"/>
                """ + LINE + TAIL;

        assertThat(codes(xml)).isEmpty();
        assertThat(CanonicalProcessWriter.canonicalText(read(xml))).contains("\"interrupting\":false");
        assertRoundTrip(read(xml));
        assertThat(new String(new SubsetBpmnWriter().write(read(xml)), StandardCharsets.UTF_8))
                .contains("isInterrupting=\"false\"");
    }

    @Test
    void PRE_382_a_boundary_event_may_say_isInterrupting_false_for_cancelActivity_false_but_not_both_ways() {
        String boundary = HEAD + """
                <bpmn:startEvent id="start"/>
                <bpmn:userTask id="work" horus:assignee="role:OPS" horus:resultVariable="r"/>
                <bpmn:boundaryEvent id="reminder" attachedToRef="work" %s>
                  <bpmn:timerEventDefinition><bpmn:timeDuration>PT24H</bpmn:timeDuration></bpmn:timerEventDefinition>
                </bpmn:boundaryEvent>
                <bpmn:endEvent id="remind-end"/>
                <bpmn:sequenceFlow id="f3" sourceRef="reminder" targetRef="remind-end"/>
                """ + LINE + TAIL;

        Process process = read(boundary.formatted("isInterrupting=\"false\""));
        assertThat(process.flowElements()).filteredOn(BoundaryEvent.class::isInstance)
                .extracting(e -> ((BoundaryEvent) e).cancelActivity()).containsExactly(false);
        assertRoundTrip(process);
        assertThat(codes(boundary.formatted("isInterrupting=\"false\" cancelActivity=\"true\"")))
                .contains("BOUNDARY_INTERRUPTING_CONFLICT");
    }

    @Test
    void PRE_382_isInterrupting_false_on_an_element_that_is_neither_a_start_nor_a_boundary_event_stays_refused() {
        String xml = HEAD + """
                <bpmn:startEvent id="start"/>
                <bpmn:task id="work" isInterrupting="false"/>
                """ + LINE + TAIL;

        assertThatThrownBy(() -> read(xml)).isInstanceOfSatisfying(
                BpmnUnsupportedElementException.class, e ->
                        assertThat(e.problems()).extracting(DefinitionProblem::element, DefinitionProblem::elementId)
                                .containsExactly(tuple("isInterrupting", "work")));
    }

    // ---- PRE-383 isForCompensation ------------------------------------------------------------------------------

    private static String compensationOn(String activity) {
        return HEAD + """
                <bpmn:startEvent id="start"/>
                <bpmn:serviceTask id="work" horus:handler="core-banking" horus:mode="remote"/>
                <bpmn:boundaryEvent id="work-undo" attachedToRef="work" cancelActivity="false">
                  <bpmn:compensateEventDefinition/>
                </bpmn:boundaryEvent>
                """ + activity + """
                <bpmn:association id="link" associationDirection="One" sourceRef="work-undo" targetRef="undo"/>
                """ + LINE + TAIL;
    }

    @Test
    void PRE_383_every_task_type_and_the_call_activity_can_be_a_compensation_activity_and_exports_back() {
        for (String activity : List.of(
                "<bpmn:userTask id=\"undo\" isForCompensation=\"true\"/>",
                "<bpmn:manualTask id=\"undo\" isForCompensation=\"true\"/>",
                "<bpmn:task id=\"undo\" isForCompensation=\"true\"/>",
                "<bpmn:sendTask id=\"undo\" isForCompensation=\"true\"/>",
                "<bpmn:scriptTask id=\"undo\" isForCompensation=\"true\"/>")) {
            String xml = compensationOn(activity);

            assertThat(codes(xml)).as(activity).isEmpty();
            assertThat(CanonicalProcessWriter.canonicalText(read(xml))).as(activity).contains("\"forCompensation\":true");
            assertRoundTrip(read(xml));
        }
    }

    @Test
    void PRE_383_a_manual_undo_refuses_the_content_it_would_never_run_with_a_named_code() {
        for (String activity : List.of(
                "<bpmn:userTask id=\"undo\" isForCompensation=\"true\" horus:assignee=\"role:OPS\"/>",
                "<bpmn:userTask id=\"undo\" isForCompensation=\"true\" horus:resultVariable=\"reversal\"/>",
                "<bpmn:sendTask id=\"undo\" isForCompensation=\"true\" messageRef=\"m-app\"/>",
                "<bpmn:scriptTask id=\"undo\" isForCompensation=\"true\" scriptFormat=\"urn:horus-bpm:expr:v1\">"
                        + "<bpmn:script>1</bpmn:script></bpmn:scriptTask>",
                "<bpmn:callActivity id=\"undo\" isForCompensation=\"true\" calledElement=\"reverse-disbursement\"/>")) {
            assertThatThrownBy(() -> read(compensationOn(activity))).as(activity).isInstanceOfSatisfying(
                    ValidationFailedException.class, e -> assertThat(e.problems()).extracting(DefinitionProblem::code,
                            DefinitionProblem::elementId).contains(tuple("COMPENSATION_ACTIVITY_CONTENT_UNUSED", "undo")));
        }
    }

    @Test
    void PRE_382_isInterrupting_false_outside_an_event_sub_process_gives_an_import_warning_naming_the_element() {
        String xml = HEAD + """
                <bpmn:startEvent id="start" isInterrupting="false"/>
                <bpmn:task id="work"/>
                """ + LINE + TAIL;

        assertThat(reader.readWithWarnings(xml.getBytes(StandardCharsets.UTF_8)).warnings())
                .extracting(DefinitionProblem::code, DefinitionProblem::elementId)
                .containsExactly(tuple("INTERRUPTING_HAS_NO_EFFECT", "start"));
    }

    @Test
    void PRE_382_isInterrupting_false_inside_an_event_sub_process_gives_no_warning() {
        String xml = HEAD + """
                <bpmn:startEvent id="start"/>
                <bpmn:task id="work"/>
                <bpmn:subProcess id="alert" triggeredByEvent="true">
                  <bpmn:startEvent id="alert-start" isInterrupting="false">
                    <bpmn:signalEventDefinition signalRef="s-alert"/>
                  </bpmn:startEvent>
                  <bpmn:endEvent id="alert-end"/>
                  <bpmn:sequenceFlow id="a1" sourceRef="alert-start" targetRef="alert-end"/>
                </bpmn:subProcess>
                """ + LINE + TAIL;

        assertThat(reader.readWithWarnings(xml.getBytes(StandardCharsets.UTF_8)).warnings())
                .extracting(DefinitionProblem::code).doesNotContain("INTERRUPTING_HAS_NO_EFFECT");
    }

    @Test
    void PRE_383_a_sub_process_or_a_gateway_is_not_a_compensation_activity() {
        String subProcess = HEAD + """
                <bpmn:startEvent id="start"/>
                <bpmn:subProcess id="work" isForCompensation="true">
                  <bpmn:startEvent id="s"/><bpmn:endEvent id="e"/>
                  <bpmn:sequenceFlow id="x" sourceRef="s" targetRef="e"/>
                </bpmn:subProcess>
                """ + LINE + TAIL;

        assertThatThrownBy(() -> read(subProcess)).isInstanceOfSatisfying(BpmnUnsupportedElementException.class, e ->
                assertThat(e.problems()).extracting(DefinitionProblem::element, DefinitionProblem::elementId)
                        .containsExactly(tuple("isForCompensation", "work")));
    }

    @Test
    void PRE_383_a_compensation_activity_keeps_its_rules_whatever_its_type() {
        // No association to it, and a sequence flow into it
        String unattached = HEAD + """
                <bpmn:startEvent id="start"/>
                <bpmn:task id="work"/>
                <bpmn:userTask id="undo" isForCompensation="true"/>
                <bpmn:sequenceFlow id="f3" sourceRef="work" targetRef="undo"/>
                """ + LINE + TAIL;

        assertThat(codes(unattached)).contains("COMPENSATION_ACTIVITY_UNATTACHED", "COMPENSATION_ACTIVITY_HAS_FLOW");
    }

    // ---- PRE-384 receiveTask instantiate ------------------------------------------------------------------------

    @Test
    void PRE_384_a_receive_task_with_instantiate_is_the_start_needs_no_key_and_exports_back() {
        String xml = HEAD + """
                <bpmn:receiveTask id="work" instantiate="true" messageRef="m-app"/>
                <bpmn:endEvent id="end"/>
                <bpmn:sequenceFlow id="f2" sourceRef="work" targetRef="end"/>
                """ + TAIL;

        assertThat(codes(xml)).isEmpty();
        assertThat(read(xml).flowElements()).filteredOn(ReceiveTask.class::isInstance)
                .extracting(e -> ((ReceiveTask) e).instantiate()).containsExactly(true);
        assertThat(CanonicalProcessWriter.canonicalText(read(xml))).contains("\"instantiate\":true");
        assertThat(new String(new SubsetBpmnWriter().write(read(xml)), StandardCharsets.UTF_8))
                .contains("instantiate=\"true\"");
        assertRoundTrip(read(xml));
    }

    @Test
    void PRE_384_a_receive_task_that_starts_the_process_is_refused_with_a_start_a_flow_in_a_key_or_a_scope() {
        String withStart = HEAD + """
                <bpmn:startEvent id="start"/>
                <bpmn:receiveTask id="work" instantiate="true" messageRef="m-app"/>
                """ + LINE + TAIL;
        String keyed = HEAD + """
                <bpmn:receiveTask id="work" instantiate="true" messageRef="m-app" horus:correlationKey="applicationId"/>
                <bpmn:endEvent id="end"/>
                <bpmn:sequenceFlow id="f2" sourceRef="work" targetRef="end"/>
                """ + TAIL;
        String inScope = HEAD + """
                <bpmn:startEvent id="start"/>
                <bpmn:subProcess id="work">
                  <bpmn:startEvent id="s"/>
                  <bpmn:receiveTask id="r" instantiate="true" messageRef="m-app"/>
                  <bpmn:endEvent id="e"/>
                  <bpmn:sequenceFlow id="x1" sourceRef="s" targetRef="r"/>
                  <bpmn:sequenceFlow id="x2" sourceRef="r" targetRef="e"/>
                </bpmn:subProcess>
                """ + LINE + TAIL;

        assertThat(codes(withStart)).contains("START_EVENT_COUNT", "RECEIVE_INSTANTIATE_INCOMING");
        assertThat(codes(keyed)).contains("CORRELATION_KEY_UNEXPECTED");
        assertThat(codes(inScope)).contains("RECEIVE_INSTANTIATE_SCOPE");
    }

    @Test
    void PRE_384_instantiate_on_a_send_task_is_refused() {
        String xml = HEAD + """
                <bpmn:startEvent id="start"/>
                <bpmn:sendTask id="work" instantiate="true" messageRef="m-app"/>
                """ + LINE + TAIL;

        assertThat(codes(xml)).contains("INSTANTIATE_ON_SEND_TASK");
    }

    // ---- PRE-385 implementation urn -----------------------------------------------------------------------------

    private static String serviceTask(String attributes) {
        return HEAD + """
                <bpmn:startEvent id="start"/>
                <bpmn:serviceTask id="work" %s/>
                """.formatted(attributes) + LINE + TAIL;
    }

    @Test
    void PRE_385_implementation_urn_names_the_handler_and_gives_the_same_definition_as_horus_handler() {
        Process byUrn = read(serviceTask("implementation=\"urn:horus-bpm:handler:kyc.check\" horus:mode=\"remote\""));
        Process byAttribute = read(serviceTask("horus:handler=\"kyc.check\" horus:mode=\"remote\""));

        assertThat(byUrn.flowElements()).filteredOn(ServiceTask.class::isInstance).singleElement()
                .satisfies(t -> assertThat(t.info().extensions().first(HandlerRef.class).orElseThrow().handler())
                        .isEqualTo("kyc.check"));
        assertThat(CanonicalProcessWriter.canonicalText(byUrn)).isEqualTo(CanonicalProcessWriter.canonicalText(byAttribute));
        assertThat(new String(new SubsetBpmnWriter().write(byUrn), StandardCharsets.UTF_8))
                .contains("horus:handler=\"kyc.check\"").doesNotContain("implementation=");
        assertRoundTrip(byUrn);
        // The same name on both is no conflict
        assertThat(codes(serviceTask("implementation=\"urn:horus-bpm:handler:kyc.check\" horus:handler=\"kyc.check\"")))
                .isEmpty();
    }

    @Test
    void PRE_385_two_different_names_or_an_invalid_name_are_refused() {
        assertThat(codes(serviceTask("implementation=\"urn:horus-bpm:handler:kyc.check\" horus:handler=\"aml.check\"")))
                .contains("IMPLEMENTATION_HANDLER_CONFLICT");
        assertThat(codes(serviceTask("implementation=\"urn:horus-bpm:handler:\"")))
                .contains("IMPLEMENTATION_HANDLER_INVALID");
        assertThat(codes(serviceTask("implementation=\"urn:horus-bpm:handler:check it\"")))
                .contains("IMPLEMENTATION_HANDLER_INVALID");
    }

    @Test
    void PRE_385_every_other_implementation_value_and_the_urn_on_other_tasks_stay_refused() {
        for (String xml : List.of(serviceTask("implementation=\"##WebService\" horus:handler=\"h\""),
                serviceTask("implementation=\"http://example.com/ws\" horus:handler=\"h\""),
                HEAD + "<bpmn:startEvent id=\"start\"/><bpmn:userTask id=\"work\" horus:assignee=\"role:OPS\" horus:resultVariable=\"r\""
                        + " implementation=\"urn:horus-bpm:handler:h\"/>" + LINE + TAIL)) {
            assertThatThrownBy(() -> read(xml)).isInstanceOfSatisfying(BpmnUnsupportedElementException.class, e ->
                    assertThat(e.problems()).extracting(DefinitionProblem::element, DefinitionProblem::elementId)
                            .containsExactly(tuple("implementation", "work")));
        }
    }
}
