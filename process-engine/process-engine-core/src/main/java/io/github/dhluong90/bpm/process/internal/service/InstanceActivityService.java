package io.github.dhluong90.bpm.process.internal.service;

import io.github.dhluong90.bpm.process.api.CancelCause;
import io.github.dhluong90.bpm.process.api.InstanceActivities;
import io.github.dhluong90.bpm.process.api.InstanceNotFoundException;
import io.github.dhluong90.bpm.process.api.PermissionDeniedException;
import io.github.dhluong90.bpm.process.api.QueryInvalidException;
import io.github.dhluong90.bpm.process.api.UnauthenticatedException;
import io.github.dhluong90.bpm.process.internal.exec.InstanceStatus;
import io.github.dhluong90.bpm.process.internal.store.ProcessQueryStore;
import io.github.dhluong90.bpm.process.internal.store.ProcessStore;
import io.github.dhluong90.bpm.process.internal.store.TransactionRunner;
import io.github.dhluong90.bpm.process.spi.CallerResolver;
import io.github.dhluong90.bpm.process.spi.TenantResolver;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The activities of one instance (PRE-027): the tenant and caller from the resolvers, the permission check, then two
 * history reads bounded by the instance's lifetime, so PostgreSQL prunes every partition the instance never lived in,
 * and the open incidents. Served as {@code ProcessQueryService.activities} through {@link ProcessQueries}. Reads
 * only; thread-safe.
 */
public final class InstanceActivityService {

    private static final System.Logger LOG = System.getLogger(InstanceActivityService.class.getName());

    static final String PERMISSION_READ = "processes:read";

    /** Upper bound of a running instance's history: every partition from its start on may hold its entries. */
    static final Instant OPEN_END = Instant.parse("9999-12-31T23:59:59Z");

    private final TenantResolver tenants;
    private final CallerResolver callers;
    private final TransactionRunner transactions;
    private final ProcessStore store;
    private final ProcessQueryStore queries;
    private final int maxPageSize;

    /**
     * @param tenants tenant resolver
     * @param callers caller resolver
     * @param transactions transaction runner on the host's transaction manager
     * @param store the instance and incident rows
     * @param queries the history reads
     * @param maxPageSize {@code query.max-page-size}
     */
    public InstanceActivityService(TenantResolver tenants, CallerResolver callers, TransactionRunner transactions,
            ProcessStore store, ProcessQueryStore queries, int maxPageSize) {
        this.tenants = Objects.requireNonNull(tenants);
        this.callers = Objects.requireNonNull(callers);
        this.transactions = Objects.requireNonNull(transactions);
        this.store = Objects.requireNonNull(store);
        this.queries = Objects.requireNonNull(queries);
        if (maxPageSize < 1) {
            throw new IllegalArgumentException("maxPageSize must be at least 1");
        }
        this.maxPageSize = maxPageSize;
    }

