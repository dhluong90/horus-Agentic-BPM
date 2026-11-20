package io.github.dhluong90.bpm.process.spi;

import java.time.Duration;
import java.time.Instant;

/**
 * Working time of a tenant's business calendar, used to set the due time of a user task with an SLA
 * ({@code horus:sla}, {@code horus:calendar}; {@code PRE-008}, {@code TR-CAL-01}, {@code TR-CAL-02}).
 *
 * <p>The engine calls it once, when it creates the task, inside the step transaction, with the tenant of the locked
 * instance row (never a value from a request) and its own clock's time as {@code start}. The answer is stored as the
 * task's due time and never computed again, so a later calendar change does not move it. An implementation must be
 * deterministic for its inputs and must not depend on the JVM's default time zone. It must be thread-safe.
 *
 * <p>The engine's default reads the calendars stored for each tenant, whose changes need a second person
 * ({@code CalendarService}). A host that keeps its calendars elsewhere replaces this bean.
 */
public interface BusinessCalendar {

    /** Elapsed time: every instant is working time. What a task without {@code horus:calendar} counts. */
    BusinessCalendar ELAPSED = new BusinessCalendar() {
        @Override
        public Instant add(String tenantId, String calendarKey, Instant start, Duration workingTime) {
            return start.plus(workingTime);
        }

        @Override
        public boolean isBusinessTime(String tenantId, String calendarKey, Instant at) {
            return true;
        }
    };

    /**
     * Adds working time to a start time.
     *
     * @param tenantId the tenant of the instance
     * @param calendarKey the calendar the step declares, or null for elapsed time
     * @param start when counting starts
     * @param workingTime the working time to add, positive
     * @return the instant at which that much working time has passed
     * @throws io.github.dhluong90.bpm.process.api.CalendarNotFoundException when the tenant has no calendar under
     *     this key; the step then fails and nothing is written
     */
    Instant add(String tenantId, String calendarKey, Instant start, Duration workingTime);

    /**
     * @param tenantId the tenant
     * @param calendarKey the calendar, or null for elapsed time
     * @param at the instant
     * @return whether {@code at} is working time: inside working hours, on a working day that is not a holiday
     * @throws io.github.dhluong90.bpm.process.api.CalendarNotFoundException when the tenant has no such calendar
     */
    boolean isBusinessTime(String tenantId, String calendarKey, Instant at);
}
