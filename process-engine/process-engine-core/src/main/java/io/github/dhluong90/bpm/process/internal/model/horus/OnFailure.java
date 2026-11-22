package io.github.dhluong90.bpm.process.internal.model.horus;

/**
 * {@code horus:onFailure} of a service or agent step (#67, PRE-073): what the engine does when the step fails for
 * good, by a business error, a failure that is not retryable or the last attempt used. {@code compensate} undoes the
 * completed steps of the step's scope newest first instead of opening the incident of PRE-040.
 *
 * @param action the action; {@code compensate} is the only one
 */
public record OnFailure(String action) implements HorusExtension {

    /** The value that starts a compensation. */
    public static final String COMPENSATE = "compensate";

    /** @return whether the step's failure starts a compensation */
    public boolean compensates() {
        return COMPENSATE.equals(action);
    }
}