    /** {@link io.github.dhluong90.bpm.process.api.ProcessQueryService#activities(String, int)}. */
    public InstanceActivities activities(String instanceId, int limit) {
        String tenant = tenant();
        requirePermission();
        if (limit < 1 || limit > maxPageSize) {
            throw new QueryInvalidException("limit must be 1 to " + maxPageSize + " (query.max-page-size)");
        }
        return transactions.inTransaction(() -> {
            ProcessStore.InstanceRow instance = store.findInstance(tenant, instanceId)
                    .orElseThrow(() -> new InstanceNotFoundException("No instance " + instanceId));
            Instant from = instance.startedAt();
            Instant to = instance.endedAt() == null ? OPEN_END : instance.endedAt();
            Map<String, ProcessStore.IncidentRow> incidents = new HashMap<>();
            for (ProcessStore.IncidentRow incident : store.openIncidents(tenant, instanceId)) {
                incidents.putIfAbsent(incident.nodeId(), incident);
            }
            List<ProcessQueryStore.NodeActivityRow> rows = new ArrayList<>(
                    queries.nodeActivities(tenant, instanceId, from, to));
            rows.sort(Comparator.comparing(ProcessQueryStore.NodeActivityRow::firstEnteredAt,
                    Comparator.nullsLast(Comparator.naturalOrder())).thenComparing(ProcessQueryStore.NodeActivityRow::nodeId));
            List<InstanceActivities.Node> nodes = new ArrayList<>();
            for (ProcessQueryStore.NodeActivityRow row : rows) {
                nodes.add(node(row, incidents.remove(row.nodeId()), instance));
            }
            for (ProcessStore.IncidentRow incident : incidents.values()) {
                // An incident on a step with no history row in the window is still shown on its step.
                nodes.add(new InstanceActivities.Node(incident.nodeId(), InstanceActivities.State.INCIDENT, null, null,
                        null, 0, null, incident.errorCode(), incident.createdAt(), null));
            }
            List<InstanceActivities.Entry> entries = new ArrayList<>(
                    queries.historyEntries(tenant, instanceId, from, to, limit + 1));
            boolean hasMore = entries.size() > limit;
            if (hasMore) {
                entries = entries.subList(0, limit);
            }
            List<InstanceActivities.Entry> undo = queries.compensationEntries(tenant, instanceId, from, to,
                    COMPENSATION_EVENT_LIMIT);
            Map<String, String> scopes = new HashMap<>();
            for (InstanceActivities.Entry e : undo) {
                if (e.scope() != null && e.scopeTokenId() != null) {
                    scopes.putIfAbsent(e.scopeTokenId(), e.scope());
                }
            }
            // An entry of the undo names its scope, so a client never pairs it with the failure by guessing.
            List<InstanceActivities.Entry> timeline = new ArrayList<>();
            for (InstanceActivities.Entry e : entries) {
                timeline.add(e.scopeTokenId() != null && e.scope() == null ? e.inScope(scopes.get(e.scopeTokenId())) : e);
            }
            return new InstanceActivities(instanceId, instance.startedAt(), instance.endedAt(), nodes, timeline,
                    hasMore, compensations(undo, scopes, store.openIncidents(tenant, instanceId)));
        });
    }

    /** Most undo history rows one instance's scopes are read from; a saga has at most 500 steps. */
    static final int COMPENSATION_EVENT_LIMIT = 2_000;

    /**
     * Where the undo of each scope stands (#80, PRE-203), from its history: a scope that ended is {@code UNDONE}; one
     * whose last event on some step is a failure or a call for a person is {@code NOT_FULLY_UNDONE}, naming that step
     * and its open incident; otherwise the undo is {@code IN_PROGRESS}. Order of the scopes is the order they started.
     */
    static List<InstanceActivities.Compensation> compensations(List<InstanceActivities.Entry> undo,
            Map<String, String> scopes, List<ProcessStore.IncidentRow> openIncidents) {
        Map<String, List<InstanceActivities.Entry>> byScope = new java.util.LinkedHashMap<>();
        for (InstanceActivities.Entry e : undo) {
            if (e.scopeTokenId() != null) {
                byScope.computeIfAbsent(e.scopeTokenId(), k -> new ArrayList<>()).add(e);
            }
        }
        List<InstanceActivities.Compensation> out = new ArrayList<>();
        for (Map.Entry<String, List<InstanceActivities.Entry>> scope : byScope.entrySet()) {
            Instant started = null;
            Instant ended = null;
            String cause = null;
            String failing = null;
            int undone = 0;
            Map<String, String> lastByStep = new java.util.LinkedHashMap<>();
            for (InstanceActivities.Entry e : scope.getValue()) {
                switch (e.type()) {
                    case "COMPENSATION_STARTED" -> {
                        started = started == null ? e.occurredAt() : started;
                        cause = e.cause() == null || "-".equals(e.cause()) ? null : e.cause();
                        failing = e.nodeId();
                    }
                    case "COMPENSATED" -> ended = e.occurredAt();
                    case "COMPENSATION_STEP_COMPLETED" -> {
                        undone++;
                        lastByStep.put(e.compensates(), e.type());
                    }
                    case "COMPENSATION_STEP_SCHEDULED", "COMPENSATION_STEP_FAILED", "COMPENSATION_STEP_MANUAL" ->
                            lastByStep.put(e.compensates(), e.type());
                    default -> { }
                }
            }
            if (started == null) {
                started = scope.getValue().get(0).occurredAt();
            }
            String blocked = lastByStep.entrySet().stream().filter(x -> "COMPENSATION_STEP_FAILED".equals(x.getValue())
                    || "COMPENSATION_STEP_MANUAL".equals(x.getValue())).map(Map.Entry::getKey).findFirst().orElse(null);
            InstanceActivities.CompensationState state = ended != null ? InstanceActivities.CompensationState.UNDONE
                    : blocked != null ? InstanceActivities.CompensationState.NOT_FULLY_UNDONE
                    : InstanceActivities.CompensationState.IN_PROGRESS;
            ProcessStore.IncidentRow incident = state == InstanceActivities.CompensationState.NOT_FULLY_UNDONE
                    ? openIncidents.stream().filter(i -> blocked.equals(i.nodeId())).findFirst().orElse(null) : null;
            out.add(new InstanceActivities.Compensation(scope.getKey(), scopes.get(scope.getKey()), state, started,
                    ended, cause, failing, undone, state == InstanceActivities.CompensationState.NOT_FULLY_UNDONE
                            ? blocked : null, incident == null ? null : incident.id(),
                    incident == null ? null : incident.errorCode()));
        }
        return out;
    }

