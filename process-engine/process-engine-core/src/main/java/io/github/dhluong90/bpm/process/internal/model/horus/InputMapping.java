package io.github.dhluong90.bpm.process.internal.model.horus;

/**
 * One {@code horus:input}: a bounded input of a step, a {@code ${instance.path}} reference or a literal.
 *
 * @param name name
 * @param value value
 * @param mask {@code mask="true"} on an agent step's input (#59): the value reaches the agent, but history and the
 *     review task keep only its keyed fingerprint, never the plain value
 * @param maskDeclared whether the definition says {@code mask="true"} or {@code mask="false"} (PD-146, #438): an
 *     agent step's input without it is listed as not classified. Not part of the canonical form or its hash
 */
public record InputMapping(
        String name,
        String value,
        boolean mask,
        boolean maskDeclared) implements HorusExtension {

    /**
     * An input whose masking is declared, as before #438.
     *
     * @param name name
     * @param value value
     * @param mask see the record
     */
    public InputMapping(String name, String value, boolean mask) {
        this(name, value, mask, true);
    }

    /**
     * An input that is not masked.
     *
     * @param name name
     * @param value value
     */
    public InputMapping(String name, String value) {
        this(name, value, false, false);
    }
}
