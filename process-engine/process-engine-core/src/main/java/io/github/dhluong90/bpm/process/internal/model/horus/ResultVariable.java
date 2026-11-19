package io.github.dhluong90.bpm.process.internal.model.horus;

/**
 * {@code horus:resultVariable}: the instance variable that receives a step's output.
 *
 * @param name name
 */
public record ResultVariable(
        String name) implements HorusExtension {
}
