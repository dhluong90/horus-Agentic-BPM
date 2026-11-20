package io.github.dhluong90.bpm.process.api;

/**
 * The stored business calendars of the caller's tenant (PE-F-11; PD-118 as amended by PD-131). A calendar is created
 * or changed by a proposal that a second holder of {@code processes:calendar:manage} approves; only the approval
 * applies it, and only to deadlines set after it. Under {@code horus.bpm.processes.calendar.approval=single}, which
 * start-up accepts only in the {@code dev} profile, a proposal applies at once. Every proposal and decision is kept
 * with the proposer, the decider, the old and the new value. The tenant comes from the host's context, never from an
 * argument. Thread-safe.
 */
public interface CalendarService {

    /**
     * Reads a calendar. Needs {@code processes:read}.
     *
     * @param key the calendar key
     * @return the calendar in force
     * @throws CalendarNotFoundException when the tenant has no calendar under this key
     */
    CalendarView calendar(String key);

    /**
     * Proposes a new calendar ({@code expectedVersion} 0) or a change of one. Needs {@code processes:calendar:manage}.
     *
     * @param key the calendar key, lower-case words joined by hyphens
     * @param calendar the whole calendar after the change
     * @param expectedVersion the version the change is made against: 0 for a new calendar
     * @param reason why, at most 500 characters
     * @return the change: {@code PENDING}, or {@code APPROVED} under single approval
     * @throws VersionConflictException when the calendar is not at {@code expectedVersion}
     * @throws InputInvalidException when the key or the reason is not valid
     */
    CalendarChange propose(String key, WorkingCalendar calendar, long expectedVersion, String reason);

    /**
     * Approves a pending change and applies it. Needs {@code processes:calendar:manage}; never the proposer.
     *
     * @param changeId the change
     * @param comment an optional comment, at most 500 characters
     * @return the approved change
     * @throws CalendarNotFoundException when the tenant has no such change
     * @throws SelfApprovalForbiddenException when the caller proposed it
     * @throws InvalidStateException when it is no longer pending
     * @throws VersionConflictException when the calendar changed since the proposal
     */
    CalendarChange approve(String changeId, String comment);

    /**
     * Rejects a pending change; the calendar stays as it is. Needs {@code processes:calendar:manage}; the proposer
     * may withdraw their own.
     *
     * @param changeId the change
     * @param comment why, at most 500 characters
     * @return the rejected change
     * @throws CalendarNotFoundException when the tenant has no such change
     * @throws InvalidStateException when it is no longer pending
     */
    CalendarChange reject(String changeId, String comment);

    /**
     * Reads one change. Needs {@code processes:read}.
     *
     * @param changeId the change
     * @return the change
     * @throws CalendarNotFoundException when the tenant has no such change
     */
    CalendarChange change(String changeId);
}
