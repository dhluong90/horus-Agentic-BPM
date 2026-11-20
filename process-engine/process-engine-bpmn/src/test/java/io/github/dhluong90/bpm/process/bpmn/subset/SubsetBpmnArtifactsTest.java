package io.github.dhluong90.bpm.process.bpmn.subset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

import io.github.dhluong90.bpm.process.api.BpmnUnsupportedElementException;
import io.github.dhluong90.bpm.process.api.DefinitionProblem;
import io.github.dhluong90.bpm.process.api.ValidationFailedException;
import io.github.dhluong90.bpm.process.bpmn.xml.Limits;
import io.github.dhluong90.bpm.process.internal.exec.BehaviorCatalog;
import io.github.dhluong90.bpm.process.internal.exec.CompiledProcess;
import io.github.dhluong90.bpm.process.internal.exec.ProcessCompiler;
import io.github.dhluong90.bpm.process.internal.json.CanonicalProcessWriter;
import io.github.dhluong90.bpm.process.internal.model.bpmn.Association;
import io.github.dhluong90.bpm.process.internal.model.bpmn.AssociationDirection;
import io.github.dhluong90.bpm.process.internal.model.bpmn.Group;
import io.github.dhluong90.bpm.process.internal.model.bpmn.Process;
import io.github.dhluong90.bpm.process.internal.model.bpmn.TextAnnotation;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import javax.xml.XMLConstants;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;
import org.junit.jupiter.api.Test;

/**
 * Text annotations, associations and groups, which BPMN modellers add to a diagram, are accepted at import, carried
 * in the model as artifacts, never executed, kept out of the definition hash, and kept by the BPMN export.
 */
class SubsetBpmnArtifactsTest {

    private static final String HEAD = """
            <?xml version="1.0" encoding="UTF-8"?>
            <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                              xmlns:horus="urn:horus-bpm:bpmn:extensions:v1"
                              id="d1" targetNamespace="urn:test">
              <bpmn:process id="card-fee-check" isExecutable="true">
                <bpmn:startEvent id="start"/>
                <bpmn:businessRuleTask id="price" name="Price the card" horus:ruleSet="card-fee" horus:resultVariable="pricing"/>
                <bpmn:endEvent id="end"/>
                <bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="price"/>
                <bpmn:sequenceFlow id="f2" sourceRef="price" targetRef="end"/>
            """;

    private static final String PLAIN = HEAD + """
              </bpmn:process>
            </bpmn:definitions>
            """;

    private static final String ANNOTATED = HEAD + """
                <bpmn:textAnnotation id="note-gold">
                  <bpmn:text>Gold customers pay 0 VND &amp; Silver customers pay 50,000 VND</bpmn:text>
                </bpmn:textAnnotation>
                <bpmn:association id="link-gold" associationDirection="None" sourceRef="price" targetRef="note-gold"/>
              </bpmn:process>
            </bpmn:definitions>
            """;

    private static final String GROUPED = HEAD + """
                <bpmn:group id="pricing-group" categoryValueRef="pricing-value"/>
              </bpmn:process>
              <bpmn:category id="pricing-category">
                <bpmn:categoryValue id="pricing-value" value="Card pricing"/>
              </bpmn:category>
            </bpmn:definitions>
            """;

    private final SubsetBpmnReader reader = new SubsetBpmnReader(Limits.DEFAULTS);
    private final SubsetBpmnWriter writer = new SubsetBpmnWriter();

    @Test
    void a_text_annotation_and_its_association_are_accepted_and_kept_as_artifacts() {
        Process process = reader.read(utf8(ANNOTATED));

        assertThat(process.artifacts()).hasSize(2);
        TextAnnotation note = (TextAnnotation) process.artifacts().get(0);
        assertThat(note.id()).isEqualTo("note-gold");
        assertThat(note.text()).isEqualTo("Gold customers pay 0 VND & Silver customers pay 50,000 VND");
        Association link = (Association) process.artifacts().get(1);
        assertThat(link).extracting(Association::id, Association::sourceRef, Association::targetRef,
                Association::associationDirection).containsExactly("link-gold", "price", "note-gold",
                AssociationDirection.NONE);
    }

    @Test
    void a_group_is_accepted_with_its_category_value_label() {
        Process process = reader.read(utf8(GROUPED));

        assertThat(process.artifacts()).singleElement().isInstanceOf(Group.class);
        Group group = (Group) process.artifacts().get(0);
        assertThat(group.id()).isEqualTo("pricing-group");
        assertThat(group.categoryValueRef()).isEqualTo("pricing-value");
        assertThat(SubsetBpmnReader.categoryValueLabel(group)).isEqualTo("Card pricing");
    }

