package io.github.dhluong90.bpm.process.api;

import java.time.Instant;
import java.util.Objects;

/**
 * A stored business calendar of the caller's tenant.
 *
 * @param key the calendar key a {@code userTask} names in {@code horus:calendar}
 * @param version the version, 1 after creation, one more after each approved change
 * @param calendar time zone, working hours and holidays
 * @param updatedBy who proposed the change now in force
 * @param approvedBy who approved it; the proposer only under {@code calendar.approval=single} (dev profile)
 * @param updatedAt when that change was approved
 */
public record CalendarView(String key, long version, WorkingCalendar calendar, String updatedBy, String approvedBy,
        Instant updatedAt) {

    public CalendarView {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(calendar, "calendar must not be null");
    }
}
