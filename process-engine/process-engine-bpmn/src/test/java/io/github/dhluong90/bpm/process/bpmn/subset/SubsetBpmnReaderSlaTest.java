package io.github.dhluong90.bpm.process.bpmn.subset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.process.api.DefinitionProblem;
import io.github.dhluong90.bpm.process.api.ValidationFailedException;
import io.github.dhluong90.bpm.process.bpmn.xml.Limits;
import io.github.dhluong90.bpm.process.internal.json.CanonicalProcessWriter;
import io.github.dhluong90.bpm.process.internal.model.bpmn.Process;
import io.github.dhluong90.bpm.process.internal.model.bpmn.UserTask;
import io.github.dhluong90.bpm.process.internal.model.horus.SlaSpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** PRE-008: {@code horus:sla} and {@code horus:calendar} of a user task, read, validated, hashed and exported. */
class SubsetBpmnReaderSlaTest {

    private final SubsetBpmnReader reader = new SubsetBpmnReader(Limits.DEFAULTS);

    private static byte[] review(String attributes) {
        return ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<bpmn:definitions xmlns:bpmn=\"http://www.omg.org/spec/BPMN/20100524/MODEL\" "
                + "xmlns:horus=\"urn:horus-bpm:bpmn:extensions:v1\" id=\"d\" targetNamespace=\"urn:t\">"
                + "<bpmn:process id=\"loan-credit-review\" isExecutable=\"true\"><bpmn:startEvent id=\"start\"/>"
                + "<bpmn:userTask id=\"credit-review\" horus:assignee=\"role:credit-officer\" "
                + "horus:resultVariable=\"review\" " + attributes + "/><bpmn:endEvent id=\"end\"/>"
                + "<bpmn:sequenceFlow id=\"f1\" sourceRef=\"start\" targetRef=\"credit-review\"/>"
                + "<bpmn:sequenceFlow id=\"f2\" sourceRef=\"credit-review\" targetRef=\"end\"/>"
                + "</bpmn:process></bpmn:definitions>").getBytes(StandardCharsets.UTF_8);
    }

    private static SlaSpec sla(Process process) {
        return process.flowElements().stream().filter(UserTask.class::isInstance).map(UserTask.class::cast)
                .findFirst().orElseThrow().extensions().first(SlaSpec.class).orElse(null);
    }

    @Test
    void PRE_008_a_user_task_sla_and_calendar_are_read_hashed_and_exported() {
        Process process = reader.read(review("horus:sla=\"PT4H\" horus:calendar=\"vn-banking-hours\""));

        assertThat(sla(process)).isEqualTo(new SlaSpec(Duration.ofHours(4), "vn-banking-hours", null));
        assertThat(CanonicalProcessWriter.canonicalText(process))
                .contains("\"sla\":{\"calendar\":\"vn-banking-hours\",\"duration\":\"PT4H\"}");
        byte[] exported = new SubsetBpmnWriter().write(process);
        assertThat(new String(exported, StandardCharsets.UTF_8)).contains("horus:sla=\"PT4H\"")
                .contains("horus:calendar=\"vn-banking-hours\"");
        assertThat(CanonicalProcessWriter.canonicalText(reader.read(exported)))
                .isEqualTo(CanonicalProcessWriter.canonicalText(process));
    }

    @Test
    void PRE_008_a_task_without_an_sla_keeps_its_canonical_form() {
        Process process = reader.read(review(""));

        assertThat(sla(process)).isNull();
        assertThat(CanonicalProcessWriter.canonicalText(process)).doesNotContain("\"sla\"");
        assertThat(sla(reader.read(review("horus:sla=\"P2D\"")))).isEqualTo(new SlaSpec(Duration.ofDays(2), null, null));
    }

    @Test
    void PRE_008_an_invalid_sla_or_calendar_fails_validation_at_import() {
        for (String attributes : new String[] {"horus:sla=\"4 hours\"", "horus:sla=\"PT0S\"", "horus:sla=\"-PT1H\"",
                "horus:sla=\"P400D\"", "horus:sla=\"PT4H\" horus:calendar=\"VN Banking\"",
                "horus:calendar=\"vn-banking-hours\""}) {
            assertThatThrownBy(() -> reader.read(review(attributes))).as(attributes)
                    .isInstanceOf(ValidationFailedException.class)
                    .satisfies(e -> assertThat(((ValidationFailedException) e).problems())
                            .extracting(DefinitionProblem::code).containsExactly("SLA_INVALID"));
        }
    }
}
