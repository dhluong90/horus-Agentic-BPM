package io.github.dhluong90.bpm.process.internal.model.horus;

import java.time.Duration;

/**
 * {@code horus:sla} of a user task.
 *
 * @param duration duration
 * @param calendar calendar
 * @param escalateTo escalateTo
 */
public record SlaSpec(
        Duration duration,
        String calendar,
        String escalateTo) implements HorusExtension {
}
