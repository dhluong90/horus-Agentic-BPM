package io.github.dhluong90.bpm.process.internal.model.horus;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * {@code horus:escalation} and {@code horus:onClaimed} of a user task (PRE-178 to PRE-180): up to
 * {@link #MAX_LEVELS} levels. The first fires at the task's due time plus its {@code after}; each later level fires
 * its {@code after} of working time past the previous one, on the same calendar as the task's SLA.
 *
 * @param levels the levels in order, one to three
 * @param releaseClaim whether a claimed task is released when a level fires ({@code onClaimed=release}, the default)
 *     or its claimant keeps it ({@code keep})
 */
public record EscalationSpec(List<Level> levels, boolean releaseClaim) implements HorusExtension {

    /** The most levels a task declares. */
    public static final int MAX_LEVELS = 3;

    /**
     * One level.
     *
     * @param after working time past the previous level, or past the due time for the first; zero or positive
     * @param to the new assignment: {@code user:<id>}, {@code role:<role>} or {@code group:<group>}
     */
    public record Level(Duration after, String to) {

        /** Checks the parts. */
        public Level {
            Objects.requireNonNull(after, "after must not be null");
            Objects.requireNonNull(to, "to must not be null");
            if (after.isNegative()) {
                throw new IllegalArgumentException("An escalation level fires after a duration of zero or more");
            }
        }
    }

    /** Checks the number of levels. */
    public EscalationSpec {
        levels = List.copyOf(levels);
        if (levels.isEmpty() || levels.size() > MAX_LEVELS) {
            throw new IllegalArgumentException("A task declares one to " + MAX_LEVELS + " escalation levels");
        }
    }
}
