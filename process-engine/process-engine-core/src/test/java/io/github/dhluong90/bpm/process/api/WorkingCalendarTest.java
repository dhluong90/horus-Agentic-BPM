package io.github.dhluong90.bpm.process.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.TimeZone;
import java.util.TreeMap;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * PRE-008: working time of a business calendar, as the engine computes a task's due time. Examples from the bank's
 * calendar {@code vn-banking-hours}: Monday to Friday 08:00 to 17:00 Asia/Ho_Chi_Minh, New Year's Day off.
 */
class WorkingCalendarTest {

    private static final ZoneId HCM = ZoneId.of("Asia/Ho_Chi_Minh");

    static WorkingCalendar vnBankingHours(String... holidays) {
        Map<DayOfWeek, List<WorkingCalendar.Hours>> week = new EnumMap<>(DayOfWeek.class);
        for (DayOfWeek day : List.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY,
                DayOfWeek.FRIDAY)) {
            week.put(day, List.of(WorkingCalendar.Hours.parse("08:00-17:00")));
        }
        TreeMap<LocalDate, String> dates = new TreeMap<>();
        for (String h : holidays) {
            dates.put(LocalDate.parse(h), "Public holiday");
        }
        return new WorkingCalendar(HCM, week, dates);
    }

    private static Instant at(String offsetTime) {
        return OffsetDateTime.parse(offsetTime).toInstant();
    }

    @Test
    void PRE_008_business_calendar_skips_holidays_and_weekends() {
        // Given vn-banking-hours with 2027-01-01 off, and a task created on Thursday 31 Dec 2026 at 15:00 +07:00
        WorkingCalendar calendar = vnBankingHours("2027-01-01");

        // When four working hours are added
        Instant due = calendar.add(at("2026-12-31T15:00:00+07:00"), Duration.ofHours(4));

        // Then 15:00 to 17:00 counts two hours, Friday 1 Jan and the weekend are skipped, and Monday 08:00 to 10:00
        // counts the rest
        assertThat(due).isEqualTo(at("2027-01-04T10:00:00+07:00"));
    }

    @Test
    void PRE_008_working_time_that_runs_out_at_closing_is_due_at_closing() {
        WorkingCalendar calendar = vnBankingHours();

        assertThat(calendar.add(at("2027-01-04T15:00:00+07:00"), Duration.ofHours(2)))
                .isEqualTo(at("2027-01-04T17:00:00+07:00"));
        assertThat(calendar.add(at("2027-01-04T15:00:00+07:00"), Duration.ofHours(2).plusMinutes(1)))
                .isEqualTo(at("2027-01-05T08:01:00+07:00"));
    }

    @Test
    void PRE_008_a_task_created_out_of_hours_starts_counting_at_the_next_opening() {
        WorkingCalendar calendar = vnBankingHours();

        // Saturday 2 Jan 2027 at 21:30: counting starts on Monday at 08:00
        assertThat(calendar.add(at("2027-01-02T21:30:00+07:00"), Duration.ofMinutes(90)))
                .isEqualTo(at("2027-01-04T09:30:00+07:00"));
        assertThat(calendar.isWorkingTime(at("2027-01-02T10:00:00+07:00"))).isFalse();
        assertThat(calendar.isWorkingTime(at("2027-01-04T08:00:00+07:00"))).isTrue();
        assertThat(calendar.isWorkingTime(at("2027-01-04T17:00:00+07:00"))).isFalse();
    }

    @Test
    void PRE_008_a_lunch_break_is_not_working_time() {
        Map<DayOfWeek, List<WorkingCalendar.Hours>> week = new EnumMap<>(DayOfWeek.class);
        week.put(DayOfWeek.MONDAY, List.of(WorkingCalendar.Hours.parse("13:00-17:00"),
                WorkingCalendar.Hours.parse("08:00-12:00")));
        WorkingCalendar calendar = new WorkingCalendar(HCM, week, new TreeMap<>());

        assertThat(calendar.add(at("2027-01-04T11:00:00+07:00"), Duration.ofHours(2)))
                .isEqualTo(at("2027-01-04T14:00:00+07:00"));
    }

    @Test
    void PRE_008_working_time_across_a_clock_change_is_real_elapsed_time() {
        // Given a 24-hour branch in New York; clocks go forward at 02:00 on Sunday 14 March 2027
        Map<DayOfWeek, List<WorkingCalendar.Hours>> week = new EnumMap<>(DayOfWeek.class);
        for (DayOfWeek day : DayOfWeek.values()) {
            week.put(day, List.of(WorkingCalendar.Hours.parse("00:00-24:00")));
        }
        WorkingCalendar calendar = new WorkingCalendar(ZoneId.of("America/New_York"), week, new TreeMap<>());

        // When 24 working hours are added from midnight of that 23-hour day
        Instant due = calendar.add(at("2027-03-14T00:00:00-05:00"), Duration.ofHours(24));

        // Then 24 real hours have passed: 01:00 the next day in summer time
        assertThat(due).isEqualTo(at("2027-03-15T01:00:00-04:00"));
        assertThat(Duration.between(at("2027-03-14T00:00:00-05:00"), due)).isEqualTo(Duration.ofHours(24));
    }

    @Test
    void PRE_008_a_calendar_without_working_time_is_refused() {
        assertThatThrownBy(() -> new WorkingCalendar(HCM, Map.of(), new TreeMap<>()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("at least one weekday");
        assertThatThrownBy(() -> WorkingCalendar.Hours.parse("17:00-08:00"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("End must be after start");
        assertThatThrownBy(() -> WorkingCalendar.Hours.parse("8-17")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WorkingCalendar(HCM, Map.of(DayOfWeek.MONDAY, List.of(
                WorkingCalendar.Hours.parse("08:00-12:00"), WorkingCalendar.Hours.parse("11:00-17:00"))),
                new TreeMap<>())).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("overlap");
        assertThatThrownBy(() -> vnBankingHours().add(at("2027-01-04T08:00:00+07:00"), Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void PRE_008_a_calendar_whose_working_days_are_all_holidays_fails_closed() {
        // Given a branch open on Mondays only, with every Monday of the search horizon a holiday
        TreeMap<LocalDate, String> holidays = new TreeMap<>();
        LocalDate monday = LocalDate.parse("2027-01-04");
        for (int week = 0; week <= WorkingCalendar.MAX_SEARCH_DAYS / 7; week++) {
            holidays.put(monday.plusWeeks(week), "Closed");
        }
        WorkingCalendar calendar = new WorkingCalendar(HCM,
                Map.of(DayOfWeek.MONDAY, List.of(WorkingCalendar.Hours.parse("08:00-17:00"))), holidays);

        // Then adding time fails instead of searching forever
        assertThatThrownBy(() -> calendar.add(at("2027-01-04T08:00:00+07:00"), Duration.ofHours(1)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("No working time");
    }

    /**
     * Seeded property, 300 tries: for a random start in 2026 to 2028 and two random working durations, the due time
     * does not depend on the JVM's default time zone, lies in working time or at a closing time, and adding the two
     * durations one after the other gives the same due time as adding their sum.
     */
    @TestFactory
    Stream<DynamicTest> PRE_008_due_times_are_deterministic_across_time_zones_and_additive() {
        WorkingCalendar calendar = vnBankingHours("2027-01-01", "2027-02-05", "2027-02-08", "2027-04-30",
                "2027-05-03", "2027-09-02");
        long seed = Long.getLong("bpm.property.seed", 0x5EED_2026_1121L);
        SplittableRandom random = new SplittableRandom(seed);
        Instant from = at("2026-01-01T00:00:00Z");
        List<String> zones = List.of("UTC", "Asia/Ho_Chi_Minh", "America/Los_Angeles", "Pacific/Kiritimati",
                "Europe/Berlin");
        return IntStream.range(0, 300).mapToObj(i -> {
            Instant start = from.plusSeconds(random.nextLong(3L * 365 * 24 * 3600));
            Duration a = Duration.ofMinutes(1 + random.nextInt(60 * 40));
            Duration b = Duration.ofMinutes(1 + random.nextInt(60 * 40));
            return DynamicTest.dynamicTest("try " + i + " seed " + seed + ": " + start + " + " + a + " + " + b, () -> {
                TimeZone original = TimeZone.getDefault();
                try {
                    Instant expected = calendar.add(start, a.plus(b));
                    for (String zone : zones) {
                        TimeZone.setDefault(TimeZone.getTimeZone(zone));
                        assertThat(calendar.add(start, a.plus(b))).as("default zone " + zone).isEqualTo(expected);
                    }
                    assertThat(calendar.add(calendar.add(start, a), b)).isEqualTo(expected);
                    boolean atClosing = expected.atZone(HCM).toLocalTime().toString().equals("17:00");
                    assertThat(atClosing || calendar.isWorkingTime(expected)).isTrue();
                } finally {
                    TimeZone.setDefault(original);
                }
            });
        });
    }
}
