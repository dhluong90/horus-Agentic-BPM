package io.github.dhluong90.bpm.process.internal.service;

import io.github.dhluong90.bpm.process.api.Page;
import io.github.dhluong90.bpm.process.api.PageRequest;
import io.github.dhluong90.bpm.process.api.PermissionDeniedException;
import io.github.dhluong90.bpm.process.api.QueryInvalidException;
import io.github.dhluong90.bpm.process.api.RoleFanOutExceededException;
import io.github.dhluong90.bpm.process.api.TaskQuery;
import io.github.dhluong90.bpm.process.api.TaskSummary;
import io.github.dhluong90.bpm.process.api.UnauthenticatedException;
import io.github.dhluong90.bpm.process.internal.store.ProcessQueryStore;
import io.github.dhluong90.bpm.process.internal.store.ProcessQueryStore.TaskRange;
import io.github.dhluong90.bpm.process.internal.store.ProcessQueryStore.TaskSummaryRow;
import io.github.dhluong90.bpm.process.internal.store.TransactionRunner;
import io.github.dhluong90.bpm.process.spi.CallerResolver;
import io.github.dhluong90.bpm.process.spi.TenantResolver;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * The task list (PRE-030, DESIGN section 8.2): the caller's tasks by assignment, status, role, group, due date and
 * missed deadline. A caller's tasks lie in several ranges (their user, their claims, each of their roles and groups),
 * each served by its own partial index ordered by {@code (due_at, id)}. Each range is read for one page after the
 * cursor, and the pages are merged here, so no range reads more than {@code limit + 1} rows however long its queue.
 * Tasks without a due time follow the dated ones, by id. Tenant-scoped through the resolver; never a request value.
 * Thread-safe.
 */
final class TaskQueries {

    static final String PERMISSION_WORK = "processes:task:work";
    static final String PERMISSION_MANAGE = "processes:task:manage";
    static final Set<String> ASSIGNMENTS = Set.of("mine", "candidate", "any");
    static final Set<String> STATUSES = Set.of("OPEN", "CLAIMED");
    private static final String SYSTEM_PREFIX = "system:";
    private static final String LIST = "tasks";
    private static final Pattern NAME = Pattern.compile("^[A-Za-z0-9_.@-]{1,100}$");
    /** The first key of the dated part: before any due time an engine clock can produce. */
    private static final Instant BEFORE_ANY = Instant.EPOCH;
    /** The upper bound of the dated part when the query sets none. */
    private static final Instant AFTER_ANY = Instant.parse("9999-12-31T00:00:00Z");

    /** Global order: dated tasks by (dueAt, id), then undated tasks by id. */
    private static final Comparator<TaskSummaryRow> ORDER = Comparator
            .comparing((TaskSummaryRow r) -> r.dueAt() == null)
            .thenComparing(r -> r.dueAt() == null ? Instant.MIN : r.dueAt())
            .thenComparing(TaskSummaryRow::id);

    private final TenantResolver tenants;
    private final CallerResolver callers;
    private final Clock clock;
    private final TransactionRunner transactions;
    private final ProcessQueryStore store;
    private final Supplier<Cursors> cursors;
    private final int maxPageSize;
    private final int maxRoles;

    TaskQueries(TenantResolver tenants, CallerResolver callers, Clock clock, TransactionRunner transactions,
            ProcessQueryStore store, Supplier<Cursors> cursors, int maxPageSize, int maxRoles) {
        this.tenants = Objects.requireNonNull(tenants);
        this.callers = Objects.requireNonNull(callers);
        this.clock = Objects.requireNonNull(clock);
        this.transactions = Objects.requireNonNull(transactions);
        this.store = Objects.requireNonNull(store);
        this.cursors = Objects.requireNonNull(cursors);
        this.maxPageSize = maxPageSize;
        this.maxRoles = maxRoles;
    }

    /** One range to read: which index and its key. */
    private record Range(TaskRange range, String key) {
    }

    /** Where the previous page ended: in the dated part at (dueAt, id), or in the undated part at id. */
    private record After(Instant dueAt, String id) {

        boolean dated() {
            return dueAt != null;
        }

        String encode() {
            return (dueAt == null ? "U" : "D" + dueAt) + "|" + id;
        }

