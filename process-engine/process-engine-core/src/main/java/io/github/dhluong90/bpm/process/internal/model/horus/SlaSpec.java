package io.github.dhluong90.bpm.process.internal.model.horus;

import java.time.Duration;

/**
 * {@code horus:sla} and {@code horus:calendar} of a user task (PRE-008): the task is due after {@code duration} of
 * working time in {@code calendar}, counted from its creation.
 *
 * @param duration the working time, positive
 * @param calendar the calendar key, or null for elapsed time
 * @param escalateTo where an overdue task escalates; not read in this release, null
 */
public record SlaSpec(
        Duration duration,
        String calendar,
        String escalateTo) implements HorusExtension {

    /** Checks the duration. */
    public SlaSpec {
        java.util.Objects.requireNonNull(duration, "duration must not be null");
        if (duration.isNegative() || duration.isZero()) {
            throw new IllegalArgumentException("An SLA is a positive duration, was " + duration);
        }
    }
}
