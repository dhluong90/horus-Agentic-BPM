package io.github.dhluong90.bpm.process.internal.store;

import io.github.dhluong90.bpm.process.api.WorkingCalendar;
import java.time.Instant;
import java.util.List;
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

    /**
     * A calendar in the tenant's list.
     *
     * @param key calendar key
     * @param version lock version
     * @param zone time zone
     * @param updatedBy proposer of the change in force
     * @param approvedBy approver of the change in force
     * @param updatedAt when it was approved
     */
    record SummaryRow(String key, long version, String zone, String updatedBy, String approvedBy, Instant updatedAt) {
    }

    /**
     * A page of the tenant's calendars by key.
     *
     * @param afterKey the last key of the previous page, or null
     * @param limit most rows
     * @return the rows, in key order
     */
    List<SummaryRow> calendars(String tenant, String afterKey, int limit);

    /**
     * A page of the tenant's changes of one status, newest first.
     *
     * @param afterAt keyset: {@code proposedAt} of the last row of the previous page, or null
     * @param afterId keyset: its id
     * @param limit most rows
     * @return the rows
     */
    List<ChangeRow> changes(String tenant, String status, Instant afterAt, String afterId, int limit);

    /**
     * A page of the tenant's {@code PENDING} changes, oldest proposal first on {@code (proposed_at, id)}, for the
     * pending approvals list (#473).
     *
     * @param afterAt keyset: {@code proposedAt} of the last row of the previous page, or null
     * @param afterId keyset: its id
     * @param limit most rows
     * @return the rows
     */
    default List<ChangeRow> pendingChangesOldestFirst(String tenant, Instant afterAt, String afterId, int limit) {
        throw new UnsupportedOperationException("pending changes are not supported by this store");
    }

    /** @return the recorded answer of an idempotency key that has not expired */
    Optional<ProcessStore.IdempotencyRecord> idempotency(String tenant, String operation, String key, Instant now);

    /** Claims a key before the work runs; see {@link ProcessStore#claimIdempotency}. */
    boolean claimIdempotency(String tenant, String operation, String key, String requestHash, Instant expiresAt,
            Instant now);

    /** Stores the answer of a claimed key in the same transaction as the work. */
    void completeIdempotency(String tenant, String operation, String key, String responseJson);

    /** @return the engine secret of this purpose, created when none exists; see {@link ProcessStore#engineKey} */
    byte[] engineKey(String name, byte[] candidate, Instant now);
}