        static After first() {
            return new After(BEFORE_ANY, "");
        }

        static After of(TaskSummaryRow row) {
            return new After(row.dueAt(), row.id());
        }

        static After decode(String text) {
            int bar = text.indexOf('|');
            if (bar < 1) {
                throw Cursors.invalid();
            }
            try {
                String part = text.substring(0, bar);
                String id = text.substring(bar + 1);
                return part.equals("U") ? new After(null, id)
                        : part.charAt(0) == 'D' ? new After(Instant.parse(part.substring(1)), id) : fail();
            } catch (RuntimeException e) {
                throw Cursors.invalid();
            }
        }

        private static After fail() {
            throw Cursors.invalid();
        }
    }

    Page<TaskSummary> tasks(TaskQuery query) {
        String tenant = tenant();
        String caller = caller();
        TaskQuery q = query == null ? TaskQuery.inbox() : query;
        int limit = limit(q.page());
        Set<String> assignment = q.assignment().isEmpty() ? Set.of("mine", "candidate") : q.assignment();
        requireAll(assignment, ASSIGNMENTS, "assignment");
        if (assignment.contains("any") && !callers.currentPermissions().contains(PERMISSION_MANAGE)) {
            throw new PermissionDeniedException("assignment=any needs " + PERMISSION_MANAGE);
        }
        Set<String> statuses = q.statuses().isEmpty() ? STATUSES : q.statuses();
        requireAll(statuses, STATUSES, "status");
        requireName(q.role(), "role");
        requireName(q.group(), "group");
        boolean narrowed = q.role() != null || q.group() != null;
        if (narrowed && assignment.equals(Set.of("mine"))) {
            throw new QueryInvalidException("role and group narrow candidate and any; they do not apply to mine");
        }
        Instant upper = q.dueBefore();
        if (q.slaBreached()) {
            Instant now = clock.instant();
            upper = upper == null || now.isBefore(upper) ? now : upper;
        }
        List<Range> ranges = ranges(caller, assignment, statuses, q.role(), q.group());
        // The cursor is bound to the tenant and these filters, so it cannot be replayed on another list.
        String scope = String.join(",", new TreeSet<>(assignment)) + "|" + String.join(",", new TreeSet<>(statuses))
                + "|" + Objects.toString(q.role(), "") + "|" + Objects.toString(q.group(), "") + "|"
                + Objects.toString(q.dueBefore(), "") + "|" + q.slaBreached();
        String token = cursors.get().verify(tenant, scope, LIST, q.page().cursor());
        After after = token == null ? After.first() : After.decode(token);
        List<String> wanted = List.copyOf(new TreeSet<>(statuses));
        Instant dueBefore = upper;
        List<TaskSummaryRow> rows = transactions.inTransaction(() -> {
            Map<String, TaskSummaryRow> merged = new LinkedHashMap<>();
            for (Range r : ranges) {
                for (TaskSummaryRow row : read(tenant, r, wanted, after, dueBefore, limit + 1)) {
                    merged.putIfAbsent(row.id(), row);
                }
            }
            return merged.values().stream().sorted(ORDER).limit(limit + 1L).toList();
        });
        List<TaskSummary> items = rows.stream().limit(limit).map(TaskQueries::summary).toList();
        if (rows.size() <= limit) {
            return new Page<>(items, null);
        }
        return new Page<>(items, cursors.get().sign(tenant, scope, LIST, After.of(rows.get(limit - 1)).encode()));
    }

    /** One page of one range after the cursor: the rest of the dated part, then, with no due bound, undated tasks. */
    private List<TaskSummaryRow> read(String tenant, Range r, List<String> statuses, After after, Instant dueBefore,
            int limit) {
        List<TaskSummaryRow> out = new ArrayList<>();
        if (after.dated()) {
            out.addAll(store.datedTasks(tenant, r.range(), r.key(), statuses, after.dueAt(), after.id(),
                    dueBefore == null ? AFTER_ANY : dueBefore, limit));
        }
        if (dueBefore == null && out.size() < limit) {
            out.addAll(store.undatedTasks(tenant, r.range(), r.key(), statuses, after.dated() ? "" : after.id(),
                    limit - out.size()));
        }
        return out;
    }

