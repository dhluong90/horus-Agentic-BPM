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
     * The call succeeded; the process moves on.
     *
     * @param output values for the result variable
     */
    record Completed(Map<String, Object> output) implements AdapterResult {
        public Completed {
            output = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(output, "output")));
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
