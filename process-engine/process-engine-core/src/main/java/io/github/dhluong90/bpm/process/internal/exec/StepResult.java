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
     */
    record Completed(Map<String, Object> output) implements StepResult {
        public Completed {
            output = output == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(output));
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
