package io.github.dhluong90.bpm.process.bpmn.subset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.process.api.DefinitionProblem;
import io.github.dhluong90.bpm.process.api.ValidationFailedException;
import io.github.dhluong90.bpm.process.bpmn.xml.Limits;
import io.github.dhluong90.bpm.process.internal.json.CanonicalProcessWriter;
import io.github.dhluong90.bpm.process.internal.model.bpmn.Process;
import io.github.dhluong90.bpm.process.internal.model.bpmn.UserTask;
import io.github.dhluong90.bpm.process.internal.model.horus.EscalationSpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/** PRE-179: {@code horus:escalation} and {@code horus:onClaimed} of a user task, read, validated, hashed and exported. */
class SubsetBpmnReaderEscalationTest {

    private final SubsetBpmnReader reader = new SubsetBpmnReader(Limits.DEFAULTS);

    private static byte[] review(String attributes) {
        return ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<bpmn:definitions xmlns:bpmn=\"http://www.omg.org/spec/BPMN/20100524/MODEL\" "
                + "xmlns:horus=\"urn:horus-bpm:bpmn:extensions:v1\" id=\"d\" targetNamespace=\"urn:t\">"
                + "<bpmn:process id=\"loan-credit-review\" isExecutable=\"true\"><bpmn:startEvent id=\"start\"/>"
                + "<bpmn:userTask id=\"review-application\" horus:assignee=\"role:credit-officer\" "
                + "horus:resultVariable=\"review\" " + attributes + "/><bpmn:endEvent id=\"end\"/>"
                + "<bpmn:sequenceFlow id=\"f1\" sourceRef=\"start\" targetRef=\"review-application\"/>"
                + "<bpmn:sequenceFlow id=\"f2\" sourceRef=\"review-application\" targetRef=\"end\"/>"
                + "</bpmn:process></bpmn:definitions>").getBytes(StandardCharsets.UTF_8);
    }

    private static EscalationSpec escalation(Process process) {
        return process.flowElements().stream().filter(UserTask.class::isInstance).map(UserTask.class::cast)
                .findFirst().orElseThrow().extensions().first(EscalationSpec.class).orElse(null);
    }

    @Test
    void PRE_179_two_levels_and_the_claim_rule_are_read_hashed_and_exported() {
        Process process = reader.read(review("horus:sla=\"PT8H\" horus:calendar=\"vn-banking-hours\" "
                + "horus:escalation=\"role:branch-supervisor;role:regional-manager|PT4H\" horus:onClaimed=\"keep\""));

        assertThat(escalation(process)).isEqualTo(new EscalationSpec(List.of(
                new EscalationSpec.Level(Duration.ZERO, "role:branch-supervisor"),
                new EscalationSpec.Level(Duration.ofHours(4), "role:regional-manager")), false));
        assertThat(CanonicalProcessWriter.canonicalText(process)).contains("\"escalation\":{\"levels\":["
                + "{\"after\":\"PT0S\",\"to\":\"role:branch-supervisor\"},"
                + "{\"after\":\"PT4H\",\"to\":\"role:regional-manager\"}],\"onClaimed\":\"keep\"}");
        byte[] exported = new SubsetBpmnWriter().write(process);
        assertThat(new String(exported, StandardCharsets.UTF_8))
                .contains("horus:escalation=\"role:branch-supervisor;role:regional-manager|PT4H\"")
                .contains("horus:onClaimed=\"keep\"");
        assertThat(CanonicalProcessWriter.canonicalText(reader.read(exported)))
                .isEqualTo(CanonicalProcessWriter.canonicalText(process));
    }

    @Test
    void PRE_179_a_task_without_escalation_keeps_its_canonical_form_and_releases_a_claim_by_default() {
        assertThat(CanonicalProcessWriter.canonicalText(reader.read(review("horus:sla=\"PT8H\""))))
                .doesNotContain("escalation");
        assertThat(escalation(reader.read(review("horus:sla=\"PT8H\" horus:escalation=\"user:nv.an@bank.vn\""))))
                .isEqualTo(new EscalationSpec(List.of(new EscalationSpec.Level(Duration.ZERO, "user:nv.an@bank.vn")),
                        true));
    }

    @Test
    void PRE_179_an_invalid_escalation_fails_validation_at_import() {
        for (String attributes : new String[] {
                // a fourth level
                "horus:sla=\"PT8H\" horus:escalation=\"role:a;role:b|PT1H;role:c|PT1H;role:d|PT1H\"",
                // a target equal to the assignment it replaces, or to the previous level
                "horus:sla=\"PT8H\" horus:escalation=\"role:credit-officer\"",
                "horus:sla=\"PT8H\" horus:escalation=\"role:a;role:a|PT1H\"",
                // a later level that waits nothing, a negative or malformed wait
                "horus:sla=\"PT8H\" horus:escalation=\"role:a;role:b\"",
                "horus:sla=\"PT8H\" horus:escalation=\"role:a|-PT1H\"",
                "horus:sla=\"PT8H\" horus:escalation=\"role:a|PTx\"",
                // not an assignment, no SLA to be late against, a claim rule that does not exist or stands alone
                "horus:sla=\"PT8H\" horus:escalation=\"supervisor\"",
                "horus:escalation=\"role:a\"",
                "horus:sla=\"PT8H\" horus:escalation=\"role:a\" horus:onClaimed=\"drop\"",
                "horus:sla=\"PT8H\" horus:onClaimed=\"keep\""}) {
            assertThatThrownBy(() -> reader.read(review(attributes))).as(attributes)
                    .isInstanceOf(ValidationFailedException.class)
                    .satisfies(e -> assertThat(((ValidationFailedException) e).problems())
                            .extracting(DefinitionProblem::code).containsOnly("ESCALATION_INVALID"));
        }
    }
}
