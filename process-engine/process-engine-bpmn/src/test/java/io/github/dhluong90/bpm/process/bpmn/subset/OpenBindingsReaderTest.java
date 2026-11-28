package io.github.dhluong90.bpm.process.bpmn.subset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.process.api.DefinitionProblem;
import io.github.dhluong90.bpm.process.api.ValidationFailedException;
import io.github.dhluong90.bpm.process.bpmn.xml.Limits;
import io.github.dhluong90.bpm.process.internal.service.DefinitionReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * PRE-390 and PRE-391: the reader that serves an import lists a binding that is missing, and a process that says it is
 * not executable, instead of refusing the file; the strict read still refuses both.
 */
class OpenBindingsReaderTest {

    private static final String NS = "xmlns:bpmn=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
            + " xmlns:horus=\"urn:horus-bpm:bpmn:extensions:v1\" id=\"d\" targetNamespace=\"urn:t\"";

    private final SubsetBpmnReader reader = new SubsetBpmnReader(Limits.DEFAULTS);

    private static byte[] file(String executable, String steps, String flows, String last) {
        return ("<bpmn:definitions " + NS + "><bpmn:process id=\"invoice-approval\" " + executable + ">"
                + "<bpmn:startEvent id=\"s\"/>" + steps + "<bpmn:endEvent id=\"e\"/>"
                + "<bpmn:sequenceFlow id=\"f0\" sourceRef=\"s\" targetRef=\"" + last + "\"/>" + flows
                + "</bpmn:process></bpmn:definitions>").getBytes(StandardCharsets.UTF_8);
    }

    private static List<String> codes(List<DefinitionProblem> problems) {
        return problems.stream().map(DefinitionProblem::code).toList();
    }

    @Test
    void PRE_390_a_missing_assignee_result_variable_and_handler_are_listed_not_refused() {
        byte[] xml = file("isExecutable=\"true\"",
                "<bpmn:serviceTask id=\"post\" implementation=\"##WebService\"/><bpmn:userTask id=\"approve\"/>",
                "<bpmn:sequenceFlow id=\"f1\" sourceRef=\"post\" targetRef=\"approve\"/>"
                        + "<bpmn:sequenceFlow id=\"f2\" sourceRef=\"approve\" targetRef=\"e\"/>", "post");

        DefinitionReader.Result result = reader.readWithWarnings(xml);

        assertThat(codes(result.openBindings())).containsExactlyInAnyOrder("IMPLEMENTATION_UNBOUND", "HANDLER_MISSING",
                "ASSIGNEE_INVALID", "RESULT_VARIABLE_REQUIRED");
        assertThat(result.openBindings()).extracting(DefinitionProblem::elementId)
                .containsExactlyInAnyOrder("post", "post", "approve", "approve");
        // the draft can be written out again
        assertThat(new SubsetBpmnWriter().write(result.process())).isNotEmpty();
        // the strict read refuses the same file
        assertThatThrownBy(() -> reader.read(xml)).isInstanceOf(RuntimeException.class);
    }

    @Test
    void PRE_390_a_message_wait_without_a_correlation_key_is_listed_and_a_wrong_value_is_still_refused() {
        byte[] open = file("isExecutable=\"true\"",
                "<bpmn:receiveTask id=\"wait\" messageRef=\"m\"/>",
                "<bpmn:sequenceFlow id=\"f1\" sourceRef=\"wait\" targetRef=\"e\"/>", "wait");
        String withMessage = new String(open, StandardCharsets.UTF_8).replace("<bpmn:process",
                "<bpmn:message id=\"m\" name=\"payment-received\"/><bpmn:process");
        assertThat(codes(reader.readWithWarnings(withMessage.getBytes(StandardCharsets.UTF_8)).openBindings()))
                .containsExactly("CORRELATION_KEY_REQUIRED");

        byte[] wrong = file("isExecutable=\"true\"",
                "<bpmn:userTask id=\"approve\" horus:assignee=\"nobody\" horus:resultVariable=\"r\"/>",
                "<bpmn:sequenceFlow id=\"f1\" sourceRef=\"approve\" targetRef=\"e\"/>", "approve");
        assertThatThrownBy(() -> reader.readWithWarnings(wrong)).isInstanceOfSatisfying(ValidationFailedException.class,
                e -> assertThat(codes(e.problems())).contains("ASSIGNEE_INVALID"));
    }

    @Test
    void PRE_391_a_process_that_says_it_is_not_executable_is_read_for_viewing_only() {
        byte[] xml = file("isExecutable=\"false\"", "", "", "e");

        DefinitionReader.Result result = reader.readWithWarnings(xml);

        assertThat(result.process().isExecutable()).isFalse();
        assertThat(codes(result.warnings())).contains("NOT_EXECUTABLE_VIEW_ONLY");
        assertThatThrownBy(() -> reader.read(xml)).isInstanceOfSatisfying(ValidationFailedException.class,
                e -> assertThat(codes(e.problems())).contains("NOT_EXECUTABLE"));
    }
}
