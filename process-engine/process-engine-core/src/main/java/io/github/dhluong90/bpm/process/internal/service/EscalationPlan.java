package io.github.dhluong90.bpm.process.internal.service;

import io.github.dhluong90.bpm.process.internal.json.Values;
import io.github.dhluong90.bpm.process.internal.model.horus.EscalationSpec;
import io.github.dhluong90.bpm.process.internal.model.horus.SlaSpec;
import io.github.dhluong90.bpm.process.spi.BusinessCalendar;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The escalation of one task with every level's fire time worked out (PRE-178, PRE-179): computed once when the task
 * is created, with the engine clock and the calendar of its SLA, and stored with the task, so a calendar change made
 * later moves no level that was already set (TR-CAL-02) and the sweep needs no calendar.
 *
 * @param releaseClaim whether a claimed task is released when a level fires
 * @param levels the levels in order, with their fire times
 */
record EscalationPlan(boolean releaseClaim, List<Level> levels) {

    /**
     * One level.
     *
     * @param to {@code user:}, {@code role:} or {@code group:}
     * @param at when it fires
     */
    record Level(String to, Instant at) {
    }

    /**
     * Works out the fire time of every level: the first at the due time plus its {@code after}, each later one its
     * {@code after} of working time past the previous.
     *
     * @param tenant the tenant of the instance row
     * @param spec the declared levels
     * @param sla the task's SLA, whose calendar counts the waits
     * @param dueAt the task's due time
     * @param calendar the business calendar
     * @return the plan
     * @throws io.github.dhluong90.bpm.process.api.CalendarNotFoundException when the calendar is unknown
     * @throws IllegalStateException when the calendar has no working time ahead
     */
    static EscalationPlan resolve(String tenant, EscalationSpec spec, SlaSpec sla, Instant dueAt,
            BusinessCalendar calendar) {
        List<Level> levels = new ArrayList<>();
        Instant previous = dueAt;
        for (EscalationSpec.Level level : spec.levels()) {
            Instant at = level.after().isZero() ? previous
                    : calendar.add(tenant, sla.calendar(), previous, level.after());
            levels.add(new Level(level.to(), at));
            previous = at;
        }
        return new EscalationPlan(spec.releaseClaim(), levels);
    }

    /** @return when the first level fires */
    Instant firstAt() {
        return levels.get(0).at();
    }

    /** @return the plan as the task keeps it */
    String toJson() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("onClaimed", releaseClaim ? "release" : "keep");
        List<Object> list = new ArrayList<>();
        for (Level l : levels) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("to", l.to());
            m.put("at", l.at().toString());
            list.add(m);
        }
        out.put("levels", list);
        return Values.write(out);
    }

    /**
     * @param json a stored plan
     * @return the plan
     */
    @SuppressWarnings("unchecked")
    static EscalationPlan fromJson(String json) {
        Map<String, Object> m = Values.readMap(json);
        List<Level> levels = new ArrayList<>();
        for (Object o : (List<Object>) m.get("levels")) {
            Map<String, Object> l = (Map<String, Object>) o;
            levels.add(new Level((String) l.get("to"), Instant.parse((String) l.get("at"))));
        }
        return new EscalationPlan(!"keep".equals(m.get("onClaimed")), levels);
    }
}
