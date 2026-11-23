package io.github.dhluong90.bpm.process.bpmn.subset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.process.api.BpmnUnsupportedElementException;
import io.github.dhluong90.bpm.process.api.DefinitionProblem;
import io.github.dhluong90.bpm.process.api.ValidationFailedException;
import io.github.dhluong90.bpm.process.bpmn.xml.Limits;
import io.github.dhluong90.bpm.process.internal.json.CanonicalProcessWriter;
import io.github.dhluong90.bpm.process.internal.model.bpmn.ManualTask;
import io.github.dhluong90.bpm.process.internal.model.bpmn.Process;
import io.github.dhluong90.bpm.process.internal.model.bpmn.ScriptTask;
import io.github.dhluong90.bpm.process.internal.model.bpmn.Task;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** PRE-250 and PRE-252: plain, manual and script tasks are imported, exported and refused as the SPEC says. */
class TaskElementsImportTest {

    private static final String HEAD = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<bpmn:definitions xmlns:bpmn=\"http://www.omg.org/spec/BPMN/20100524/MODEL\" "
            + "xmlns:horus=\"urn:horus-bpm:bpmn:extensions:v1\" id=\"d\" targetNamespace=\"urn:t\">\n"
            + "<bpmn:process id=\"card-fee-review\" isExecutable=\"true\">\n";
    private static final String TAIL = "</bpmn:process></bpmn:definitions>";

    private final SubsetBpmnReader reader = new SubsetBpmnReader(Limits.DEFAULTS);

    private static byte[] file(String body) {
        return (HEAD + body + TAIL).getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void PRE_250_task_manual_task_and_script_task_import_and_export_to_the_same_definition() {
        Process process = reader.read(file("<bpmn:task id=\"note\" name=\"Note\"/>"
                + "<bpmn:manualTask id=\"post-letter\"/>"
                + "<bpmn:scriptTask id=\"double-fee\" scriptFormat=\"urn:horus-bpm:expr:v1\" horus:resultVariable=\"fee2\">"
                + "<bpmn:script>amount * 2</bpmn:script></bpmn:scriptTask>"));

        assertThat(process.flowElements()).hasAtLeastOneElementOfType(Task.class)
                .hasAtLeastOneElementOfType(ManualTask.class).hasAtLeastOneElementOfType(ScriptTask.class);
        assertThat(process.flowElements().stream().filter(ScriptTask.class::isInstance).map(ScriptTask.class::cast)
                .findFirst().orElseThrow().script()).isEqualTo("amount * 2");
        byte[] exported = new SubsetBpmnWriter().write(process);
        assertThat(new String(exported, StandardCharsets.UTF_8)).contains("<bpmn:script>amount * 2</bpmn:script>");
        assertThat(CanonicalProcessWriter.canonicalText(reader.read(exported)))
                .isEqualTo(CanonicalProcessWriter.canonicalText(process));
    }

    @Test
    void PRE_250_the_worked_example_reads_and_compiles() throws java.io.IOException {
        byte[] example;
        try (java.io.InputStream in = getClass().getResourceAsStream("/bpmn/card-welcome.bpmn")) {
            example = in.readAllBytes();
        }

        Process process = reader.read(example);

        io.github.dhluong90.bpm.process.internal.exec.CompiledProcess compiled =
                new io.github.dhluong90.bpm.process.internal.exec.ProcessCompiler(
                        io.github.dhluong90.bpm.process.internal.exec.BehaviorCatalog.registry(), 500)
                        .compile("D1", process);
        assertThat(compiled.node("yearly-fee")).isPresent();
        assertThat(compiled.node("post-card")).isPresent();
    }

    @Test
    void PRE_252_a_script_that_is_not_valid_is_refused_with_the_line_of_the_script() {
        byte[] bad = file("<bpmn:scriptTask id=\"double-fee\" horus:resultVariable=\"fee2\">\n"
                + "<bpmn:script>amount *</bpmn:script></bpmn:scriptTask>");

        assertThatThrownBy(() -> reader.read(bad)).isInstanceOfSatisfying(ValidationFailedException.class, e ->
                assertThat(e.problems()).singleElement().satisfies(p -> {
                    assertThat(p.code()).isEqualTo("SCRIPT_INVALID");
                    assertThat(p.line()).isEqualTo(5);
                    assertThat(p.elementId()).isEqualTo("double-fee");
                }));
    }

    @Test
    void PRE_252_a_script_task_needs_a_script_and_a_result_variable() {
        byte[] none = file("<bpmn:scriptTask id=\"s\"/>");

        assertThatThrownBy(() -> reader.read(none)).isInstanceOfSatisfying(ValidationFailedException.class, e ->
                assertThat(e.problems()).extracting(DefinitionProblem::code)
                        .containsExactlyInAnyOrder("SCRIPT_MISSING", "RESULT_VARIABLE_REQUIRED"));
    }

    @Test
    void PRE_252_a_script_in_another_language_is_refused_with_its_stage() {
        byte[] feel = file("<bpmn:scriptTask id=\"s\" scriptFormat=\"https://www.omg.org/spec/DMN/20191111/FEEL/\" "
                + "horus:resultVariable=\"r\"><bpmn:script>1 + 1</bpmn:script></bpmn:scriptTask>");
        byte[] js = file("<bpmn:scriptTask id=\"s\" scriptFormat=\"javascript\" horus:resultVariable=\"r\">"
                + "<bpmn:script>java.lang.System.exit(1)</bpmn:script></bpmn:scriptTask>");

        assertThatThrownBy(() -> reader.read(feel)).isInstanceOfSatisfying(BpmnUnsupportedElementException.class, e ->
                assertThat(e.problems()).singleElement().satisfies(p -> {
                    assertThat(p.code()).isEqualTo("BPMN_SCRIPT_FORMAT_UNSUPPORTED");
                    assertThat(p.stage()).isEqualTo("S1");
                }));
        assertThatThrownBy(() -> reader.read(js)).isInstanceOfSatisfying(BpmnUnsupportedElementException.class, e ->
                assertThat(e.problems()).singleElement().satisfies(p ->
                        assertThat(p.stage()).isEqualTo(DefinitionProblem.NOT_PLANNED)));
    }
}