    @Test
    void artifacts_are_skipped_at_run_time_and_do_not_change_the_definition_hash() {
        Process plain = reader.read(utf8(PLAIN));
        Process annotated = reader.read(utf8(ANNOTATED));
        Process grouped = reader.read(utf8(GROUPED));

        assertThat(CanonicalProcessWriter.canonicalText(annotated)).isEqualTo(CanonicalProcessWriter.canonicalText(plain));
        assertThat(CanonicalProcessWriter.canonicalText(grouped)).isEqualTo(CanonicalProcessWriter.canonicalText(plain));
        CompiledProcess compiled = new ProcessCompiler(BehaviorCatalog.registry(), 500).compile("D1", annotated);
        assertThat(compiled.node("note-gold")).isEmpty();
        assertThat(compiled.node("price").orElseThrow().outgoing()).containsExactly("f2");
    }

    @Test
    void an_artifact_id_that_repeats_a_flow_element_id_is_refused() {
        String clash = ANNOTATED.replace("id=\"note-gold\"", "id=\"price\"");

        assertThatThrownBy(() -> reader.read(utf8(clash))).isInstanceOf(ValidationFailedException.class)
                .satisfies(e -> assertThat(((ValidationFailedException) e).problems())
                        .extracting(DefinitionProblem::code, DefinitionProblem::elementId)
                        .contains(tuple("DUPLICATE_ID", "price")));
    }

    @Test
    void elements_inside_an_artifact_other_than_text_and_documentation_are_still_refused() {
        String nested = ANNOTATED.replace("<bpmn:text>", "<bpmn:startEvent id=\"hidden\"/><bpmn:text>");

        assertThatThrownBy(() -> reader.read(utf8(nested))).isInstanceOf(BpmnUnsupportedElementException.class)
                .satisfies(e -> assertThat(((BpmnUnsupportedElementException) e).problems())
                        .extracting(DefinitionProblem::element).contains("textAnnotation child startEvent"));
    }

    @Test
    void a_modeller_file_with_annotation_association_group_and_layout_imports() {
        Process process = reader.read(resource("modeller-annotated.bpmn"));

        assertThat(process.id()).isEqualTo("loan-limit-check");
        assertThat(process.artifacts()).extracting(a -> a.getClass().getSimpleName(), a -> a.id())
                .containsExactly(tuple("TextAnnotation", "TextAnnotation_0a1b2c3"),
                        tuple("Association", "Association_1d2e3f4"), tuple("Group", "Group_0g5h6j7"));
        assertThat(SubsetBpmnReader.categoryValueLabel((Group) process.artifacts().get(2))).isEqualTo("Credit checks");
        new ProcessCompiler(BehaviorCatalog.registry(), 500).compile("D1", process);
    }

    @Test
    void the_bpmn_export_keeps_annotations_associations_and_groups() throws Exception {
        Process source = reader.read(resource("modeller-annotated.bpmn"));

        byte[] exported = writer.write(source);
        Process again = reader.read(exported);

        assertThat(again.artifacts()).isEqualTo(source.artifacts());
        assertThat(CanonicalProcessWriter.canonicalText(again)).isEqualTo(CanonicalProcessWriter.canonicalText(source));
        String xml = new String(exported, StandardCharsets.UTF_8);
        assertThat(xml).contains("<bpmn:text>Limit is 500,000,000 VND for retail customers</bpmn:text>")
                .contains("<bpmn:categoryValue id=\"CategoryValue_1k8l9m0\" value=\"Credit checks\"/>")
                .contains("bpmnElement=\"TextAnnotation_0a1b2c3\"")
                .contains("bpmnElement=\"Association_1d2e3f4\"")
                .contains("bpmnElement=\"Group_0g5h6j7\"");
        assertThat(writer.write(again)).isEqualTo(exported);
        validate(exported);
    }

    @Test
    void the_bpmn_export_keeps_inline_annotations_and_groups() throws Exception {
        for (String document : new String[] {ANNOTATED, GROUPED}) {
            Process source = reader.read(utf8(document));

            byte[] exported = writer.write(source);

            assertThat(reader.read(exported).artifacts()).isEqualTo(source.artifacts());
            validate(exported);
        }
    }

    private static void validate(byte[] document) throws Exception {
        SchemaFactory factory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
        factory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "file,jar");
        URL xsd = SubsetBpmnArtifactsTest.class.getResource("/xsd/bpmn20/BPMN20.xsd");
        Validator validator = factory.newSchema(xsd).newValidator();
        validator.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        validator.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        validator.validate(new StreamSource(new ByteArrayInputStream(document)));
    }

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] resource(String name) {
        try (InputStream in = SubsetBpmnArtifactsTest.class.getResourceAsStream("/bpmn/" + name)) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
