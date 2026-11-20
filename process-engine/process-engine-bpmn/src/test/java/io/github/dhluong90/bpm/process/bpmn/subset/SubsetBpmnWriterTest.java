package io.github.dhluong90.bpm.process.bpmn.subset;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dhluong90.bpm.process.bpmn.xml.Limits;
import io.github.dhluong90.bpm.process.internal.exec.BehaviorCatalog;
import io.github.dhluong90.bpm.process.internal.exec.ProcessCompiler;
import io.github.dhluong90.bpm.process.internal.json.CanonicalProcessWriter;
import io.github.dhluong90.bpm.process.internal.json.Hashes;
import io.github.dhluong90.bpm.process.internal.model.bpmn.FlowNode;
import io.github.dhluong90.bpm.process.internal.model.bpmn.Process;
import io.github.dhluong90.bpm.process.internal.model.bpmn.SequenceFlow;
import io.github.dhluong90.bpm.process.internal.model.bpmn.ServiceTask;
import io.github.dhluong90.bpm.process.internal.model.horus.HandlerRef;
import io.github.dhluong90.bpm.process.internal.model.horus.InputMapping;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import javax.xml.XMLConstants;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * PRE-034: a definition exported as BPMN imports back to the same definition hash, validates against the BPMN 2.0
 * XSD, and opens in modellers. Fixtures: every importable example and test definition.
 */
class SubsetBpmnWriterTest {

    private static final Path EXAMPLES = Path.of(System.getProperty("bpm.root", "../.."), "examples");

    private final SubsetBpmnReader reader = new SubsetBpmnReader(Limits.DEFAULTS);
    private final SubsetBpmnWriter writer = new SubsetBpmnWriter();

    /** Every importable definition: the example processes under {@code examples/} and the test fixtures. */
    static Stream<String> definitions() throws IOException {
        List<String> examples;
        try (Stream<Path> files = Files.walk(EXAMPLES)) {
            examples = files.filter(p -> p.toString().endsWith(".bpmn") && p.toString().contains("src/main"))
                    .map(Path::toString).sorted().toList();
        }
        assertThat(examples).as("example definitions under examples/").isNotEmpty();
        return Stream.concat(examples.stream(), Stream.of("classpath:card-fee-review.bpmn",
                "classpath:kyc-onboarding.bpmn"));
    }

    @ParameterizedTest
    @MethodSource("definitions")
    void PRE_034_bpmn_export_round_trip_preserves_hash(String definition) {
        Process source = reader.read(bytes(definition));

        Process again = reader.read(writer.write(source));

        assertThat(CanonicalProcessWriter.canonicalText(again)).isEqualTo(CanonicalProcessWriter.canonicalText(source));
        assertThat(hash(again)).isEqualTo(hash(source));
        new ProcessCompiler(BehaviorCatalog.registry(), 500).compile("D1", again);
    }

    @ParameterizedTest
    @MethodSource("definitions")
    void PRE_034_exported_xml_validates_against_the_bpmn_2_0_xsd(String definition) throws Exception {
        byte[] exported = writer.write(reader.read(bytes(definition)));

        Validator validator = bpmnSchema().newValidator();
        validator.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        validator.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        validator.validate(new StreamSource(new ByteArrayInputStream(exported)));
    }

    @Test
    void the_xsd_check_refuses_a_document_the_schema_does_not_allow() throws Exception {
        String invalid = "<bpmn:definitions xmlns:bpmn=\"http://www.omg.org/spec/BPMN/20100524/MODEL\" id=\"d\">"
                + "<bpmn:process id=\"p\"/></bpmn:definitions>";

        Validator validator = bpmnSchema().newValidator();

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> validator.validate(new StreamSource(
                new ByteArrayInputStream(invalid.getBytes(StandardCharsets.UTF_8)))))
                .isInstanceOf(org.xml.sax.SAXParseException.class).hasMessageContaining("targetNamespace");
    }

    @ParameterizedTest
    @MethodSource("definitions")
    void PRE_034_export_is_deterministic_and_stable_across_round_trips(String definition) {
        Process source = reader.read(bytes(definition));
        byte[] first = writer.write(source);

        assertThat(writer.write(source)).isEqualTo(first);
        assertThat(writer.write(reader.read(first))).isEqualTo(first);
    }

    @Test
    void PRE_034_kyc_onboarding_keeps_markup_line_breaks_and_vietnamese_text_exactly() {
        Process source = reader.read(bytes("classpath:kyc-onboarding.bpmn"));

        Process again = reader.read(writer.write(source));

        assertThat(again.name()).isEqualTo("KYC onboarding & review");
        FlowNode start = node(again, "start");
        assertThat(start.name()).isEqualTo("Mở tài khoản");
        ServiceTask check = (ServiceTask) node(again, "check-documents");
        assertThat(check.name()).isEqualTo("Check \"ID\" <and> address\ndocuments");
        assertThat(check.extensions().first(HandlerRef.class)).map(HandlerRef::mode).contains("local");
        assertThat(check.extensions().all(InputMapping.class)).extracting(InputMapping::value)
                .containsExactly("${instance.customerId}", "tab\tand & ampersand");
        SequenceFlow low = again.flowElements().stream().filter(e -> e.id().equals("risk-level__low"))
                .map(SequenceFlow.class::cast).findFirst().orElseThrow();
        assertThat(low.conditionExpression().text()).isEqualTo("risk.score < 30 and documents.complete == true");
        assertThat(low.name()).isEqualTo("score < 30");
    }

    @Test
    void PRE_034_export_has_incoming_outgoing_and_a_shape_and_edge_for_every_node_and_flow() {
        Process source = reader.read(bytes("classpath:kyc-onboarding.bpmn"));

        String xml = new String(writer.write(source), StandardCharsets.UTF_8);

        long nodes = source.flowElements().stream().filter(FlowNode.class::isInstance).count();
        long flows = source.flowElements().stream().filter(SequenceFlow.class::isInstance).count();
        assertThat(count(xml, "<bpmndi:BPMNShape ")).isEqualTo(nodes);
        assertThat(count(xml, "<bpmndi:BPMNEdge ")).isEqualTo(flows);
        assertThat(xml).contains("<bpmn:incoming>request__check</bpmn:incoming>")
                .contains("horus:fourEyes=\"true\"")
                .contains("horus:assignee=\"group:branch-ops\"")
                // Foreign extensions are not part of the definition, so they are not exported.
                .doesNotContain("acme");
    }

    // ---- helpers ------------------------------------------------------------------------------------------

    private static Schema bpmnSchema() throws Exception {
        SchemaFactory factory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
        // The OMG schema files are local test resources; nothing is fetched over the network.
        factory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "file,jar");
        URL xsd = SubsetBpmnWriterTest.class.getResource("/xsd/bpmn20/BPMN20.xsd");
        return factory.newSchema(xsd);
    }

    private static FlowNode node(Process process, String id) {
        return process.flowElements().stream().filter(e -> e.id().equals(id)).map(FlowNode.class::cast).findFirst()
                .orElseThrow();
    }

    private static long count(String text, String needle) {
        return text.split(java.util.regex.Pattern.quote(needle), -1).length - 1L;
    }

    private static String hash(Process process) {
        return Hashes.sha256(CanonicalProcessWriter.canonicalText(process));
    }

    private static byte[] bytes(String definition) {
        try {
            if (definition.startsWith("classpath:")) {
                try (InputStream in = SubsetBpmnWriterTest.class.getResourceAsStream("/bpmn/"
                        + definition.substring("classpath:".length()))) {
                    return in.readAllBytes();
                }
            }
            return Files.readAllBytes(Path.of(definition));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
