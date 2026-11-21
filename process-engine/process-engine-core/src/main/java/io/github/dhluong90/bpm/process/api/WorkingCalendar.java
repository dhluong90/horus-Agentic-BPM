package io.github.dhluong90.bpm.process.api;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A business calendar: a time zone, working hours per weekday and holidays ({@code TR-CAL-01}, {@code TR-CAL-02}).
 * Immutable, thread-safe and deterministic: every computation uses {@link #zone()}, never the JVM's default zone, so
 * the same calendar and the same instant give the same answer on every host.
 *
 * <p>Working hours are local times in the zone. On a day when the clocks change, an hour that does not exist is
 * skipped and an hour that exists twice is counted once per real hour, so working time is always real elapsed time.
 *
 * @param zone the calendar's time zone, for example {@code Asia/Ho_Chi_Minh}
 * @param week the working hours of each weekday; a day that is absent is not a working day
 * @param holidays local dates that are not working days, with a description each
 */
public record WorkingCalendar(ZoneId zone, Map<DayOfWeek, List<Hours>> week, SortedMap<LocalDate, String> holidays) {

    /**
     * How far {@link #add} searches for working time. A calendar always has working hours on some weekday, so only a
     * run of holidays longer than this can exhaust it; the call then fails closed instead of looping.
     */
    public static final int MAX_SEARCH_DAYS = 3_660;

    /** At most this many working-hour ranges per weekday. */
    public static final int MAX_RANGES_PER_DAY = 8;

    /** At most this many holidays per calendar. */
    public static final int MAX_HOLIDAYS = 2_000;

    /** At most this many characters in a holiday description. */
    public static final int MAX_DESCRIPTION = 200;

    /** Validates and copies. */
    public WorkingCalendar {
        Objects.requireNonNull(zone, "zone must not be null");
        Objects.requireNonNull(week, "week must not be null");
        Objects.requireNonNull(holidays, "holidays must not be null");
        EnumMap<DayOfWeek, List<Hours>> days = new EnumMap<>(DayOfWeek.class);
        week.forEach((day, ranges) -> {
            Objects.requireNonNull(day, "a weekday must not be null");
            List<Hours> sorted = new ArrayList<>(Objects.requireNonNull(ranges, "working hours of " + day));
            if (sorted.size() > MAX_RANGES_PER_DAY) {
                throw new IllegalArgumentException("At most " + MAX_RANGES_PER_DAY + " working-hour ranges on " + day);
            }
            sorted.sort((a, b) -> Integer.compare(a.startMinute(), b.startMinute()));
            for (int i = 1; i < sorted.size(); i++) {
                if (sorted.get(i).startMinute() < sorted.get(i - 1).endMinute()) {
                    throw new IllegalArgumentException("Working hours overlap on " + day + ": " + sorted.get(i - 1)
                            + " and " + sorted.get(i));
                }
            }
            if (!sorted.isEmpty()) {
                days.put(day, List.copyOf(sorted));
            }
        });
        if (days.isEmpty()) {
            throw new IllegalArgumentException("A calendar needs working hours on at least one weekday");
        }
        if (holidays.size() > MAX_HOLIDAYS) {
            throw new IllegalArgumentException("At most " + MAX_HOLIDAYS + " holidays per calendar");
        }
        TreeMap<LocalDate, String> dates = new TreeMap<>();
        holidays.forEach((date, description) -> {
            Objects.requireNonNull(date, "a holiday date must not be null");
            String text = description == null ? "" : description.strip();
            if (text.length() > MAX_DESCRIPTION || text.chars().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("A holiday description is at most " + MAX_DESCRIPTION
                        + " characters without control characters");
            }
            dates.put(date, text);
        });
        week = Collections.unmodifiableMap(days);
        holidays = Collections.unmodifiableSortedMap(dates);
    }

    /**
     * Adds working time to a start time.
     *
     * @param start when counting starts
     * @param workingTime the working time to add, positive
     * @return the instant at which {@code workingTime} of working time has passed since {@code start}; when the time
     *     runs out exactly at the end of a working range, that end
     * @throws IllegalArgumentException when {@code workingTime} is not positive
     * @throws IllegalStateException when no such instant lies within {@link #MAX_SEARCH_DAYS} days
     */
    public Instant add(Instant start, Duration workingTime) {
        Objects.requireNonNull(start, "start must not be null");
        Objects.requireNonNull(workingTime, "workingTime must not be null");
        if (workingTime.isNegative() || workingTime.isZero()) {
            throw new IllegalArgumentException("workingTime must be positive, was " + workingTime);
        }
        Duration remaining = workingTime;
        LocalDate day = start.atZone(zone).toLocalDate();
        for (int searched = 0; searched < MAX_SEARCH_DAYS; searched++, day = day.plusDays(1)) {
            for (Instant[] range : ranges(day)) {
                Instant from = range[0].isBefore(start) ? start : range[0];
                if (!from.isBefore(range[1])) {
                    continue;
                }
                Duration available = Duration.between(from, range[1]);
                if (remaining.compareTo(available) <= 0) {
                    return from.plus(remaining);
                }
                remaining = remaining.minus(available);
            }
        }
        throw new IllegalStateException("No working time left within " + MAX_SEARCH_DAYS + " days of " + start);
    }

    /**
     * @param at an instant
     * @return whether it is inside working hours of a working day that is not a holiday
     */
    public boolean isWorkingTime(Instant at) {
        Objects.requireNonNull(at, "at must not be null");
        for (Instant[] range : ranges(at.atZone(zone).toLocalDate())) {
            if (!at.isBefore(range[0]) && at.isBefore(range[1])) {
                return true;
            }
        }
        return false;
    }

    /** The working ranges of one local date as instants, empty on a holiday or a day off. */
    private List<Instant[]> ranges(LocalDate day) {
        List<Hours> hours = week.get(day.getDayOfWeek());
        if (hours == null || holidays.containsKey(day)) {
            return List.of();
        }
        List<Instant[]> out = new ArrayList<>(hours.size());
        for (Hours h : hours) {
            out.add(new Instant[] {at(day, h.startMinute()), at(day, h.endMinute())});
        }
        return out;
    }

    /** The instant of a minute of a local day; minute 1,440 is the start of the next day. */
    private Instant at(LocalDate day, int minute) {
        if (minute == Hours.END_OF_DAY) {
            return day.plusDays(1).atStartOfDay(zone).toInstant();
        }
        // A local time in a gap is moved forward by the gap; in an overlap the earlier offset is used.
        return ZonedDateTime.ofLocal(LocalDateTime.of(day, LocalTime.of(minute / 60, minute % 60)), zone, null)
                .toInstant();
    }

    /**
     * One range of working hours in a day, from a local start time (inclusive) to an end (exclusive), to the minute.
     *
     * @param startMinute minutes after midnight, 0 to 1,439
     * @param endMinute minutes after midnight, after the start, up to 1,440 ({@code 24:00})
     */
    public record Hours(int startMinute, int endMinute) {

        /** {@code 24:00}: the end of the day. */
        public static final int END_OF_DAY = 24 * 60;

        private static final Pattern RANGE = Pattern.compile("^([01][0-9]|2[0-3]):([0-5][0-9])-"
                + "(?:([01][0-9]|2[0-3]):([0-5][0-9])|(24):(00))$");

        /** Checks the range. */
        public Hours {
            if (startMinute < 0 || startMinute >= END_OF_DAY || endMinute <= startMinute || endMinute > END_OF_DAY) {
                throw new IllegalArgumentException("Working hours run from a start before 24:00 to a later end up to "
                        + "24:00, was " + startMinute + " to " + endMinute + " minutes");
            }
        }

        /**
         * @param text {@code HH:MM-HH:MM}, for example {@code 08:00-17:00}; the end may be {@code 24:00}
         * @return the range
         * @throws IllegalArgumentException when the text is not such a range or the end is not after the start
         */
        public static Hours parse(String text) {
            Matcher m = RANGE.matcher(text == null ? "" : text.strip());
            if (!m.matches()) {
                throw new IllegalArgumentException("Working hours are HH:MM-HH:MM, for example 08:00-17:00");
            }
            int start = Integer.parseInt(m.group(1)) * 60 + Integer.parseInt(m.group(2));
            int end = m.group(5) != null ? END_OF_DAY : Integer.parseInt(m.group(3)) * 60 + Integer.parseInt(m.group(4));
            if (end <= start) {
                throw new IllegalArgumentException("End must be after start: " + text.strip());
            }
            return new Hours(start, end);
        }

        @Override
        public String toString() {
            return "%02d:%02d-%02d:%02d".formatted(startMinute / 60, startMinute % 60, endMinute / 60, endMinute % 60);
        }
    }
}
