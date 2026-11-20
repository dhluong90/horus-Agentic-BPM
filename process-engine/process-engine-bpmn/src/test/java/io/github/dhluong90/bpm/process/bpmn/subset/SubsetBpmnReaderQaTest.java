package io.github.dhluong90.bpm.process.bpmn.subset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

import io.github.dhluong90.bpm.process.api.BpmnUnsupportedElementException;
import io.github.dhluong90.bpm.process.api.DefinitionProblem;
import io.github.dhluong90.bpm.process.api.ProcessException;
import io.github.dhluong90.bpm.process.api.ValidationFailedException;
import io.github.dhluong90.bpm.process.bpmn.xml.Limits;
import io.github.dhluong90.bpm.process.internal.exec.BehaviorCatalog;
import io.github.dhluong90.bpm.process.internal.exec.ProcessCompiler;
import io.github.dhluong90.bpm.process.internal.model.bpmn.Process;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** QA review: BPMN refusal list and import validation edge cases of the subset reader and compiler. */
class SubsetBpmnReaderQaTest {

    private static final String HEAD = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<bpmn:definitions xmlns:bpmn=\"http://www.omg.org/spec/BPMN/20100524/MODEL\" "
            + "xmlns:horus=\"urn:horus-bpm:bpmn:extensions:v1\" id=\"d\" targetNamespace=\"urn:t\"";

    private final SubsetBpmnReader reader = new SubsetBpmnReader(Limits.DEFAULTS);
    private final ProcessCompiler compiler = new ProcessCompiler(BehaviorCatalog.registry(), 500);

    private Process importAndCompile(String xml) {
        Process process = reader.read(xml.getBytes(StandardCharsets.UTF_8));
        compiler.compile("D1", process);
        return process;
    }

    @Test
    void PRE_033_a_sequence_flow_without_source_ref_is_a_validation_problem_not_a_null_pointer() {
        String xml = HEAD + ">\n"
                + "  <bpmn:process id=\"p\" isExecutable=\"true\">\n"
                + "    <bpmn:startEvent id=\"start\"/>\n"
                + "    <bpmn:endEvent id=\"end\"/>\n"
                + "    <bpmn:sequenceFlow id=\"f1\" sourceRef=\"start\" targetRef=\"end\"/>\n"
                + "    <bpmn:sequenceFlow id=\"f2\" targetRef=\"end\"/>\n"
                + "  </bpmn:process>\n"
                + "</bpmn:definitions>\n";

        assertThatThrownBy(() -> importAndCompile(xml))
                .isInstanceOf(ValidationFailedException.class)
                .satisfies(e -> assertThat(((ValidationFailedException) e).problems())
                        .extracting(DefinitionProblem::code, DefinitionProblem::elementId)
                        .contains(tuple("FLOW_REF_INVALID", "f2")));
    }

    @Test
    void PRE_033_a_document_level_feel_expression_language_is_refused_for_conditions_without_their_own_language() {
        // BPMN 2.0: a formal expression without its own language uses definitions@expressionLanguage.
        String xml = HEAD + " expressionLanguage=\"https://www.omg.org/spec/FEEL/20140401\">\n"
                + "  <bpmn:process id=\"p\" isExecutable=\"true\">\n"
                + "    <bpmn:startEvent id=\"start\"/>\n"
                + "    <bpmn:exclusiveGateway id=\"g\" default=\"g__b\"/>\n"
                + "    <bpmn:endEvent id=\"a\"/>\n"
                + "    <bpmn:endEvent id=\"b\"/>\n"
                + "    <bpmn:sequenceFlow id=\"s\" sourceRef=\"start\" targetRef=\"g\"/>\n"
                + "    <bpmn:sequenceFlow id=\"g__a\" sourceRef=\"g\" targetRef=\"a\">\n"
                + "      <bpmn:conditionExpression>amount &gt; 5</bpmn:conditionExpression>\n"
                + "    </bpmn:sequenceFlow>\n"
                + "    <bpmn:sequenceFlow id=\"g__b\" sourceRef=\"g\" targetRef=\"b\"/>\n"
                + "  </bpmn:process>\n"
                + "</bpmn:definitions>\n";

        assertThatThrownBy(() -> importAndCompile(xml))
                .isInstanceOf(BpmnUnsupportedElementException.class)
                .hasMessageContaining("FEEL");
    }

