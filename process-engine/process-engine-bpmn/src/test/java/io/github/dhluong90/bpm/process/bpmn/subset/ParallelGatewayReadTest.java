package io.github.dhluong90.bpm.process.bpmn.subset;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dhluong90.bpm.process.bpmn.xml.Limits;
import io.github.dhluong90.bpm.process.internal.exec.BehaviorCatalog;
import io.github.dhluong90.bpm.process.internal.exec.CompiledProcess;
import io.github.dhluong90.bpm.process.internal.exec.ProcessCompiler;
import io.github.dhluong90.bpm.process.internal.json.CanonicalProcessWriter;
import io.github.dhluong90.bpm.process.internal.json.Hashes;
import io.github.dhluong90.bpm.process.internal.model.bpmn.ParallelGateway;
import io.github.dhluong90.bpm.process.internal.model.bpmn.Process;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** PRE-005: {@code parallelGateway} is read, compiled and hashed with its branches. */
class ParallelGatewayReadTest {

    private final SubsetBpmnReader reader = new SubsetBpmnReader(Limits.DEFAULTS);

    private static byte[] loanChecks(String secondBranch) {
        return ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<bpmn:definitions xmlns:bpmn=\"http://www.omg.org/spec/BPMN/20100524/MODEL\" "
                + "xmlns:horus=\"urn:horus-bpm:bpmn:extensions:v1\" id=\"d\" targetNamespace=\"urn:t\">"
                + "<bpmn:process id=\"loan-checks\" isExecutable=\"true\">"
                + "<bpmn:startEvent id=\"start\"/>"
                + "<bpmn:parallelGateway id=\"fork-checks\" name=\"Run both checks\"/>"
                + "<bpmn:userTask id=\"credit-bureau\" horus:assignee=\"role:CREDIT_ANALYST\" horus:resultVariable=\"bureau\"/>"
                + "<bpmn:userTask id=\"fraud-check\" horus:assignee=\"role:FRAUD_ANALYST\" horus:resultVariable=\"fraud\"/>"
                + "<bpmn:userTask id=\"aml-check\" horus:assignee=\"role:AML_ANALYST\" horus:resultVariable=\"aml\"/>"
                + "<bpmn:parallelGateway id=\"join-checks\"/>"
                + "<bpmn:endEvent id=\"end\"/>"
                + "<bpmn:sequenceFlow id=\"s\" sourceRef=\"start\" targetRef=\"fork-checks\"/>"
                + "<bpmn:sequenceFlow id=\"f1\" sourceRef=\"fork-checks\" targetRef=\"credit-bureau\"/>"
                + "<bpmn:sequenceFlow id=\"f2\" sourceRef=\"fork-checks\" targetRef=\"" + secondBranch + "\"/>"
                + "<bpmn:sequenceFlow id=\"j1\" sourceRef=\"credit-bureau\" targetRef=\"join-checks\"/>"
                + "<bpmn:sequenceFlow id=\"j2\" sourceRef=\"fraud-check\" targetRef=\"join-checks\"/>"
                + "<bpmn:sequenceFlow id=\"j3\" sourceRef=\"aml-check\" targetRef=\"join-checks\"/>"
                + "<bpmn:sequenceFlow id=\"e\" sourceRef=\"join-checks\" targetRef=\"end\"/>"
                + "</bpmn:process></bpmn:definitions>").getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void PRE_005_parallel_gateways_are_read_with_their_flows_and_compile() {
        Process process = reader.read(loanChecks("fraud-check"));

        ParallelGateway fork = process.flowElements().stream().filter(ParallelGateway.class::isInstance)
                .map(ParallelGateway.class::cast).filter(g -> g.id().equals("fork-checks")).findFirst().orElseThrow();
        assertThat(fork.name()).isEqualTo("Run both checks");
        assertThat(fork.outgoing()).containsExactly("f1", "f2");
        CompiledProcess compiled = new ProcessCompiler(BehaviorCatalog.registry(), 500).compile("D1", withAmlBranch());
        assertThat(compiled.node("join-checks").orElseThrow().incoming()).containsExactly("j1", "j2", "j3");
    }

    @Test
    void PRE_005_the_definition_hash_covers_the_branches_of_a_fork() {
        String toFraud = CanonicalProcessWriter.canonicalText(reader.read(loanChecks("fraud-check")));
        String toAml = CanonicalProcessWriter.canonicalText(reader.read(loanChecks("aml-check")));

        assertThat(toFraud).contains("\"next\":[\"credit-bureau\",\"fraud-check\"]", "\"type\":\"parallelGateway\"");
        assertThat(Hashes.sha256(toFraud)).isNotEqualTo(Hashes.sha256(toAml));
    }

    /** The three-branch variant: every check reachable, so the graph compiles. */
    private Process withAmlBranch() {
        String xml = new String(loanChecks("fraud-check"), StandardCharsets.UTF_8).replace(
                "<bpmn:sequenceFlow id=\"j1\"",
                "<bpmn:sequenceFlow id=\"f3\" sourceRef=\"fork-checks\" targetRef=\"aml-check\"/><bpmn:sequenceFlow id=\"j1\"");
        return reader.read(xml.getBytes(StandardCharsets.UTF_8));
    }
}
