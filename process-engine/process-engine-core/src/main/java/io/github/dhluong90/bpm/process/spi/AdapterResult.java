package io.github.dhluong90.bpm.process.spi;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Outcome of one {@link ServiceAdapter} attempt, or of a completion reported later. Sealed, so later outcomes need no
 * SPI change. Codes may be returned to API callers and recorded in history; details are for logs only and must not
 * contain personal data.
 */
public sealed interface AdapterResult permits AdapterResult.Completed, AdapterResult.BusinessError,
        AdapterResult.Failed, AdapterResult.Accepted {

    /**
     * @param output values for the step's result variable
     * @return a completed result
     */
    static AdapterResult completed(Map<String, ?> output) {
        return new Completed(new LinkedHashMap<>(output));
    }

    /**
     * @param output values for the step's result variable
     * @param labels what produced the answer, for example the model id and the token usage; see {@link Completed}
     * @return a completed result with labels
     */
    static AdapterResult completed(Map<String, ?> output, Map<String, String> labels) {
        return new Completed(new LinkedHashMap<>(output), labels);
    }

    /**
     * The call succeeded; the process moves on.
     *
     * <p>Labels describe how the answer was produced, never what it says: they are kept with the step's history for
     * evaluation and offline replay, and the engine takes no decision from them. Reserved keys, all optional:
     * {@value #MODEL} (the model id or version), {@value #PROMPT_VERSION}, {@value #INPUT_TOKENS} and
     * {@value #OUTPUT_TOKENS} (token usage as a decimal integer). A host may add keys of its own. Labels must not carry
     * personal data. A remote service result has none. Bounds and recording are part of the agent history.
     *
     * @param output values for the result variable
     * @param labels string labels of the answer; empty when none, never null
     */
    record Completed(Map<String, Object> output, Map<String, String> labels) implements AdapterResult {

        /** Label key of the model id or version that produced the answer. */
        public static final String MODEL = "model";
        /** Label key of the prompt version. */
        public static final String PROMPT_VERSION = "promptVersion";
        /** Label key of the number of input tokens. */
        public static final String INPUT_TOKENS = "usage.inputTokens";
        /** Label key of the number of output tokens. */
        public static final String OUTPUT_TOKENS = "usage.outputTokens";

        public Completed {
            output = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(output, "output")));
            Map<String, String> copy = new LinkedHashMap<>();
            (labels == null ? Map.<String, String>of() : labels).forEach((key, value) -> copy.put(
                    Objects.requireNonNull(key, "label key must not be null"),
                    Objects.requireNonNull(value, "label value must not be null")));
            labels = Collections.unmodifiableMap(copy);
        }

        /**
         * A completed result without labels.
         *
         * @param output values for the result variable
         */
        public Completed(Map<String, Object> output) {
            this(output, Map.of());
        }
    }

    /**
     * A business outcome the process has no path for yet: the job fails at once, with no retry, and the token stays
     * on the step.
     *
     * @param code business error code
     * @param message detail for logs
     */
    record BusinessError(String code, String message) implements AdapterResult {
        public BusinessError {
            Objects.requireNonNull(code, "code must not be null");
        }
    }

    /**
     * A technical failure: the job is due again after {@code jobs.retry-backoff} times the attempt, with a new attempt
     * id, and fails after {@code jobs.max-attempts} or at once when not retryable.
     *
     * @param errorCode error code
     * @param detail detail for logs
     * @param retryable whether a retry may succeed
     */
    record Failed(String errorCode, String detail, boolean retryable) implements AdapterResult {
        public Failed {
            Objects.requireNonNull(errorCode, "errorCode must not be null");
        }
    }

    /**
     * The remote side took the request and reports the result later through the completion API, quoting the attempt
     * id and presenting the attempt's completion token. The job keeps its lease; a completion after the lease ends is discarded and the step is called again.
     */
    record Accepted() implements AdapterResult {
    }
}
