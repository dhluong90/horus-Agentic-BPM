package io.github.dhluong90.bpm.process.bpmn.subset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.process.api.DefinitionProblem;
import io.github.dhluong90.bpm.process.api.ValidationFailedException;
import io.github.dhluong90.bpm.process.bpmn.xml.Limits;
import io.github.dhluong90.bpm.process.internal.json.CanonicalProcessWriter;
import io.github.dhluong90.bpm.process.internal.model.bpmn.Process;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Reading what modellers write (#632 follow-up): a declared encoding other than UTF-8, ids that are not kebab-case,
 * a process that never says {@code isExecutable}, and an {@code import} or {@code itemDefinition} the engine does not
 * use. Each is read, or refused with its reason, and the export restores what it can.
 */
class ModellerFileReadingTest {

    private final SubsetBpmnReader reader = new SubsetBpmnReader(Limits.DEFAULTS);

    private static final String NS = "xmlns:bpmn=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
            + " xmlns:horus=\"urn:horus-bpm:bpmn:extensions:v1\" id=\"d\" targetNamespace=\"urn:t\""
            + " expressionLanguage=\"urn:horus-bpm:expr:v1\"";

    private static String process(String id, String attributes, String extra) {
        return "<bpmn:definitions " + NS + ">" + extra + "<bpmn:process id=\"" + id + "\" " + attributes + ">"
                + "<bpmn:startEvent id=\"start\"/><bpmn:task id=\"check\" name=\"Kiem tra ho so\"/>"
                + "<bpmn:endEvent id=\"end\"/>"
                + "<bpmn:sequenceFlow id=\"f1\" sourceRef=\"start\" targetRef=\"check\"/>"
                + "<bpmn:sequenceFlow id=\"f2\" sourceRef=\"check\" targetRef=\"end\"/>"
                + "</bpmn:process></bpmn:definitions>";
    }

    private Process read(String xml) {
        return reader.read(xml.getBytes(StandardCharsets.UTF_8));
    }

    private List<String> codes(String xml) {
        try {
            read(xml);
            return List.of();
        } catch (ValidationFailedException e) {
            return e.problems().stream().map(DefinitionProblem::code).toList();
        }
    }

    // ---- PRE-386 encoding ---------------------------------------------------------------------------------------

    @Test
    void PRE_386_a_file_in_iso_8859_1_is_read_as_the_text_it_declares_and_exports_as_utf_8() {
        String xml = "<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?>"
                + process("kyc-review", "isExecutable=\"true\"", "").replace("Kiem tra ho so", "Kiém tra hồ sơ".replace("ồ", "o").replace("ơ", "o"));

        Process process = reader.read(xml.getBytes(StandardCharsets.ISO_8859_1));

        assertThat(process.flowElements()).extracting(e -> e.getClass().getSimpleName()).contains("Task");
        assertThat(process.flowElements().stream().filter(e -> "check".equals(e.id())).findFirst().orElseThrow())
                .extracting(e -> ((io.github.dhluong90.bpm.process.internal.model.bpmn.Task) e).name())
                .isEqualTo("Kiém tra ho so");
        byte[] exported = new SubsetBpmnWriter().write(process);
        assertThat(new String(exported, StandardCharsets.UTF_8)).contains("encoding=\"UTF-8\"")
                .contains("Kiém tra ho so");
    }

    @Test
    void PRE_386_windows_1252_and_us_ascii_declarations_are_read_and_a_mismatch_is_refused() {
        String euro = "<?xml version='1.0' encoding='windows-1252'?>"
                + process("kyc-review", "isExecutable=\"true\"", "").replace("Kiem tra ho so", "Fee € 5");
        assertThat(reader.read(euro.getBytes(Charset.forName("windows-1252"))).flowElements()).isNotEmpty();

        // UTF-8 bytes under a US-ASCII declaration, and a UTF-16 declaration, are refused
        String ascii = "<?xml version=\"1.0\" encoding=\"US-ASCII\"?>"
                + process("kyc-review", "isExecutable=\"true\"", "").replace("Kiem tra ho so", "Nguyễn");
        assertThatThrownBy(() -> reader.read(ascii.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(io.github.dhluong90.bpm.process.api.BpmnUnsafeXmlException.class);
        String utf16 = "<?xml version=\"1.0\" encoding=\"UTF-16\"?>" + process("kyc-review", "isExecutable=\"true\"", "");
        assertThatThrownBy(() -> reader.read(utf16.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(io.github.dhluong90.bpm.process.api.BpmnUnsafeXmlException.class);
    }

    // ---- PRE-387 ids --------------------------------------------------------------------------------------------

    @Test
    void PRE_387_a_process_id_that_is_not_a_key_is_mapped_deterministically_and_the_export_restores_it() {
        String xml = process("_3d1ef204-2d4c-4643-8fc5-c319cc032ec0", "isExecutable=\"true\"", "");

        Process process = read(xml);

        assertThat(process.id()).isEqualTo("p-3d1ef204-2d4c-4643-8fc5-c319cc032ec0");
        assertThat(read(xml).id()).isEqualTo(process.id());
        String exported = new String(new SubsetBpmnWriter().write(process), StandardCharsets.UTF_8);
        assertThat(exported).contains("<bpmn:process id=\"_3d1ef204-2d4c-4643-8fc5-c319cc032ec0\"")
                .doesNotContain("p-3d1ef204");
        // the hash is made of the key, so the restored id reads back to the same definition
        assertThat(CanonicalProcessWriter.canonicalText(reader.read(exported.getBytes(StandardCharsets.UTF_8))))
                .isEqualTo(CanonicalProcessWriter.canonicalText(process));
        assertThat(read(process("Loan_Review", "isExecutable=\"true\"", "")).id()).isEqualTo("loan-review");
    }

    @Test
    void PRE_387_two_processes_that_map_to_one_key_are_refused_and_a_key_is_never_changed() {
        String collaboration = "<bpmn:definitions " + NS + "><bpmn:collaboration id=\"c\">"
                + "<bpmn:participant id=\"p1\" processRef=\"Loan_Review\"/><bpmn:participant id=\"p2\" processRef=\"loan-review\"/>"
                + "</bpmn:collaboration>"
                + "<bpmn:process id=\"Loan_Review\" isExecutable=\"true\"><bpmn:startEvent id=\"a\"/><bpmn:endEvent id=\"b\"/>"
                + "<bpmn:sequenceFlow id=\"f1\" sourceRef=\"a\" targetRef=\"b\"/></bpmn:process>"
                + "<bpmn:process id=\"loan-review\" isExecutable=\"true\"><bpmn:startEvent id=\"c1\"/><bpmn:endEvent id=\"d1\"/>"
                + "<bpmn:sequenceFlow id=\"f2\" sourceRef=\"c1\" targetRef=\"d1\"/></bpmn:process></bpmn:definitions>";

        assertThat(codes(collaboration)).contains("PROCESS_ID_COLLISION");
        assertThat(read(process("card-fee-review", "isExecutable=\"true\"", "")).id()).isEqualTo("card-fee-review");
    }

    @Test
    void PRE_387_a_message_with_no_name_takes_its_mapped_id_and_a_lane_set_without_an_id_is_read() {
        String xml = process("kyc-review", "isExecutable=\"true\"",
                "<bpmn:message id=\"Message_1404332496319\"/><bpmn:message id=\"_9f\"/>");
        assertThat(codes(xml)).isEmpty();

        String twin = process("kyc-review", "isExecutable=\"true\"",
                "<bpmn:message id=\"m1\" name=\"Message_9\"/><bpmn:message id=\"Message_9\"/>");
        assertThat(codes(twin)).contains("MESSAGE_NAME_COLLISION");

        String lanes = "<bpmn:definitions " + NS + "><bpmn:process id=\"kyc-review\" isExecutable=\"true\">"
                + "<bpmn:laneSet><bpmn:lane name=\"Operations\"><bpmn:flowNodeRef>check</bpmn:flowNodeRef></bpmn:lane></bpmn:laneSet>"
                + "<bpmn:startEvent id=\"start\"/><bpmn:userTask id=\"check\" horus:resultVariable=\"r\"/><bpmn:endEvent id=\"end\"/>"
                + "<bpmn:sequenceFlow id=\"f1\" sourceRef=\"start\" targetRef=\"check\"/>"
                + "<bpmn:sequenceFlow id=\"f2\" sourceRef=\"check\" targetRef=\"end\"/></bpmn:process></bpmn:definitions>";
        assertThat(read(lanes).laneSets()).singleElement().satisfies(set -> assertThat(set.id()).startsWith("laneSet_line"));
    }

    // ---- PRE-388 isExecutable -----------------------------------------------------------------------------------

    @Test
    void PRE_388_a_process_that_does_not_say_isExecutable_is_executable_and_false_stays_refused() {
        assertThat(codes(process("kyc-review", "", ""))).isEmpty();
        assertThat(codes(process("kyc-review", "isExecutable=\"false\"", ""))).contains("NOT_EXECUTABLE");
    }

    // ---- PRE-389 import and itemDefinition ----------------------------------------------------------------------

    @Test
    void PRE_389_an_import_and_an_item_definition_are_ignored_with_a_warning_and_not_refused() {
        String xml = process("kyc-review", "isExecutable=\"true\"",
                "<bpmn:import importType=\"http://www.w3.org/2001/XMLSchema\" location=\"types.xsd\" namespace=\"urn:x\"/>"
                        + "<bpmn:itemDefinition id=\"i1\" structureRef=\"string\"/>");

        SubsetBpmnReader.Result result = reader.readWithWarnings(xml.getBytes(StandardCharsets.UTF_8));

        assertThat(result.process().id()).isEqualTo("kyc-review");
        assertThat(result.warnings()).extracting(DefinitionProblem::code)
                .contains("BPMN_IMPORT_IGNORED", "ITEM_DEFINITION_IGNORED");
    }
}
