package io.github.dhluong90.bpm.process.internal.model.horus;

/**
 * One {@code horus:input}: a bounded input of a step, a {@code ${instance.path}} reference or a literal.
 *
 * @param name name
 * @param value value
 */
public record InputMapping(
        String name,
        String value) implements HorusExtension {
}
