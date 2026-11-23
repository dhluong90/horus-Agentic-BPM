package io.github.dhluong90.bpm.process.bpmn.subset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.process.api.BpmnUnsafeXmlException;
import io.github.dhluong90.bpm.process.api.BpmnUnsupportedElementException;
import io.github.dhluong90.bpm.process.api.DefinitionProblem;
import io.github.dhluong90.bpm.process.api.DefinitionTooLargeException;
import io.github.dhluong90.bpm.process.bpmn.xml.Limits;
import io.github.dhluong90.bpm.process.internal.exec.BehaviorCatalog;
import io.github.dhluong90.bpm.process.internal.exec.CompiledProcess;
import io.github.dhluong90.bpm.process.internal.exec.ProcessCompiler;
import io.github.dhluong90.bpm.process.internal.model.bpmn.BusinessRuleTask;
import io.github.dhluong90.bpm.process.internal.model.bpmn.ExclusiveGateway;
import io.github.dhluong90.bpm.process.internal.model.bpmn.Process;
import io.github.dhluong90.bpm.process.internal.model.horus.DecisionRef;
import io.github.dhluong90.bpm.process.internal.model.horus.InputMapping;
import java.io.IOException;
import java.io.InputStream;
import org.junit.jupiter.api.Test;

class SubsetBpmnReaderTest {

    private final SubsetBpmnReader reader = new SubsetBpmnReader(Limits.DEFAULTS);

    @Test
    void sample_reads_into_the_model_and_compiles() {
        Process process = reader.read(bytes("card-fee-review.bpmn"));

        assertThat(process.id()).isEqualTo("card-fee-review");
        CompiledProcess compiled = new ProcessCompiler(BehaviorCatalog.registry(), 500).compile("D1", process);
        BusinessRuleTask price = (BusinessRuleTask) compiled.node("price-card").orElseThrow();
        assertThat(price.extensions().first(DecisionRef.class)).map(DecisionRef::ruleSet).contains("card-fee");
        assertThat(price.extensions().all(InputMapping.class)).extracting(InputMapping::name).containsExactly("tier", "txCount");
        assertThat(((ExclusiveGateway) compiled.node("fee-check").orElseThrow()).defaultFlow()).isEqualTo("fee-check__review");
        assertThat(compiled.flow("fee-check__auto").orElseThrow().conditionExpression().text()).isEqualTo("pricing.fee == 0 VND");
        assertThat(compiled.node("price-card").orElseThrow().incoming()).containsExactly("load-profile__price-card");
    }

    @Test
    void unsupported_elements_are_named_with_id_and_line() {
        assertThatThrownBy(() -> reader.read(bytes("unsupported.bpmn")))
                .isInstanceOf(BpmnUnsupportedElementException.class)
                .satisfies(e -> assertThat(((BpmnUnsupportedElementException) e).problems())
                        .extracting(DefinitionProblem::element, DefinitionProblem::elementId, DefinitionProblem::line)
                        .contains(org.assertj.core.groups.Tuple.tuple("boundaryEvent", "be-timeout", 6),
                                org.assertj.core.groups.Tuple.tuple("dataObject", "do-1", 7),
                                org.assertj.core.groups.Tuple.tuple("endEvent with linkEventDefinition", "end", 8)));
    }

    @Test
    void doctype_and_external_entities_are_refused_and_never_resolved() {
        assertThatThrownBy(() -> reader.read(bytes("xxe.bpmn"))).isInstanceOf(BpmnUnsafeXmlException.class);
    }

    @Test
    void a_condition_in_another_expression_language_is_refused() {
        assertThatThrownBy(() -> reader.read(bytes("feel-condition.bpmn")))
                .isInstanceOf(BpmnUnsupportedElementException.class)
                .hasMessageContaining("XPath");
    }

    @Test
    void size_and_depth_limits_apply() {
        assertThatThrownBy(() -> new SubsetBpmnReader(new Limits(100, 32, 5000, 0, 65_536, 4_096)).read(bytes("card-fee-review.bpmn")))
                .isInstanceOf(DefinitionTooLargeException.class);
        assertThatThrownBy(() -> new SubsetBpmnReader(new Limits(1_048_576, 2, 5000, 0, 65_536, 4_096)).read(bytes("card-fee-review.bpmn")))
                .isInstanceOf(BpmnUnsafeXmlException.class);
    }

    private static final String HEAD = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            + "<bpmn:definitions xmlns:bpmn=\"http://www.omg.org/spec/BPMN/20100524/MODEL\" "
            + "xmlns:horus=\"urn:horus-bpm:bpmn:extensions:v1\" id=\"d\" targetNamespace=\"urn:t\">"
            + "<bpmn:process id=\"p\" isExecutable=\"true\">";
    private static final String TAIL = "</bpmn:process></bpmn:definitions>";

