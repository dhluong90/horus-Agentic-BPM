package io.github.dhluong90.bpm.process.internal.service;

import io.github.dhluong90.bpm.process.internal.exec.StepResult;
import io.github.dhluong90.bpm.process.spi.AdapterResult;
import java.util.Map;

/**
 * The one place where a completed adapter result becomes the engine's step result (#59), for the executor and the
 * completion API alike. Labels an agent reports about its answer (PD-014: {@code model}, {@code promptVersion} and the
 * host's own keys) travel on {@link StepResult.Completed#labels()} to history, bounded by {@link AgentLabelLimits}.
 *
 * <p>The adapter result does not carry them yet: the model id and token usage of a completed result are added to the
 * SPI by a separate change, and are read here, through its accessors, once it lands. Until then an answer has no
 * labels, and a host that reports them through the engine's own result transaction is recorded the same way.
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

    /** The labels of a completed adapter result: none until the SPI carries them (see the class comment). */
    static Map<String, String> labels(AdapterResult.Completed completed) {
        return Map.of();
    }
}
