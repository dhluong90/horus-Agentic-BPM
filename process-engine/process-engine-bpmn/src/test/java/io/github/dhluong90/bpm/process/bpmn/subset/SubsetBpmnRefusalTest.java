package io.github.dhluong90.bpm.process.bpmn.subset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import io.github.dhluong90.bpm.process.api.BpmnUnsafeXmlException;
import io.github.dhluong90.bpm.process.api.BpmnUnsupportedElementException;
import io.github.dhluong90.bpm.process.api.CmmnUnsupportedException;
import io.github.dhluong90.bpm.process.api.DefinitionProblem;
import io.github.dhluong90.bpm.process.api.ValidationFailedException;
import io.github.dhluong90.bpm.process.bpmn.xml.Limits;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** The BPMN reader refuses a CMMN file, and every element, attribute and language outside the stage, by name. */
class SubsetBpmnRefusalTest {

    private static final String HEAD = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            + "<bpmn:definitions xmlns:bpmn=\"http://www.omg.org/spec/BPMN/20100524/MODEL\" "
            + "xmlns:horus=\"urn:horus-bpm:bpmn:extensions:v1\" id=\"d\" targetNamespace=\"urn:t\">"
            + "<bpmn:process id=\"p\" isExecutable=\"true\">";
    private static final String TAIL = "</bpmn:process></bpmn:definitions>";

    private final SubsetBpmnReader reader = new SubsetBpmnReader(Limits.DEFAULTS);

    @Test
    void PRE_107_a_cmmn_file_is_refused_by_name_with_its_namespace_and_line() {
        // Given claim-case.cmmn, CMMN 1.1, with a casePlanModel on line 4
        byte[] file = bytes("claim-case.cmmn");

        // When it is imported, Then it is CMMN_UNSUPPORTED at the root, line 1
        assertThatThrownBy(() -> reader.read(file)).isInstanceOfSatisfying(CmmnUnsupportedException.class, e -> {
            assertThat(e.code()).isEqualTo("CMMN_UNSUPPORTED");
            assertThat(e).hasMessage("CMMN is not supported; model the case as a BPMN process");
            assertThat(e.namespace()).isEqualTo("http://www.omg.org/spec/CMMN/20151109/MODEL");
            assertThat(e.line()).isEqualTo(1);
        });
    }

