package io.github.dhluong90.bpm.process.bpmn.subset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.process.api.DefinitionProblem;
import io.github.dhluong90.bpm.process.api.ValidationFailedException;
import io.github.dhluong90.bpm.process.bpmn.xml.Limits;
import io.github.dhluong90.bpm.process.internal.exec.BehaviorCatalog;
import io.github.dhluong90.bpm.process.internal.exec.ProcessCompiler;
import io.github.dhluong90.bpm.process.internal.json.CanonicalProcessWriter;
import io.github.dhluong90.bpm.process.internal.json.Hashes;
import io.github.dhluong90.bpm.process.internal.model.bpmn.FlowElement;
import io.github.dhluong90.bpm.process.internal.model.bpmn.Process;
import io.github.dhluong90.bpm.process.internal.model.horus.AgentTaskSpec;
import io.github.dhluong90.bpm.process.internal.model.horus.GuardrailSpec;
import io.github.dhluong90.bpm.process.internal.model.horus.InputMapping;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The agent step in BPMN (#52, PRE-061): a {@code serviceTask} with {@code horus:type="agentTask"}, its output schema
 * and its guardrail. A loan application's documents are assessed by {@code documents.assess}; a refused answer goes to
 * a compliance reviewer. Every invalid guard spec is refused when the document is read, naming the step.
 */
class SubsetBpmnReaderAgentTaskTest {

    private static final String SCHEMA = "{\"type\": \"object\", \"required\": [\"riskScore\"],"
            + " \"properties\": {\"riskScore\": {\"type\": \"integer\", \"minimum\": 0, \"maximum\": 100}}}";
    private static final String GUARDRAIL = "<horus:guardrail decision=\"agent-output-guardrails\""
            + " onViolation=\"route-to-human\" reviewer=\"role:COMPLIANCE_REVIEWER\"/>";

    private final SubsetBpmnReader reader = new SubsetBpmnReader(Limits.DEFAULTS);
    private final SubsetBpmnWriter writer = new SubsetBpmnWriter();

    private static byte[] agentStep(String attributes, String extensions) {
        return ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<bpmn:definitions xmlns:bpmn=\"http://www.omg.org/spec/BPMN/20100524/MODEL\" "
                + "xmlns:horus=\"urn:horus-bpm:bpmn:extensions:v1\" id=\"d\" targetNamespace=\"urn:t\">"
                + "<bpmn:process id=\"loan-assessment\" isExecutable=\"true\"><bpmn:startEvent id=\"start\"/>"
                + "<bpmn:serviceTask id=\"assess-documents\" horus:type=\"agentTask\" " + attributes + ">"
                + "<bpmn:extensionElements>" + extensions + "</bpmn:extensionElements></bpmn:serviceTask>"
                + "<bpmn:endEvent id=\"end\"/>"
                + "<bpmn:sequenceFlow id=\"f1\" sourceRef=\"start\" targetRef=\"assess-documents\"/>"
                + "<bpmn:sequenceFlow id=\"f2\" sourceRef=\"assess-documents\" targetRef=\"end\"/>"
                + "</bpmn:process></bpmn:definitions>").getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] resource(String name) {
        try (InputStream in = SubsetBpmnReaderAgentTaskTest.class.getResourceAsStream("/bpmn/" + name)) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static FlowElement step(Process process) {
        return process.flowElements().stream().filter(e -> e.id().equals("assess-documents")).findFirst()
                .orElseThrow();
    }

    private List<DefinitionProblem> problems(byte[] document) {
        try {
            reader.read(document);
        } catch (ValidationFailedException e) {
            return e.problems();
        }
        throw new AssertionError("The document was accepted");
    }

    @Test
    void PRE_061_an_agent_step_reads_with_its_agent_schema_guardrail_inputs_and_timeout() {
        Process process = reader.read(resource("loan-agent-assessment.bpmn"));

        FlowElement step = step(process);
        AgentTaskSpec agent = step.extensions().first(AgentTaskSpec.class).orElseThrow();
        assertThat(agent.agent()).isEqualTo("documents.assess");
        assertThat(agent.timeout()).isEqualTo(Duration.ofSeconds(2));
        // The schema is stored as canonical JSON (RFC 8785), so whitespace in the document never changes the hash
        assertThat(agent.outputSchema()).startsWith("{\"additionalProperties\":false,\"properties\":{")
                .contains("\"summary\":{\"mask\":true,\"maxLength\":500,\"type\":\"string\"}");
        assertThat(step.extensions().first(GuardrailSpec.class)).contains(new GuardrailSpec("agent-output-guardrails",
                "route-to-human", "role:COMPLIANCE_REVIEWER"));
        assertThat(step.extensions().all(InputMapping.class)).extracting(InputMapping::name)
                .containsExactly("applicationId", "requestedAmount");
        new ProcessCompiler(BehaviorCatalog.registry(), 500).compile("D1", process);
    }

    @Test
    void PRE_061_the_canonical_form_carries_the_whole_guard_spec_so_the_hash_covers_it() {
        Process source = reader.read(resource("loan-agent-assessment.bpmn"));
        String canonical = CanonicalProcessWriter.canonicalText(source);

        // Changing only the reviewer, only the rule set, or only a schema bound gives another definition hash
        String xml = new String(resource("loan-agent-assessment.bpmn"), StandardCharsets.UTF_8);
        for (String[] change : new String[][] {
                {"role:COMPLIANCE_REVIEWER", "role:BRANCH_MANAGER"},
                {"decision=\"agent-output-guardrails\"", "decision=\"agent-output-guardrails-v2\""},
                {"\"maximum\": 100", "\"maximum\": 101"}}) {
            Process changed = reader.read(xml.replace(change[0], change[1]).getBytes(StandardCharsets.UTF_8));
            assertThat(Hashes.sha256(CanonicalProcessWriter.canonicalText(changed))).as(change[1])
                    .isNotEqualTo(Hashes.sha256(canonical));
        }
        assertThat(canonical).contains("\"type\":\"agentTask\"", "\"agent\":\"documents.assess\"",
                "\"guardrails\":{\"decision\":\"agent-output-guardrails\",\"onViolation\":\"route-to-human\","
                        + "\"reviewer\":{\"role\":\"COMPLIANCE_REVIEWER\"}}", "\"timeout\":\"PT2S\"");
        // The export reads back to the same canonical form
        assertThat(CanonicalProcessWriter.canonicalText(reader.read(writer.write(source)))).isEqualTo(canonical);
    }

    @Test
    void PRE_061_on_violation_defaults_to_route_to_human_and_fail_needs_no_reviewer() {
        Process defaulted = reader.read(agentStep("horus:agent=\"documents.assess\"", "<horus:outputSchema>" + SCHEMA
                + "</horus:outputSchema><horus:guardrail decision=\"agent-output-guardrails\""
                + " reviewer=\"role:COMPLIANCE_REVIEWER\" fourEyes=\"true\"/>"));
        Process failing = reader.read(agentStep("horus:agent=\"documents.assess\"", "<horus:outputSchema>" + SCHEMA
                + "</horus:outputSchema><horus:guardrail decision=\"agent-output-guardrails\" onViolation=\"fail\"/>"));

        assertThat(step(defaulted).extensions().first(GuardrailSpec.class)).contains(new GuardrailSpec(
                "agent-output-guardrails", "route-to-human", "role:COMPLIANCE_REVIEWER"));
        assertThat(step(failing).extensions().first(GuardrailSpec.class)).contains(new GuardrailSpec(
                "agent-output-guardrails", "fail", null));
    }

    @Test
    void PRE_061_an_invalid_guard_spec_is_refused_naming_the_step() {
        String schema = "<horus:outputSchema>" + SCHEMA + "</horus:outputSchema>";
        String[][] cases = {
                // attributes, extensions, expected message fragment
                {"horus:agent=\"documents.assess\"", GUARDRAIL, "needs a <horus:outputSchema>"},
                {"horus:agent=\"documents.assess\"", "<horus:outputSchema>riskScore: integer</horus:outputSchema>"
                        + GUARDRAIL, "not valid JSON"},
                {"horus:agent=\"documents.assess\"", "<horus:outputSchema>[1, 2]</horus:outputSchema>" + GUARDRAIL,
                        "must be a JSON object"},
                {"horus:agent=\"documents.assess\"", schema, "needs a <horus:guardrail"},
                {"horus:agent=\"documents.assess\"", schema + "<horus:guardrail onViolation=\"fail\"/>",
                        "needs decision"},
                {"horus:agent=\"documents.assess\"", schema + "<horus:guardrail decision=\"Agent Guardrails\""
                        + " onViolation=\"fail\"/>", "needs decision"},
                {"horus:agent=\"documents.assess\"", schema + "<horus:guardrail decision=\"agent-output-guardrails\""
                        + " onViolation=\"ignore\"/>", "route-to-human or fail"},
                {"horus:agent=\"documents.assess\"", schema + "<horus:guardrail decision=\"agent-output-guardrails\""
                        + " onViolation=\"route-to-human\"/>", "needs reviewer"},
                {"horus:agent=\"documents.assess\"", schema + "<horus:guardrail decision=\"agent-output-guardrails\""
                        + " reviewer=\"compliance\"/>", "needs reviewer"},
                {"horus:agent=\"documents.assess\"", schema + "<horus:guardrail decision=\"agent-output-guardrails\""
                        + " onViolation=\"fail\" reviewer=\"role:COMPLIANCE_REVIEWER\"/>", "route-to-human only"},
                {"horus:agent=\"documents.assess\"", schema + "<horus:guardrail decision=\"agent-output-guardrails\""
                        + " reviewer=\"role:COMPLIANCE_REVIEWER\" fourEyes=\"false\"/>", "cannot be switched off"},
                {"horus:agent=\"documents.assess\"", schema + GUARDRAIL + GUARDRAIL, "has one <horus:guardrail>"}};
        for (String[] c : cases) {
            assertThat(problems(agentStep(c[0], c[1]))).as(c[2])
                    .anySatisfy(p -> {
                        assertThat(p.code()).isEqualTo("GUARD_SPEC_INVALID");
                        assertThat(p.elementId()).isEqualTo("assess-documents");
                        assertThat(p.message()).contains(c[2]);
                    });
        }
    }

    @Test
    void PRE_061_an_agent_step_needs_its_agent_and_takes_no_handler_mode_or_bad_timeout() {
        String spec = "<horus:outputSchema>" + SCHEMA + "</horus:outputSchema>" + GUARDRAIL;

        assertThat(problems(agentStep("", spec))).extracting(DefinitionProblem::code).contains("AGENT_MISSING");
        assertThat(problems(agentStep("horus:agent=\"documents.assess\" horus:handler=\"documents.assess\""
                + " horus:mode=\"remote\"", spec))).extracting(DefinitionProblem::code).contains("AGENT_TASK_INVALID");
        assertThat(problems(agentStep("horus:agent=\"documents.assess\" horus:timeout=\"PT0S\"", spec)))
                .extracting(DefinitionProblem::code).contains("TIMEOUT_INVALID");
    }

    @Test
    void a_problem_never_echoes_the_schema_text() {
        String secret = "079088001234";
        List<DefinitionProblem> problems = problems(agentStep("horus:agent=\"documents.assess\"",
                "<horus:outputSchema>{\"nationalId\": " + secret + ",</horus:outputSchema>" + GUARDRAIL));

        assertThat(problems).isNotEmpty().allSatisfy(p -> assertThat(p.message()).doesNotContain(secret));
    }

    @Test
    void another_horus_type_is_still_unsupported() {
        byte[] document = new String(agentStep("horus:agent=\"documents.assess\"", ""), StandardCharsets.UTF_8)
                .replace("horus:type=\"agentTask\"", "horus:type=\"chatTask\"").getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> reader.read(document))
                .isInstanceOf(io.github.dhluong90.bpm.process.api.BpmnUnsupportedElementException.class);
    }
}