    /**
     * The ranges of a query. {@code any} reads the tenant (or the given role and group); otherwise {@code mine} reads
     * the caller's declared tasks and claims, and {@code candidate} the open tasks of each of the caller's roles and
     * groups (only the given one when it is among them). Open-only ranges are skipped when {@code OPEN} is not wanted.
     */
    private List<Range> ranges(String caller, Set<String> assignment, Set<String> statuses, String role,
            String group) {
        List<Range> ranges = new ArrayList<>();
        boolean open = statuses.contains("OPEN");
        boolean narrowed = role != null || group != null;
        if (assignment.contains("any")) {
            if (!narrowed) {
                ranges.add(new Range(TaskRange.ANY, null));
            }
            if (role != null && open) {
                ranges.add(new Range(TaskRange.ROLE, role));
            }
            if (group != null && open) {
                ranges.add(new Range(TaskRange.GROUP, group));
            }
            return ranges;
        }
        if (assignment.contains("mine") && !narrowed) {
            ranges.add(new Range(TaskRange.MINE, caller));
            if (statuses.contains("CLAIMED")) {
                ranges.add(new Range(TaskRange.CLAIMED, caller));
            }
        }
        if (assignment.contains("candidate") && open) {
            // #408: each role and group of the caller is a queue read, so a very long list is refused, not served.
            int queues = new TreeSet<>(callers.currentRoles()).size() + new TreeSet<>(callers.currentGroups()).size();
            if (role == null && group == null && queues > maxRoles) {
                throw new RoleFanOutExceededException(queues, maxRoles);
            }
            for (String r : new TreeSet<>(callers.currentRoles())) {
                if (NAME.matcher(r).matches() && (role == null ? group == null : role.equals(r))) {
                    ranges.add(new Range(TaskRange.ROLE, r));
                }
            }
            for (String g : new TreeSet<>(callers.currentGroups())) {
                if (NAME.matcher(g).matches() && (group == null ? role == null : group.equals(g))) {
                    ranges.add(new Range(TaskRange.GROUP, g));
                }
            }
        }
        return ranges;
    }

    private static TaskSummary summary(TaskSummaryRow r) {
        return new TaskSummary(r.id(), r.instanceId(), r.definitionKey(), r.definitionVersion(), r.businessKey(),
                r.nodeId(), r.name(), r.assignee(), r.status(), r.lockVersion(), r.claimedBy(), r.createdAt(),
                r.dueAt());
    }

    private int limit(PageRequest page) {
        Integer limit = page.limit();
        if (limit == null) {
            return Math.min(io.github.dhluong90.bpm.process.internal.service.EngineDefaults.DEFAULT_PAGE_SIZE,
                    maxPageSize);
        }
        if (limit < 1 || limit > maxPageSize) {
            throw new QueryInvalidException("limit must be 1 to " + maxPageSize + " (query.max-page-size)");
        }
        return limit;
    }

    private static void requireAll(Set<String> values, Set<String> allowed, String name) {
        for (String v : values) {
            if (!allowed.contains(v)) {
                throw new QueryInvalidException(name + " must be one or more of " + String.join(", ",
                        new TreeSet<>(allowed)));
            }
        }
    }

    private static void requireName(String value, String name) {
        if (value != null && !NAME.matcher(value).matches()) {
            throw new QueryInvalidException(name + " is 1 to 100 letters, digits or _ . @ -");
        }
    }

    private String tenant() {
        String tenant = tenants.currentTenantId();
        if (tenant == null || tenant.isBlank()) {
            throw new UnauthenticatedException("No tenant in the security context");
        }
        return tenant;
    }

    private String caller() {
        String caller = callers.currentCallerId();
        if (caller == null || caller.isBlank()) {
            throw new UnauthenticatedException("No caller in the security context");
        }
        if (caller.startsWith(SYSTEM_PREFIX)) {
            throw new PermissionDeniedException("Identities starting with " + SYSTEM_PREFIX + " are reserved for the "
                    + "engine");
        }
        if (!callers.currentPermissions().contains(PERMISSION_WORK)) {
            throw new PermissionDeniedException("Needs " + PERMISSION_WORK);
        }
        return caller;
    }
}
