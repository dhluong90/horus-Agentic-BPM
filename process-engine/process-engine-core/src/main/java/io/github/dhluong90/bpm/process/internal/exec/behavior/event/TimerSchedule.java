package io.github.dhluong90.bpm.process.internal.exec.behavior.event;

import io.github.dhluong90.bpm.process.internal.model.bpmn.Expression;
import io.github.dhluong90.bpm.process.internal.model.bpmn.TimerEventDefinition;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * When a timer of this release falls due: {@code timeDuration} as an ISO 8601 duration in days, hours, minutes and
 * seconds ({@code PT10M}, {@code P3D}), counted from the moment the token arrives, or {@code timeDate} as an ISO 8601
 * date-time with an offset ({@code 2027-04-01T00:00:00+07:00}), or on an event sub-process start {@code timeCycle} as a
 * repeating interval ({@code R3/PT10M}). Calendar durations (months, years), expressions and business calendars come with the full event set. Pure; no I/O.
 */
public final class TimerSchedule {

    /** Shortest accepted duration: a timer is a wait state, not a busy loop. */
    public static final Duration MIN_DURATION = Duration.ofSeconds(1);

    private TimerSchedule() {
    }

    /**
     * @param timer the timer definition
     * @return why the timer cannot run in this release, or empty when it can
     */
    public static Optional<String> problem(TimerEventDefinition timer) {
        boolean hasDuration = text(timer.timeDuration()) != null;
        boolean hasDate = text(timer.timeDate()) != null;
        boolean hasCycle = timer.timeCycle() != null;
        if ((hasDuration ? 1 : 0) + (hasDate ? 1 : 0) + (hasCycle ? 1 : 0) != 1) {
            return Optional.of("A timer needs exactly one of timeDuration, timeDate and timeCycle");
        }
        if (hasCycle) {
            return cycle(timer).isPresent() ? Optional.empty() : Optional.of("timeCycle must be an ISO 8601 repeating"
                    + " interval in days, hours, minutes or seconds, for example R3/PT10M or R/PT1H, at least PT1S");
        }
        try {
            if (hasDuration) {
                Duration d = Duration.parse(text(timer.timeDuration()));
                if (d.compareTo(MIN_DURATION) < 0) {
                    return Optional.of("timeDuration must be at least PT1S");
                }
            } else {
                OffsetDateTime.parse(text(timer.timeDate()));
            }
        } catch (DateTimeException e) {
            return Optional.of(hasDuration
                    ? "timeDuration must be an ISO 8601 duration in days, hours, minutes or seconds, for example PT10M"
                    : "timeDate must be an ISO 8601 date-time with an offset, for example 2027-04-01T00:00:00+07:00");
        }
        return Optional.empty();
    }

    /**
     * @param timer a timer without {@link #problem}
     * @param arrivedAt when the token arrived
     * @return the due time
     * @throws IllegalArgumentException when the timer has a problem
     */
    public static Instant dueAt(TimerEventDefinition timer, Instant arrivedAt) {
        problem(timer).ifPresent(p -> {
            throw new IllegalArgumentException(p);
        });
        if (timer.timeCycle() != null) {
            return arrivedAt.plus(cycle(timer).orElseThrow().interval());
        }
        String duration = text(timer.timeDuration());
        return duration != null ? arrivedAt.plus(Duration.parse(duration))
                : OffsetDateTime.parse(text(timer.timeDate())).toInstant();
    }

    /**
     * @param timer the timer
     * @return the duration text, or null
     */
    public static String duration(TimerEventDefinition timer) {
        return text(timer.timeDuration());
    }

    /**
     * @param timer the timer
     * @return the date text, or null
     */
    public static String date(TimerEventDefinition timer) {
        return text(timer.timeDate());
    }

    /**
     * A repeating interval: {@code R3/PT10M} fires three times, {@code R/PT1H} without end, each one interval after the
     * one before (the first one interval after the timer is armed).
     *
     * @param repetitions how many times it fires, or {@code -1} for without end
     * @param interval the time between two firings
     */
    public record Cycle(long repetitions, Duration interval) {
    }

    /**
     * @param timer the timer
     * @return its repeating interval, or empty when it has no {@code timeCycle} or the text is not one this release runs
     */
    public static Optional<Cycle> cycle(TimerEventDefinition timer) {
        String text = text(timer.timeCycle());
        if (text == null) {
            return Optional.empty();
        }
        int slash = text.indexOf('/');
        if (slash < 1 || !text.startsWith("R")) {
            return Optional.empty();
        }
        try {
            String count = text.substring(1, slash);
            long repetitions = count.isEmpty() ? -1 : Long.parseLong(count);
            Duration interval = Duration.parse(text.substring(slash + 1));
            if (repetitions == 0 || repetitions < -1 || interval.compareTo(MIN_DURATION) < 0) {
                return Optional.empty();
            }
            return Optional.of(new Cycle(repetitions, interval));
        } catch (DateTimeException | NumberFormatException e) {
            return Optional.empty();
        }
    }

    private static String text(Expression e) {
        return e == null || e.text() == null || e.text().isBlank() ? null : e.text().strip();
    }
}
