package io.github.dhluong90.bpm.process.api;

import java.time.Instant;
import java.util.Objects;

/**
 * A calendar in a list: no working hours and no holidays, which {@link CalendarService#calendar} returns.
 *
 * @param key the calendar key
 * @param version the version in force
 * @param zone the time zone
 * @param updatedBy who proposed the change in force
 * @param approvedBy who approved it
 * @param updatedAt when it was approved
 */
public record CalendarSummary(String key, long version, String zone, String updatedBy, String approvedBy,
        Instant updatedAt) {

    public CalendarSummary {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(zone, "zone must not be null");
    }
}
