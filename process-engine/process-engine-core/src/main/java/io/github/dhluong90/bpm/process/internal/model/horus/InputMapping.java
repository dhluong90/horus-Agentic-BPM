package io.github.dhluong90.bpm.process.internal.model.horus;

/**
 * One {@code horus:input}: a bounded input of a step, a {@code ${instance.path}} reference or a literal.
 *
 * @param name name
 * @param value value
 * @param mask {@code mask="true"} on an agent step's input (#59): the value reaches the agent, but history and the
 *     review task keep only its keyed fingerprint, never the plain value
 */
public record InputMapping(
        String name,
        String value,
        boolean mask) implements HorusExtension {

    /**
     * An input that is not masked.
     *
     * @param name name
     * @param value value
     */
    public InputMapping(String name, String value) {
        this(name, value, false);
    }
}
