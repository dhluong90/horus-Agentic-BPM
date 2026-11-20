package io.github.dhluong90.bpm.process.internal.service;

import io.github.dhluong90.bpm.process.api.CalendarChange;
import io.github.dhluong90.bpm.process.api.CalendarNotFoundException;
import io.github.dhluong90.bpm.process.api.CalendarService;
import io.github.dhluong90.bpm.process.api.CalendarView;
import io.github.dhluong90.bpm.process.api.InputInvalidException;
import io.github.dhluong90.bpm.process.api.InvalidStateException;
import io.github.dhluong90.bpm.process.api.PermissionDeniedException;
import io.github.dhluong90.bpm.process.api.SelfApprovalForbiddenException;
import io.github.dhluong90.bpm.process.api.UnauthenticatedException;
import io.github.dhluong90.bpm.process.api.VersionConflictException;
import io.github.dhluong90.bpm.process.api.WorkingCalendar;
import io.github.dhluong90.bpm.process.internal.json.Ulid;
import io.github.dhluong90.bpm.process.internal.store.CalendarStore;
import io.github.dhluong90.bpm.process.internal.store.CalendarStore.ChangeRow;
import io.github.dhluong90.bpm.process.internal.store.CalendarStore.StoredCalendar;
import io.github.dhluong90.bpm.process.internal.store.TransactionRunner;
import io.github.dhluong90.bpm.process.spi.BusinessCalendar;
import io.github.dhluong90.bpm.process.spi.CallerResolver;
import io.github.dhluong90.bpm.process.spi.ProcessEventPublisher;
import io.github.dhluong90.bpm.process.spi.TenantResolver;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The engine's {@link CalendarService} over {@code pre_calendar}, and the engine's default {@link BusinessCalendar}
 * ({@link #businessCalendar()}), which reads the same rows inside the step transaction (PRE-008). Four eyes is the
 * default (PD-131): a proposal applies only when a second holder of {@code processes:calendar:manage} approves it,
 * and the database refuses an approved four-eyes row whose approver is its proposer. Thread-safe.
 */
public final class Calendars implements CalendarService {

    /** Whether a calendar change needs a second person. */
    public enum Approval {
        /** A second holder of {@code processes:calendar:manage} approves; the default. */
        FOUR_EYES,
        /** The proposer applies it alone; accepted by the starter only in the {@code dev} profile. */
        SINGLE
    }

    static final String PERMISSION_READ = "processes:read";
    static final String PERMISSION_MANAGE = "processes:calendar:manage";
    static final String EVENT_CHANGED = "processes.calendar.changed.v1";
    private static final String SYSTEM_PREFIX = "system:";
    private static final int MAX_KEY_LENGTH = 100;
    private static final int MAX_TEXT = 500;
    private static final Pattern KEY = Pattern.compile("^[a-z][a-z0-9]*(-[a-z0-9]+)*$");

    private final TenantResolver tenants;
    private final CallerResolver callers;
    private final Clock clock;
    private final TransactionRunner transactions;
    private final CalendarStore store;
    private final ProcessEventPublisher publisher;
    private final Approval approval;

    /**
     * @param tenants tenant resolver
     * @param callers caller resolver
     * @param clock engine clock
     * @param transactions transaction runner on the host's transaction manager
     * @param store calendar storage
     * @param publisher event publisher, called in the approving transaction
     * @param approval four eyes or, in the dev profile only, single
     */
    public Calendars(TenantResolver tenants, CallerResolver callers, Clock clock, TransactionRunner transactions,
            CalendarStore store, ProcessEventPublisher publisher, Approval approval) {
        this.tenants = Objects.requireNonNull(tenants);
        this.callers = Objects.requireNonNull(callers);
        this.clock = Objects.requireNonNull(clock);
        this.transactions = Objects.requireNonNull(transactions);
        this.store = Objects.requireNonNull(store);
        this.publisher = Objects.requireNonNull(publisher);
        this.approval = Objects.requireNonNull(approval);
    }

    /**
     * The engine's default {@link BusinessCalendar}: elapsed time for a task without {@code horus:calendar}, the
     * stored calendar of the given tenant otherwise. It reads in the caller's transaction and checks no permission:
     * the engine calls it with the tenant of the locked instance row, never one from a request.
     *
     * @return the business calendar
     */
    public BusinessCalendar businessCalendar() {
        return new BusinessCalendar() {
            @Override
            public Instant add(String tenantId, String calendarKey, Instant start, Duration workingTime) {
                if (calendarKey == null) {
                    return ELAPSED.add(tenantId, null, start, workingTime);
                }
                return stored(tenantId, calendarKey).add(start, workingTime);
            }

            @Override
            public boolean isBusinessTime(String tenantId, String calendarKey, Instant at) {
                return calendarKey == null || stored(tenantId, calendarKey).isWorkingTime(at);
            }
        };
    }

    private WorkingCalendar stored(String tenantId, String key) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        return store.calendar(tenantId, key).map(StoredCalendar::calendar)
                .orElseThrow(() -> new CalendarNotFoundException("No calendar " + safe(key) + " in this tenant"));
    }

    @Override
    public CalendarView calendar(String key) {
        String tenant = tenant();
        caller(PERMISSION_READ);
        requireKey(key);
        StoredCalendar c = transactions.inTransaction(() -> store.calendar(tenant, key))
                .orElseThrow(() -> new CalendarNotFoundException("No calendar " + key + " in this tenant"));
        return new CalendarView(c.key(), c.version(), c.calendar(), c.updatedBy(), c.approvedBy(), c.updatedAt());
    }

    @Override
    public CalendarChange propose(String key, WorkingCalendar calendar, long expectedVersion, String reason) {
        String tenant = tenant();
        String caller = caller(PERMISSION_MANAGE);
        requireKey(key);
        Objects.requireNonNull(calendar, "calendar must not be null");
        String why = text(reason, "reason", true);
        if (expectedVersion < 0) {
            throw new InputInvalidException("expectedVersion is 0 for a new calendar, or the version changed");
        }
        return transactions.inTransaction(() -> {
            StoredCalendar current = store.lockCalendar(tenant, key).orElse(null);
            long version = current == null ? 0 : current.version();
            if (version != expectedVersion) {
                throw new VersionConflictException("Calendar " + key + " is at version " + version + ", not "
                        + expectedVersion);
            }
            Instant now = clock.instant();
            ChangeRow change = new ChangeRow(tenant, Ulid.next(now), key, "PENDING", approval.name(), version,
                    current == null ? null : current.calendar(), calendar, why, caller, now, null, null, null);
            store.insertChange(change);
            if (approval == Approval.SINGLE) {
                return view(apply(change, current, caller, null, now));
            }
            return view(change);
        });
    }

    @Override
    public CalendarChange approve(String changeId, String comment) {
        String tenant = tenant();
        String caller = caller(PERMISSION_MANAGE);
        String note = text(comment, "comment", false);
        return transactions.inTransaction(() -> {
            ChangeRow change = pending(tenant, changeId);
            if (change.proposedBy().equals(caller)) {
                throw new SelfApprovalForbiddenException("The proposer of a calendar change cannot approve it");
            }
            StoredCalendar current = store.lockCalendar(tenant, change.calendarKey()).orElse(null);
            long version = current == null ? 0 : current.version();
            if (version != change.baseVersion()) {
                throw new VersionConflictException("Calendar " + change.calendarKey() + " changed since the proposal: "
                        + "version " + version + ", proposed against " + change.baseVersion());
            }
            return view(apply(change, current, caller, note, clock.instant()));
        });
    }

    @Override
    public CalendarChange reject(String changeId, String comment) {
        String tenant = tenant();
        String caller = caller(PERMISSION_MANAGE);
        String note = text(comment, "comment", true);
        return transactions.inTransaction(() -> {
            ChangeRow change = pending(tenant, changeId);
            Instant now = clock.instant();
            store.decideChange(tenant, change.id(), "REJECTED", caller, now, note);
            return view(decided(change, "REJECTED", caller, now, note));
        });
    }

    @Override
    public CalendarChange change(String changeId) {
        String tenant = tenant();
        caller(PERMISSION_READ);
        return view(transactions.inTransaction(() -> store.change(tenant, requireId(changeId)))
                .orElseThrow(() -> new CalendarNotFoundException("No calendar change " + safe(changeId))));
    }

    /** Applies an approved change under the calendar lock, records the decision and emits the event. */
    private ChangeRow apply(ChangeRow change, StoredCalendar current, String approver, String comment, Instant now) {
        long version;
        if (current == null) {
            if (!store.insertCalendar(change.tenantId(), Ulid.next(now), change.calendarKey(), change.proposed(),
                    change.proposedBy(), approver, now)) {
                throw new VersionConflictException("Calendar " + change.calendarKey() + " was created meanwhile");
            }
            version = 1;
        } else {
            version = current.version() + 1;
            store.replaceCalendar(change.tenantId(), current.id(), version, change.proposed(), change.proposedBy(),
                    approver, now);
        }
        store.decideChange(change.tenantId(), change.id(), "APPROVED", approver, now, comment);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("calendarKey", change.calendarKey());
        payload.put("changeId", change.id());
        payload.put("version", version);
        payload.put("proposedBy", change.proposedBy());
        payload.put("approvedBy", approver);
        payload.put("approval", change.approval());
        publisher.publish(new ProcessEventPublisher.Event(change.id() + ":approved", EVENT_CHANGED, change.tenantId(),
                now, payload));
        return decided(change, "APPROVED", approver, now, comment);
    }

    private ChangeRow pending(String tenant, String changeId) {
        ChangeRow change = store.lockChange(tenant, requireId(changeId))
                .orElseThrow(() -> new CalendarNotFoundException("No calendar change " + safe(changeId)));
        if (!"PENDING".equals(change.status())) {
            throw new InvalidStateException("Calendar change " + changeId + " is " + change.status());
        }
        return change;
    }

    private static ChangeRow decided(ChangeRow c, String status, String by, Instant at, String comment) {
        return new ChangeRow(c.tenantId(), c.id(), c.calendarKey(), status, c.approval(), c.baseVersion(), c.previous(),
                c.proposed(), c.reason(), c.proposedBy(), c.proposedAt(), by, at, comment);
    }

    private static CalendarChange view(ChangeRow c) {
        return new CalendarChange(c.id(), c.calendarKey(), c.status(), c.approval(), c.baseVersion(), c.previous(),
                c.proposed(), c.reason(), c.proposedBy(), c.proposedAt(), c.decidedBy(), c.decidedAt(), c.comment());
    }

    private String tenant() {
        String tenant = tenants.currentTenantId();
        if (tenant == null || tenant.isBlank()) {
            throw new UnauthenticatedException("No tenant in the security context");
        }
        return tenant;
    }

    private String caller(String permission) {
        String caller = callers.currentCallerId();
        if (caller == null || caller.isBlank()) {
            throw new UnauthenticatedException("No caller in the security context");
        }
        if (caller.startsWith(SYSTEM_PREFIX)) {
            throw new PermissionDeniedException("Identities starting with " + SYSTEM_PREFIX + " are reserved for the "
                    + "engine");
        }
        if (!callers.currentPermissions().contains(permission)) {
            throw new PermissionDeniedException("Needs " + permission);
        }
        return caller;
    }

    private static void requireKey(String key) {
        if (key == null || key.length() > MAX_KEY_LENGTH || !KEY.matcher(key).matches()) {
            throw new InputInvalidException("A calendar key is lower-case words joined by hyphens, at most "
                    + MAX_KEY_LENGTH + " characters");
        }
    }

    private static String requireId(String id) {
        if (id == null || id.isEmpty() || id.length() > 26) {
            throw new CalendarNotFoundException("No calendar change " + safe(id));
        }
        return id;
    }

    private static String text(String value, String name, boolean required) {
        String text = value == null ? "" : value.strip();
        if (required && text.isEmpty()) {
            throw new InputInvalidException(name + " is required");
        }
        if (text.length() > MAX_TEXT) {
            throw new InputInvalidException(name + " is at most " + MAX_TEXT + " characters");
        }
        return text.isEmpty() ? null : text;
    }

    private static String safe(String value) {
        if (value == null) {
            return "(none)";
        }
        String bounded = value.length() > 40 ? value.substring(0, 40) + "..." : value;
        return bounded.replaceAll("\\p{Cntrl}", "?");
    }
}
