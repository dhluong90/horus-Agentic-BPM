package io.github.dhluong90.bpm.process.internal.exec;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Result of an invocation delivered back to the kernel in {@link Command.AsyncResult}.
 */
public sealed interface StepResult permits StepResult.Completed, StepResult.BusinessError, StepResult.Failed {

    /**
     * The call succeeded.
     *
     * @param output values for the result variable
     * @param labels what the agent reported about its answer, such as {@code model} and {@code promptVersion}
     *     (PD-014, #59): recorded in history as they are and never acted on; empty for every other step
     */
    record Completed(Map<String, Object> output, Map<String, String> labels) implements StepResult {
        public Completed {
            output = output == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(output));
            labels = labels == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(labels));
        }

        /** @param output values for the result variable */
        public Completed(Map<String, Object> output) {
            this(output, Map.of());
        }
    }

    /**
     * A business outcome; boundary error events catch it (S1), otherwise an incident.
     *
     * @param code business error code
     * @param message detail
     */
    record BusinessError(String code, String message) implements StepResult {    }

    /**
     * A technical failure; the retry policy applies, then an incident or compensation.
     *
     * @param errorCode error code
     * @param detail detail
     * @param retryable whether a retry may succeed
     */
    record Failed(String errorCode, String detail, boolean retryable) implements StepResult {    }
}
