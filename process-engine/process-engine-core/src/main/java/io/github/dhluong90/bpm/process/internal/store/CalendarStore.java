package io.github.dhluong90.bpm.process.internal.store;

import io.github.dhluong90.bpm.process.api.WorkingCalendar;
import java.time.Instant;
import java.util.Optional;

/**
 * Storage port of the business calendars ({@code pre_calendar}, {@code pre_holiday}, {@code pre_calendar_change}).
 * Every method takes the tenant and every statement filters by it first (TR-TEN-01). Methods join the caller's
 * transaction. Implementations must be thread-safe.
 */
public interface CalendarStore {

    /**
     * A calendar in force.
     *
     * @param id row id
     * @param key calendar key
     * @param version lock version
     * @param calendar zone, hours and holidays
     * @param updatedBy proposer of the change in force
     * @param approvedBy approver of the change in force
     * @param updatedAt when it was approved
     */
    record StoredCalendar(String id, String key, long version, WorkingCalendar calendar, String updatedBy,
            String approvedBy, Instant updatedAt) {
    }

    /**
     * A change row.
     *
     * @param tenantId tenant
     * @param id change id
     * @param calendarKey calendar key
     * @param status PENDING, APPROVED or REJECTED
     * @param approval FOUR_EYES or SINGLE
     * @param baseVersion version proposed against, 0 for a new calendar
     * @param previous calendar before, or null
     * @param proposed calendar after
     * @param reason reason
     * @param proposedBy proposer
     * @param proposedAt when proposed
     * @param decidedBy decider, or null
     * @param decidedAt when decided, or null
     * @param comment decider's comment, or null
     */
    record ChangeRow(String tenantId, String id, String calendarKey, String status, String approval, long baseVersion,
            WorkingCalendar previous, WorkingCalendar proposed, String reason, String proposedBy, Instant proposedAt,
            String decidedBy, Instant decidedAt, String comment) {
    }

    /** @return the calendar in force, read in the caller's transaction */
    Optional<StoredCalendar> calendar(String tenant, String key);

    /** @return the calendar row, locked {@code FOR UPDATE} until the transaction ends */
    Optional<StoredCalendar> lockCalendar(String tenant, String key);

    /**
     * Inserts a calendar at version 1 with its holidays.
     *
     * @return false when the key exists already in the tenant
     */
    boolean insertCalendar(String tenant, String id, String key, WorkingCalendar calendar, String updatedBy,
            String approvedBy, Instant at);

    /** Replaces hours and holidays of a locked calendar and moves it to {@code version}. */
    void replaceCalendar(String tenant, String id, long version, WorkingCalendar calendar, String updatedBy,
            String approvedBy, Instant at);

    void insertChange(ChangeRow change);

    /** @return the change, locked {@code FOR UPDATE} */
    Optional<ChangeRow> lockChange(String tenant, String id);

    Optional<ChangeRow> change(String tenant, String id);

    /** Records the decision of a pending change. */
    void decideChange(String tenant, String id, String status, String decidedBy, Instant decidedAt, String comment);
}
