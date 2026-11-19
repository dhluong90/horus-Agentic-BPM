package io.github.dhluong90.bpm.process.internal.model.horus;

/**
 * {@code horus:compensate}: the handler that undoes a step, or {@code manual}.
 *
 * @param handler handler
 */
public record CompensateHandlerRef(
        String handler) implements HorusExtension {
}
