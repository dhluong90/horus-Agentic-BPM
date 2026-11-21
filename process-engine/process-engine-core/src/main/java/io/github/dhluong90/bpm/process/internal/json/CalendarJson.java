package io.github.dhluong90.bpm.process.internal.json;

import io.github.dhluong90.bpm.process.api.WorkingCalendar;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * A {@link WorkingCalendar} as the change record, the idempotency record and the REST body keep it:
 * {@code {"zone": "...", "week": {"MONDAY": ["08:00-17:00"]}, "holidays": {"2027-01-01": "New Year"}}}.
 */
public final class CalendarJson {

    private CalendarJson() {
    }

    /**
     * @param c the calendar
     * @return zone, week and holidays, in a stable order
     */
    public static Map<String, Object> toMap(WorkingCalendar c) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("zone", c.zone().getId());
        out.put("week", week(c));
        Map<String, Object> holidays = new LinkedHashMap<>();
        c.holidays().forEach((date, description) -> holidays.put(date.toString(), description));
        out.put("holidays", holidays);
        return out;
    }

    /**
     * @param c the calendar
     * @return its working hours alone, per weekday
     */
    public static Map<String, Object> week(WorkingCalendar c) {
        Map<String, Object> week = new LinkedHashMap<>();
        c.week().forEach((day, hours) -> week.put(day.name(), hours.stream().map(Object::toString).toList()));
        return week;
    }

    /**
     * @param json a map as {@link #toMap} writes it
     * @return the calendar
     * @throws IllegalArgumentException when a part is missing or malformed
     */
    @SuppressWarnings("unchecked")
    public static WorkingCalendar fromMap(Map<String, Object> json) {
        try {
            TreeMap<LocalDate, String> holidays = new TreeMap<>();
            Object declared = json.get("holidays");
            if (declared != null) {
                ((Map<String, Object>) declared).forEach((date, d) -> holidays.put(LocalDate.parse(date),
                        d == null ? "" : (String) d));
            }
            return new WorkingCalendar(ZoneId.of((String) json.get("zone")), week((Map<String, Object>) json.get("week")),
                    holidays);
        } catch (ClassCastException | java.time.DateTimeException | NullPointerException e) {
            throw new IllegalArgumentException("A calendar is a zone, working hours per weekday and holidays", e);
        }
    }

    /**
     * @param json weekday name to ranges such as {@code 08:00-17:00}
     * @return the working hours
     */
    @SuppressWarnings("unchecked")
    public static Map<DayOfWeek, List<WorkingCalendar.Hours>> week(Map<String, Object> json) {
        EnumMap<DayOfWeek, List<WorkingCalendar.Hours>> week = new EnumMap<>(DayOfWeek.class);
        json.forEach((day, ranges) -> {
            List<WorkingCalendar.Hours> hours = new ArrayList<>();
            for (Object range : (List<Object>) ranges) {
                hours.add(WorkingCalendar.Hours.parse((String) range));
            }
            week.put(DayOfWeek.valueOf(day), hours);
        });
        return week;
    }
}