    private static byte[] doc(String body) {
        return (HEAD + body + TAIL).getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    @Test
    void a_remote_service_step_reads_with_its_timeout_and_bad_modes_or_timeouts_are_refused() {
        Process process = reader.read(doc("<bpmn:startEvent id=\"s\"/>"
                + "<bpmn:serviceTask id=\"bureau\" horus:handler=\"creditBureau.score\" horus:mode=\"remote\""
                + " horus:timeout=\"PT2S\"/><bpmn:endEvent id=\"e\"/>"
                + "<bpmn:sequenceFlow id=\"f1\" sourceRef=\"s\" targetRef=\"bureau\"/>"
                + "<bpmn:sequenceFlow id=\"f2\" sourceRef=\"bureau\" targetRef=\"e\"/>"));
        io.github.dhluong90.bpm.process.internal.model.horus.HandlerRef ref = process.flowElements().stream()
                .filter(e -> e.id().equals("bureau")).findFirst().orElseThrow()
                .extensions().first(io.github.dhluong90.bpm.process.internal.model.horus.HandlerRef.class).orElseThrow();
        assertThat(ref.mode()).isEqualTo("remote");
        assertThat(ref.timeout()).isEqualTo(java.time.Duration.ofSeconds(2));

        for (String bad : new String[] {"horus:mode=\"later\"", "horus:timeout=\"PT2S\"",
                "horus:mode=\"remote\" horus:timeout=\"two seconds\"", "horus:mode=\"remote\" horus:timeout=\"PT0S\""}) {
            assertThatThrownBy(() -> reader.read(doc("<bpmn:startEvent id=\"s\"/>"
                    + "<bpmn:serviceTask id=\"bureau\" horus:handler=\"creditBureau.score\" " + bad + "/>"
                    + "<bpmn:sequenceFlow id=\"f1\" sourceRef=\"s\" targetRef=\"bureau\"/>")))
                    .as(bad)
                    .isInstanceOf(io.github.dhluong90.bpm.process.api.ValidationFailedException.class)
                    .satisfies(e -> assertThat(((io.github.dhluong90.bpm.process.api.ValidationFailedException) e)
                            .problems()).extracting(DefinitionProblem::code).containsAnyOf("MODE_INVALID",
                                    "TIMEOUT_INVALID"));
        }
    }

    @Test
    void only_utf8_is_read_so_the_stored_source_is_what_was_parsed() {
        byte[] latin1 = ("<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?>" + HEAD.substring(HEAD.indexOf("?>") + 2)
                + "<bpmn:startEvent id=\"s\" name=\"caf\u00e9\"/>" + TAIL).getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        assertThatThrownBy(() -> reader.read(latin1)).isInstanceOf(BpmnUnsafeXmlException.class);
    }

    @Test
    void an_entity_reference_inside_a_condition_is_refused() {
        byte[] withEntity = doc("<bpmn:startEvent id=\"s\"/><bpmn:endEvent id=\"e\"/>"
                + "<bpmn:sequenceFlow id=\"f\" sourceRef=\"s\" targetRef=\"e\"><bpmn:conditionExpression>a == &x;"
                + "</bpmn:conditionExpression></bpmn:sequenceFlow>");
        assertThatThrownBy(() -> reader.read(withEntity)).isInstanceOf(BpmnUnsafeXmlException.class);
    }

    @Test
    void attribute_and_element_count_limits_hold_at_the_boundary() {
        assertThatThrownBy(() -> reader.read(doc("<bpmn:startEvent id=\"" + "a".repeat(4_097) + "\"/>")))
                .isInstanceOf(BpmnUnsafeXmlException.class);
        String many = "<bpmn:documentation/>".repeat(4_998);
        assertThatThrownBy(() -> reader.read(doc(many + "<bpmn:documentation/>")))
                .as("5,001 elements").isInstanceOf(BpmnUnsafeXmlException.class);
    }

    @Test
    void echoed_ids_are_cut_at_200_characters_and_lose_control_characters() {
        String id = "x".repeat(200) + "&#13;&#10;y";
        assertThatThrownBy(() -> reader.read(doc("<bpmn:dataObject id=\"" + id + "\"/>")))
                .isInstanceOf(BpmnUnsupportedElementException.class)
                .satisfies(e -> assertThat(((BpmnUnsupportedElementException) e).problems().get(0).elementId())
                        .hasSizeLessThanOrEqualTo(200).doesNotContain("\r", "\n"));
    }

    @Test
    void an_unresolved_event_definition_ref_is_refused_naming_the_reference_element_and_line() {
        byte[] bpmn = (HEAD + "\n<bpmn:startEvent id=\"start\">\n<bpmn:eventDefinitionRef>missing-timer</bpmn:eventDefinitionRef>"
                + "\n</bpmn:startEvent>" + TAIL).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertThatThrownBy(() -> reader.read(bpmn))
                .isInstanceOf(io.github.dhluong90.bpm.process.api.ValidationFailedException.class)
                .satisfies(e -> assertThat(((io.github.dhluong90.bpm.process.api.ValidationFailedException) e).problems())
                        .anySatisfy(p -> {
                            assertThat(p.code()).isEqualTo("EVENT_DEFINITION_REF_UNRESOLVED");
                            assertThat(p.message()).contains("missing-timer");
                            assertThat(p.element()).isEqualTo("startEvent");
                            assertThat(p.elementId()).isEqualTo("start");
                            assertThat(p.line()).isEqualTo(3);
                        }));
    }

    @Test
    void a_user_task_without_a_result_variable_is_refused() {
        assertThatThrownBy(() -> reader.read(doc("<bpmn:userTask id=\"u\" horus:assignee=\"role:OFFICER\"/>")))
                .isInstanceOf(io.github.dhluong90.bpm.process.api.ValidationFailedException.class)
                .hasMessageContaining("resultVariable");
    }

    private static byte[] bytes(String name) {
        try (InputStream in = SubsetBpmnReaderTest.class.getResourceAsStream("/bpmn/" + name)) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
