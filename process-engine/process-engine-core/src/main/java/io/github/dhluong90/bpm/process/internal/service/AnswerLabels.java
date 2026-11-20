package io.github.dhluong90.bpm.process.internal.service;

import io.github.dhluong90.bpm.process.internal.exec.StepResult;
import io.github.dhluong90.bpm.process.spi.AdapterResult;
import java.util.Map;

/**
 * The one place where a completed adapter result becomes the engine's step result (#59), for the executor and the
 * completion API alike. Labels an agent reports about its answer (PD-014; reserved keys
 * {@link AdapterResult.Completed#MODEL}, {@link AdapterResult.Completed#PROMPT_VERSION},
 * {@link AdapterResult.Completed#INPUT_TOKENS}, {@link AdapterResult.Completed#OUTPUT_TOKENS}, plus the host's own
 * keys) are read from {@link AdapterResult.Completed#labels()} and travel on {@link StepResult.Completed#labels()} to
 * history, bounded by {@link AgentLabelLimits}.
 */
final class AnswerLabels {

    private AnswerLabels() {
    }

    /**
     * @param completed a completed adapter result
     * @return the step result, with the labels the adapter result carries
     */
    static StepResult.Completed step(AdapterResult.Completed completed) {
        return new StepResult.Completed(completed.output(), labels(completed));
    }

    /**
     * @param completed a completed adapter result
     * @return its labels as the adapter reported them; empty when none
     */
    static Map<String, String> labels(AdapterResult.Completed completed) {
        return completed.labels();
    }
}
