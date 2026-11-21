package io.github.dhluong90.bpm.process.internal.service;

import io.github.dhluong90.bpm.process.api.InstanceActivities;
import io.github.dhluong90.bpm.process.api.InstanceNotFoundException;
import io.github.dhluong90.bpm.process.api.PermissionDeniedException;
import io.github.dhluong90.bpm.process.api.QueryInvalidException;
import io.github.dhluong90.bpm.process.api.UnauthenticatedException;
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
                nodes.add(node(row, incidents.remove(row.nodeId())));
            }
            for (ProcessStore.IncidentRow incident : incidents.values()) {
                // An incident on a step with no history row in the window is still shown on its step.
                nodes.add(new InstanceActivities.Node(incident.nodeId(), InstanceActivities.State.INCIDENT, null, null,
                        null, 0, null, incident.errorCode(), incident.createdAt()));
            }
            List<InstanceActivities.Entry> entries = new ArrayList<>(
                    queries.historyEntries(tenant, instanceId, from, to, limit + 1));
            boolean hasMore = entries.size() > limit;
            if (hasMore) {
                entries = entries.subList(0, limit);
            }
            return new InstanceActivities(instanceId, instance.startedAt(), instance.endedAt(), nodes, entries, hasMore);
        });
    }

    private static InstanceActivities.Node node(ProcessQueryStore.NodeActivityRow row, ProcessStore.IncidentRow incident) {
        boolean left = row.lastCompletedAt() != null
                && (row.lastEnteredAt() == null || !row.lastCompletedAt().isBefore(row.lastEnteredAt()));
        InstanceActivities.State state = incident != null ? InstanceActivities.State.INCIDENT
                : left ? InstanceActivities.State.COMPLETED : InstanceActivities.State.ACTIVE;
        Duration duration = left && row.firstEnteredAt() != null
                ? Duration.between(row.firstEnteredAt(), row.lastCompletedAt()) : null;
        int attempts = (int) Math.max(row.entries(), row.attemptIds());
        return new InstanceActivities.Node(row.nodeId(), state, row.firstEnteredAt(), row.lastCompletedAt(), duration,
                attempts, row.lastActor(), incident == null ? null : incident.errorCode(),
                incident == null ? null : incident.createdAt());
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
