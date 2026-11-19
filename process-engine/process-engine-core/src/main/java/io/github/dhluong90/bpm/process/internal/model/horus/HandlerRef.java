package io.github.dhluong90.bpm.process.internal.model.horus;

import java.time.Duration;

/**
 * {@code horus:handler}, {@code horus:mode} and {@code horus:timeout} of a service task.
 *
 * @param handler handler
 * @param mode mode
 * @param timeout timeout
 */
public record HandlerRef(
        String handler,
        String mode,
        Duration timeout) implements HorusExtension {
}