    @Test
    void PRE_033_an_unsupported_element_written_over_several_lines_is_reported_at_the_line_where_it_starts() {
        String xml = HEAD + ">\n"                                              // line 2
                + "  <bpmn:process id=\"p\" isExecutable=\"true\">\n"          // line 3
                + "    <bpmn:startEvent id=\"start\"/>\n"                      // line 4
                + "    <bpmn:userTask id=\"review\" horus:assignee=\"role:X\"/>\n" // line 5
                + "    <bpmn:boundaryEvent id=\"be-timeout\"\n"               // line 6
                + "        attachedToRef=\"review\"\n"                         // line 7
                + "        cancelActivity=\"true\"/>\n"                        // line 8
                + "  </bpmn:process>\n"
                + "</bpmn:definitions>\n";

        assertThatThrownBy(() -> reader.read(xml.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(BpmnUnsupportedElementException.class)
                .satisfies(e -> assertThat(((BpmnUnsupportedElementException) e).problems())
                        .extracting(DefinitionProblem::element, DefinitionProblem::elementId, DefinitionProblem::line)
                        .contains(tuple("boundaryEvent", "be-timeout", 6)));
    }

    @Test
    void PRE_033_a_flow_element_without_an_id_is_refused_by_name() {
        String xml = HEAD + ">\n"
                + "  <bpmn:process id=\"p\" isExecutable=\"true\">\n"
                + "    <bpmn:startEvent id=\"start\"/>\n"
                + "    <bpmn:endEvent id=\"end\"/>\n"
                + "    <bpmn:sequenceFlow sourceRef=\"start\" targetRef=\"end\"/>\n"
                + "  </bpmn:process>\n"
                + "</bpmn:definitions>\n";

        assertThatThrownBy(() -> importAndCompile(xml))
                .isInstanceOf(ProcessException.class)
                .hasMessageContaining("id");
    }

    @Test
    void PRE_004_a_default_flow_that_also_carries_a_condition_is_refused() {
        // BPMN 2.0: the default flow of an exclusive gateway must not have a condition.
        String xml = HEAD + ">\n"
                + "  <bpmn:process id=\"p\" isExecutable=\"true\">\n"
                + "    <bpmn:startEvent id=\"start\"/>\n"
                + "    <bpmn:exclusiveGateway id=\"g\" default=\"g__b\"/>\n"
                + "    <bpmn:endEvent id=\"a\"/>\n"
                + "    <bpmn:endEvent id=\"b\"/>\n"
                + "    <bpmn:sequenceFlow id=\"s\" sourceRef=\"start\" targetRef=\"g\"/>\n"
                + "    <bpmn:sequenceFlow id=\"g__a\" sourceRef=\"g\" targetRef=\"a\">\n"
                + "      <bpmn:conditionExpression>x == 1</bpmn:conditionExpression>\n"
                + "    </bpmn:sequenceFlow>\n"
                + "    <bpmn:sequenceFlow id=\"g__b\" sourceRef=\"g\" targetRef=\"b\">\n"
                + "      <bpmn:conditionExpression>x == 2</bpmn:conditionExpression>\n"
                + "    </bpmn:sequenceFlow>\n"
                + "  </bpmn:process>\n"
                + "</bpmn:definitions>\n";

        assertThatThrownBy(() -> importAndCompile(xml)).isInstanceOf(ValidationFailedException.class);
    }

    @Test
    void PRE_033_a_condition_typed_as_tFormalExpression_reads_like_the_sample() {
        // The schema-valid form of the sample's conditions; must keep working when the sample is fixed.
        String xml = HEAD + " xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">\n"
                + "  <bpmn:process id=\"p\" isExecutable=\"true\">\n"
                + "    <bpmn:startEvent id=\"start\"/>\n"
                + "    <bpmn:exclusiveGateway id=\"g\" default=\"g__b\"/>\n"
                + "    <bpmn:endEvent id=\"a\"/>\n"
                + "    <bpmn:endEvent id=\"b\"/>\n"
                + "    <bpmn:sequenceFlow id=\"s\" sourceRef=\"start\" targetRef=\"g\"/>\n"
                + "    <bpmn:sequenceFlow id=\"g__a\" sourceRef=\"g\" targetRef=\"a\">\n"
                + "      <bpmn:conditionExpression xsi:type=\"bpmn:tFormalExpression\" "
                + "language=\"urn:horus-bpm:expr:v1\">fee == 0 VND</bpmn:conditionExpression>\n"
                + "    </bpmn:sequenceFlow>\n"
                + "    <bpmn:sequenceFlow id=\"g__b\" sourceRef=\"g\" targetRef=\"b\"/>\n"
                + "  </bpmn:process>\n"
                + "</bpmn:definitions>\n";

        Process process = importAndCompile(xml);

        assertThat(process.flowElements()).isNotEmpty();
    }
}
