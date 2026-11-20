package io.github.dhluong90.bpm.process.spi;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Outcome of a handler call. Sealed, so BPMN error events can be added later without an SPI change (DN-09).
 */
public sealed interface HandlerResult permits HandlerResult.Completed, HandlerResult.BusinessError,
        HandlerResult.Failed {

    /**
     * @param output values for the step's result variable
     * @return a completed result
     */
    static HandlerResult completed(Map<String, ?> output) {
        return new Completed(new LinkedHashMap<>(output));
    }

    /**
     * The step succeeded.
     *
     * @param output values for the result variable
     */
    record Completed(Map<String, Object> output) implements HandlerResult {
        public Completed {
            output = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(output, "output")));
        }
    }

    /**
     * A business outcome the process has no path for yet; the step fails and the transaction rolls back. The code may
     * be returned to API callers; the message is for logs and must not contain personal data.
     *
     * @param code business error code
     * @param message detail
     */
    record BusinessError(String code, String message) implements HandlerResult {
    }

    /**
     * A technical failure; the step fails and the transaction rolls back. Retries are not in this skeleton. The code
     * may be returned to API callers; the detail is for logs and must not contain personal data.
     *
     * @param errorCode error code
     * @param detail detail
     * @param retryable whether a retry may succeed
     */
    record Failed(String errorCode, String detail, boolean retryable) implements HandlerResult {
    }
}
