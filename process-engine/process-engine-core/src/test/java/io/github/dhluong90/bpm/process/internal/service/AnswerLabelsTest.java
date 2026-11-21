package io.github.dhluong90.bpm.process.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dhluong90.bpm.process.internal.exec.StepResult;
import io.github.dhluong90.bpm.process.spi.AdapterResult;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AnswerLabelsTest {

    @Test
    void PRE_012_step_result_carries_the_labels_of_the_completed_adapter_result() {
        // Given an adapter result with the reserved labels and a host label
        AdapterResult.Completed completed = (AdapterResult.Completed) AdapterResult.completed(
                Map.of("recommendation", "APPROVE"),
                Map.of(AdapterResult.Completed.MODEL, "scoring-2026-10", AdapterResult.Completed.PROMPT_VERSION, "p7",
                        AdapterResult.Completed.INPUT_TOKENS, "1200", AdapterResult.Completed.OUTPUT_TOKENS, "80",
                        "vendor", "in-house"));

        // When it becomes the engine's step result
        StepResult.Completed step = AnswerLabels.step(completed);

        // Then the output and every label reach history unchanged
        assertThat(step.output()).isEqualTo(Map.of("recommendation", "APPROVE"));
        assertThat(step.labels()).isEqualTo(Map.of("model", "scoring-2026-10", "promptVersion", "p7",
                "usage.inputTokens", "1200", "usage.outputTokens", "80", "vendor", "in-house"));
    }

    @Test
    void PRE_012_a_result_without_labels_has_none() {
        StepResult.Completed step = AnswerLabels.step(new AdapterResult.Completed(Map.of("ok", true)));

        assertThat(step.labels()).isEmpty();
    }
}