    /** An instance that ended any way other than completing (cancelled or failed). */
    private static boolean abnormalEnd(ProcessStore.InstanceRow instance) {
        return !InstanceStatus.COMPLETED.name().equals(instance.status());
    }

    /**
     * One step as the timeline shows it. A step with an open incident is {@code INCIDENT}. Otherwise the last closing
     * event decides: {@code NODE_COMPLETED} is {@code COMPLETED}; {@code TOKEN_CANCELLED} is {@code CANCELLED}, or
     * {@code TERMINATED} when its {@code cause} is a terminate end, an ad-hoc cancel, an instance cancel or an undo. A step
     * entered and not closed is {@code ACTIVE} while the instance runs; once the instance has ended it is
     * {@code TERMINATED} when the instance ended terminated or cancelled, and {@code COMPLETED} when it ended normally
     * (a history gap, logged). A compensated step keeps its state; the undo is reported in the compensation block.
     */
    private static InstanceActivities.Node node(ProcessQueryStore.NodeActivityRow row, ProcessStore.IncidentRow incident,
            ProcessStore.InstanceRow instance) {
        boolean closed = row.lastClosedAt() != null
                && (row.lastEnteredAt() == null || !row.lastClosedAt().isBefore(row.lastEnteredAt()));
        Instant endedAt = instance.endedAt();
        Instant leftAt = closed ? row.lastClosedAt() : null;
        InstanceActivities.State state;
        String cause = null;
        if (incident != null) {
            state = InstanceActivities.State.INCIDENT;
        } else if (closed) {
            if ("TOKEN_CANCELLED".equals(row.lastClosedType())) {
                // The cause decides: an event that interrupted the step is CANCELLED, a terminate, a cancel or an
                // undo is TERMINATED. An entry written before the cause existed reads as CANCELLED, with no reason.
                cause = row.lastClosedCause();
                state = CancelCause.of(cause).filter(CancelCause::terminating).isPresent()
                        ? InstanceActivities.State.TERMINATED : InstanceActivities.State.CANCELLED;
            } else {
                state = InstanceActivities.State.COMPLETED;
            }
        } else if (endedAt == null) {
            state = InstanceActivities.State.ACTIVE;
        } else {
            leftAt = endedAt;
            if (abnormalEnd(instance)) {
                state = InstanceActivities.State.TERMINATED;
            } else {
                state = InstanceActivities.State.COMPLETED;
                LOG.log(System.Logger.Level.WARNING, "process.history.gap instance={0} node={1}: the instance ended "
                        + "normally but the step has no closing event", instance.id(), row.nodeId());
            }
        }
        boolean left = leftAt != null && state != InstanceActivities.State.INCIDENT;
        Instant completedAt = left ? leftAt : null;
        Duration duration = left && row.firstEnteredAt() != null
                ? Duration.between(row.firstEnteredAt(), completedAt) : null;
        int attempts = (int) Math.max(row.entries(), row.attemptIds());
        return new InstanceActivities.Node(row.nodeId(), state, row.firstEnteredAt(), completedAt, duration,
                attempts, row.lastActor(), incident == null ? null : incident.errorCode(),
                incident == null ? null : incident.createdAt(), cause);
    }

    private String tenant() {
        String tenant = tenants.currentTenantId();
        if (tenant == null || tenant.isBlank()) {
            throw new UnauthenticatedException("No tenant in the security context");
        }
        return tenant;
    }

    private void requirePermission() {
        String caller = callers.currentCallerId();
        if (caller == null || caller.isBlank()) {
            throw new UnauthenticatedException("No caller in the security context");
        }
        if (!callers.currentPermissions().contains(PERMISSION_READ)) {
            throw new PermissionDeniedException("Needs " + PERMISSION_READ);
        }
    }
}
