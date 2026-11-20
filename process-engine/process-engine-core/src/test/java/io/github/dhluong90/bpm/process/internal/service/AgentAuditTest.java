package io.github.dhluong90.bpm.process.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dhluong90.bpm.process.internal.model.bpmn.ElementInfo;
import io.github.dhluong90.bpm.process.internal.model.bpmn.ExtensionBag;
import io.github.dhluong90.bpm.process.internal.model.bpmn.ServiceTask;
import io.github.dhluong90.bpm.process.internal.model.horus.HorusExtension;
import io.github.dhluong90.bpm.process.internal.model.horus.InputMapping;
import io.github.dhluong90.bpm.process.spi.InputMasker;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * PRE-012 (#59): what the history of an agent step keeps of its inputs, of its labels and of a reviewer's override.
 * A loan assessment sends the application id in plain text and the applicant's national id masked.
 */
class AgentAuditTest {

    private static final ServiceTask STEP = new ServiceTask("assess-documents", "Assess the documents", List.of(),
            List.of(), null, null, null, new ElementInfo(List.of(), new ExtensionBag(List.<HorusExtension>of(
                    new InputMapping("applicationId", "${instance.applicationId}"),
                    new InputMapping("nationalId", "${instance.nationalId}", true),
                    new InputMapping("requestedAmount", "${instance.amount}", true)), List.of()), Map.of()));

    private static Map<String, Object> inputs() {
        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("applicationId", "LOAN-2027-1201");
        inputs.put("nationalId", "079088001234");
        inputs.put("requestedAmount", 250_000_000L);
        return inputs;
    }

    @Test
    void PRE_012_a_masked_input_keeps_only_what_the_masker_returns() {
        Map<String, Object> audit = AgentAudit.maskInputs("bank-a", STEP, inputs(),
                (tenant, plain) -> "hmac:k1:" + tenant + ":" + plain.length());

        assertThat(audit).containsEntry("applicationId", "LOAN-2027-1201")
                .containsEntry("nationalId", "hmac:k1:bank-a:12")
                // A number is fingerprinted as its JSON text
                .containsEntry("requestedAmount", "hmac:k1:bank-a:9");
    }

    @Test
    void PRE_012_masking_fails_closed_when_the_masker_fails_or_echoes_the_value() {
        Map<String, Object> failing = AgentAudit.maskInputs("bank-a", STEP, inputs(), (tenant, plain) -> {
            throw new IllegalStateException("key store down");
        });
        Map<String, Object> echoing = AgentAudit.maskInputs("bank-a", STEP, inputs(), (tenant, plain) -> plain);

        assertThat(failing).containsEntry("nationalId", InputMasker.REDACTED);
        assertThat(echoing).containsEntry("nationalId", InputMasker.REDACTED)
                .containsEntry("requestedAmount", InputMasker.REDACTED);
        assertThat(echoing.toString()).doesNotContain("079088001234");
    }

    @Test
    void PRE_012_the_override_diff_lists_only_the_fields_the_reviewer_changed() {
        Map<String, Object> refused = Map.of("riskScore", 85L, "recommendation", "APPROVE", "summary", "***");
        Map<String, Object> reviewed = Map.of("riskScore", 85, "recommendation", "REJECT", "summary", "***");

        assertThat(AgentAudit.diff(refused, reviewed)).isEqualTo(Map.of("recommendation",
                Map.of("from", "APPROVE", "to", "REJECT")));
    }

    @Test
    void PRE_012_label_bounds_refuse_17_labels_a_long_key_and_a_long_value() {
        Map<String, String> seventeen = new LinkedHashMap<>();
        for (int i = 1; i <= 17; i++) {
            seventeen.put("label" + i, "v");
        }

        assertThat(AgentLabelLimits.DEFAULTS.violation(Map.of("model", "scoring-2026-10", "promptVersion", "p7",
                "vendor", "in-house"))).isNull();
        assertThat(AgentLabelLimits.DEFAULTS.violation(seventeen)).contains("17 labels, at most 16");
        assertThat(AgentLabelLimits.DEFAULTS.violation(Map.of("k".repeat(65), "v"))).contains("key");
        assertThat(AgentLabelLimits.DEFAULTS.violation(Map.of("model", "v".repeat(257)))).contains("value")
                .doesNotContain("vvv");
    }
}