    @Test
    void PRE_107_the_reader_stops_at_the_root_so_no_other_check_runs() {
        byte[] file = ("<definitions xmlns=\"http://www.omg.org/spec/CMMN/20121101/MODEL\"><case><unclosed>")
                .getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> reader.read(file)).isInstanceOf(CmmnUnsupportedException.class);
    }

    @Test
    void PRE_107_a_root_in_another_unknown_namespace_is_a_validation_failure_naming_the_namespace() {
        byte[] xpdl = "<definitions xmlns=\"http://www.wfmc.org/2008/XPDL2.2\"/>".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> reader.read(xpdl)).isInstanceOfSatisfying(ValidationFailedException.class, e ->
                assertThat(e.problems()).extracting(DefinitionProblem::code).containsExactly("NOT_BPMN"));
    }

    @Test
    void PRE_107_a_dtd_is_refused_before_the_namespace_is_looked_at() {
        byte[] file = ("<?xml version=\"1.0\"?><!DOCTYPE definitions [<!ENTITY x \"y\">]>"
                + "<definitions xmlns=\"http://www.omg.org/spec/CMMN/20151109/MODEL\"/>").getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> reader.read(file)).isInstanceOf(BpmnUnsafeXmlException.class);
    }

    @Test
    void PRE_108_every_element_and_language_outside_the_stage_is_listed_with_id_line_and_stage() {
        // Given loan.bpmn with a message boundary event (42), inclusive gateway (57), send task (63), ad-hoc sub-process (70)
        // and an XPath condition (88), and camunda:class on a service task (30)
        byte[] file = bytes("loan.bpmn");

        assertThatThrownBy(() -> reader.read(file)).isInstanceOfSatisfying(BpmnUnsupportedElementException.class, e -> {
            assertThat(e.code()).isEqualTo("BPMN_UNSUPPORTED_ELEMENT");
            assertThat(e.problems()).extracting(DefinitionProblem::element, DefinitionProblem::elementId,
                    DefinitionProblem::line, DefinitionProblem::stage).containsExactly(
                    tuple("boundaryEvent with messageEventDefinition", "be-timeout", 42, "S2"),
                    tuple("inclusiveGateway", "ig-1", 57, "S1"),
                    tuple("eventBasedGateway", "notify", 63, "S2"),
                    tuple("adHocSubProcess", "adhoc-1", 70, "S3"),
                    tuple("conditionExpression", "f2", 88, "not planned"));
            assertThat(e.problems().get(4).code()).isEqualTo("BPMN_EXPRESSION_LANGUAGE_UNSUPPORTED");
            assertThat(e.truncated()).isFalse();
        });
    }

    @Test
    void PRE_108_a_vendor_attribute_is_a_warning_with_its_namespace_and_line_and_is_not_refused() {
        byte[] file = bytes("loan.bpmn");

        assertThatThrownBy(() -> reader.read(file)).isInstanceOfSatisfying(BpmnUnsupportedElementException.class, e ->
                assertThat(e.warnings()).singleElement().satisfies(w -> {
                    assertThat(w.code()).isEqualTo("FOREIGN_EXTENSION_IGNORED");
                    assertThat(w.element()).isEqualTo("camunda:class");
                    assertThat(w.elementId()).isEqualTo("score");
                    assertThat(w.line()).isEqualTo(30);
                    assertThat(w.message()).contains("http://camunda.org/schema/1.0/bpmn");
                }));
    }

    @Test
    void PRE_108_a_file_with_only_a_vendor_attribute_is_not_refused() {
        byte[] file = (HEAD.replace("xmlns:horus", "xmlns:camunda=\"http://camunda.org/schema/1.0/bpmn\" xmlns:horus")
                + "<bpmn:startEvent id=\"s\"/><bpmn:serviceTask id=\"t\" camunda:class=\"x.Y\" horus:handler=\"h\"/>"
                + "<bpmn:endEvent id=\"e\"/><bpmn:sequenceFlow id=\"f1\" sourceRef=\"s\" targetRef=\"t\"/>"
                + "<bpmn:sequenceFlow id=\"f2\" sourceRef=\"t\" targetRef=\"e\"/>" + TAIL).getBytes(StandardCharsets.UTF_8);

        assertThat(reader.read(file).id()).isEqualTo("p");
    }

    @Test
    void PRE_108_a_successful_read_returns_the_vendor_attributes_it_ignored_as_warnings() {
        byte[] file = (HEAD.replace("xmlns:horus", "xmlns:camunda=\"http://camunda.org/schema/1.0/bpmn\" xmlns:horus")
                + "<bpmn:startEvent id=\"s\"/><bpmn:serviceTask id=\"t\" camunda:class=\"x.Y\" horus:handler=\"h\"/>"
                + "<bpmn:endEvent id=\"e\"/><bpmn:sequenceFlow id=\"f1\" sourceRef=\"s\" targetRef=\"t\"/>"
                + "<bpmn:sequenceFlow id=\"f2\" sourceRef=\"t\" targetRef=\"e\"/>" + TAIL).getBytes(StandardCharsets.UTF_8);

        var read = reader.readWithWarnings(file);

        assertThat(read.process().id()).isEqualTo("p");
        assertThat(read.warnings()).singleElement().satisfies(w -> {
            assertThat(w.code()).isEqualTo("FOREIGN_EXTENSION_IGNORED");
            assertThat(w.element()).isEqualTo("camunda:class");
            assertThat(w.elementId()).isEqualTo("t");
        });
    }

    @Test
    void PRE_108_a_file_without_vendor_attributes_reads_with_no_warnings() {
        byte[] file = (HEAD + "<bpmn:startEvent id=\"s\"/><bpmn:endEvent id=\"e\"/>"
                + "<bpmn:sequenceFlow id=\"f1\" sourceRef=\"s\" targetRef=\"e\"/>" + TAIL)
                .getBytes(StandardCharsets.UTF_8);

        assertThat(reader.readWithWarnings(file).warnings()).isEmpty();
    }

    @Test
    void PRE_108_a_throw_event_takes_its_stage_from_its_event_definition() {
        byte[] file = (HEAD + "<bpmn:startEvent id=\"s\"/>"
                + "<bpmn:intermediateThrowEvent id=\"cnc\"><bpmn:cancelEventDefinition/></bpmn:intermediateThrowEvent>"
                + "<bpmn:intermediateThrowEvent id=\"err\"><bpmn:errorEventDefinition/></bpmn:intermediateThrowEvent>"
                + "<bpmn:intermediateThrowEvent id=\"none\"/>" + TAIL).getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> reader.read(file)).isInstanceOfSatisfying(BpmnUnsupportedElementException.class, e ->
                assertThat(e.problems()).extracting(DefinitionProblem::elementId, DefinitionProblem::stage).containsExactly(
                        tuple("cnc", "not planned"), tuple("err", "not planned"), tuple("none", "S0")));
    }

    @Test
    void PRE_108_a_behaviour_attribute_that_changes_how_a_step_runs_is_refused_by_name() {
        byte[] file = (HEAD + "<bpmn:startEvent id=\"s\"/>"
                + "<bpmn:task id=\"undo\" isForCompensation=\"true\"/>"
                + "<bpmn:serviceTask id=\"batch\" completionQuantity=\"2\" horus:handler=\"h\"/>"
                + "<bpmn:serviceTask id=\"ws\" implementation=\"##WebService\" horus:handler=\"h\"/>"
                + TAIL).getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> reader.read(file)).isInstanceOfSatisfying(BpmnUnsupportedElementException.class, e ->
                assertThat(e.problems()).extracting(DefinitionProblem::element, DefinitionProblem::elementId,
                        DefinitionProblem::stage).containsExactly(
                        tuple("isForCompensation", "undo", "not planned"),
                        tuple("completionQuantity", "batch", "not planned"),
                        tuple("implementation", "ws", "not planned")));
    }

    @Test
    void PRE_108_at_most_100_entries_are_listed_and_the_rest_set_the_truncated_flag() {
        StringBuilder body = new StringBuilder("<bpmn:startEvent id=\"s\"/>");
        for (int i = 0; i < 130; i++) {
            body.append("<bpmn:inclusiveGateway id=\"g").append(i).append("\"/>");
        }

        assertThatThrownBy(() -> reader.read((HEAD + body + TAIL).getBytes(StandardCharsets.UTF_8)))
                .isInstanceOfSatisfying(BpmnUnsupportedElementException.class, e -> {
                    assertThat(e.problems()).hasSize(100);
                    assertThat(e.truncated()).isTrue();
                });
    }

    @Test
    void PRE_108_ids_lose_control_characters_and_are_cut_at_200_characters() {
        String id = "g".repeat(250) + "&#13;&#10;y";
        byte[] file = (HEAD + "<bpmn:inclusiveGateway id=\"a&#13;&#10;b\"/><bpmn:inclusiveGateway id=\"" + id + "\"/>"
                + TAIL).getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> reader.read(file)).isInstanceOfSatisfying(BpmnUnsupportedElementException.class, e -> {
            assertThat(e.problems().get(0).elementId()).isEqualTo("ab");
            assertThat(e.problems().get(1).elementId()).hasSize(200).doesNotContain("\r", "\n");
        });
    }

    @Test
    void PRE_108_no_stage_label_names_a_sprint_a_release_or_a_date() {
        byte[] file = (HEAD + "<bpmn:startEvent id=\"s\"/>"
                + "<bpmn:intermediateThrowEvent id=\"x\"/><bpmn:inclusiveGateway id=\"sc\"/><bpmn:choreographyTask id=\"ch\"/>"
                + "<bpmn:eventBasedGateway id=\"eg\"/>" + TAIL).getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> reader.read(file)).isInstanceOfSatisfying(BpmnUnsupportedElementException.class, e -> {
            assertThat(e.problems()).extracting(DefinitionProblem::stage)
                    .containsExactly("S0", "S1", "not planned", "S2");
            assertThat(e.problems()).extracting(DefinitionProblem::message)
                    .allSatisfy(m -> assertThat(m).doesNotContainIgnoringCase("sprint").doesNotContain("next")
                            .doesNotContain("later").doesNotContainPattern("20\\d\\d"));
        });
    }

    @Test
    void PRE_108_event_definitions_take_the_stage_of_their_kind() {
        // A compensation, timer, error or escalation start event reads: it starts an event sub-process (#487).
        byte[] file = (HEAD + "<bpmn:startEvent id=\"a\"><bpmn:compensateEventDefinition/></bpmn:startEvent>"
                + "<bpmn:startEvent id=\"b\"><bpmn:timerEventDefinition><bpmn:timeDuration>PT1H</bpmn:timeDuration>"
                + "</bpmn:timerEventDefinition></bpmn:startEvent>"
                + "<bpmn:endEvent id=\"c\"><bpmn:conditionalEventDefinition/></bpmn:endEvent>"
                + "<bpmn:startEvent id=\"d\"><bpmn:cancelEventDefinition/></bpmn:startEvent>" + TAIL)
                .getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> reader.read(file)).isInstanceOfSatisfying(BpmnUnsupportedElementException.class, e ->
                assertThat(e.problems()).extracting(DefinitionProblem::stage).containsExactly("S2", "not planned"));
    }

    private static byte[] bytes(String name) {
        try (InputStream in = SubsetBpmnRefusalTest.class.getResourceAsStream("/bpmn/" + name)) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
