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

    /**
     * Proposes as {@link #propose(String, WorkingCalendar, long, String)} does, once per idempotency key: a repeat
     * with the same key and the same request returns the first answer and writes nothing (PRE-182).
     *
     * @param key the calendar key
     * @param calendar the whole calendar after the change
     * @param expectedVersion the version the change is made against: 0 for a new calendar
     * @param reason why, at most 500 characters
     * @param idempotencyKey 1 to 200 characters, or null for no replay protection
     * @return the change as the first call returned it
     * @throws IdempotencyKeyReuseException when the key was used with a different request
     * @throws IdempotencyInFlightException when a request with the key is still running
     */
    CalendarChange propose(String key, WorkingCalendar calendar, long expectedVersion, String reason,
            String idempotencyKey);

    /**
     * Approves as {@link #approve(String, String)} does, once per idempotency key (PRE-182).
     *
     * @param changeId the change
     * @param comment an optional comment, at most 500 characters
     * @param idempotencyKey 1 to 200 characters, or null for no replay protection
     * @return the change as the first call returned it
     */
    CalendarChange approve(String changeId, String comment, String idempotencyKey);

    /**
     * Approves as {@link #approve(String, String, String)} does, only if the change is still the one the approver
     * reviewed (PD-139, #473).
     *
     * @param changeId the change
     * @param comment an optional comment, at most 500 characters
     * @param idempotencyKey 1 to 200 characters, or null for no replay protection
     * @param expectedChangeHash the {@code contentHash} of the change as shown in the pending approvals list, or null
     *     for no check; another value is {@code CONTENT_CHANGED} and nothing is written
     * @return the change as the first call returned it
     */
    default CalendarChange approve(String changeId, String comment, String idempotencyKey,
            String expectedChangeHash) {
        if (expectedChangeHash != null) {
            throw new UnsupportedOperationException("a reviewed hash is not supported by this service");
        }
        return approve(changeId, comment, idempotencyKey);
    }

    /**
     * Rejects as {@link #reject(String, String)} does, once per idempotency key (PRE-182).
     *
     * @param changeId the change
     * @param comment why, at most 500 characters
     * @param idempotencyKey 1 to 200 characters, or null for no replay protection
     * @return the change as the first call returned it
     */
    CalendarChange reject(String changeId, String comment, String idempotencyKey);

    /**
     * Lists the tenant's calendars by key (PRE-181). Needs {@code processes:read}.
     *
     * @param page which page
     * @return the summaries, without working hours or holidays
     * @throws QueryInvalidException when the limit or the cursor is not valid
     */
    Page<CalendarSummary> calendars(PageRequest page);

    /**
     * Lists the tenant's calendar changes of one status, newest first (PRE-181). Needs {@code processes:read}.
     *
     * @param status {@code PENDING}, {@code APPROVED} or {@code REJECTED}
     * @param page which page
     * @return the changes
     * @throws QueryInvalidException when the status, the limit or the cursor is not valid
     */
    Page<CalendarChange> changes(String status, PageRequest page);
}
