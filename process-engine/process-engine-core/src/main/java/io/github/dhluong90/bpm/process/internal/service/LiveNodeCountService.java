package io.github.dhluong90.bpm.process.internal.service;

import io.github.dhluong90.bpm.process.api.DefinitionNotFoundException;
import io.github.dhluong90.bpm.process.api.NodeCounts;
import io.github.dhluong90.bpm.process.api.PermissionDeniedException;
import io.github.dhluong90.bpm.process.api.ProcessQueryService;
import io.github.dhluong90.bpm.process.api.UnauthenticatedException;
import io.github.dhluong90.bpm.process.internal.store.ProcessQueryStore;
import io.github.dhluong90.bpm.process.internal.store.TransactionRunner;
import io.github.dhluong90.bpm.process.spi.CallerResolver;
import io.github.dhluong90.bpm.process.spi.TenantResolver;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Live counts per node (PRE-028): the tenant and caller from the resolvers, the permission check, one read of the
 * live token index, and a per-replica cache keyed by tenant and definition id, so one tenant's answer can never be
 * served to another. Thread-safe; two concurrent misses may both read, which is harmless for a read.
 */
public final class LiveNodeCountService implements ProcessQueryService {

    static final String PERMISSION_READ = "processes:read";

    private record Key(String tenant, String definitionId) {
    }

    private record Entry(NodeCounts counts, Instant expiresAt) {
    }

    private final TenantResolver tenants;
    private final CallerResolver callers;
    private final Clock clock;
    private final TransactionRunner transactions;
    private final ProcessQueryStore store;
    private final LiveStatsSettings settings;
    private final Map<Key, Entry> cache = new ConcurrentHashMap<>();

    /**
     * @param tenants tenant resolver
     * @param callers caller resolver
     * @param clock engine clock
     * @param transactions transaction runner on the host's transaction manager
     * @param store read-only storage
     * @param settings cache settings
     */
    public LiveNodeCountService(TenantResolver tenants, CallerResolver callers, Clock clock,
            TransactionRunner transactions, ProcessQueryStore store, LiveStatsSettings settings) {
        this.tenants = Objects.requireNonNull(tenants);
        this.callers = Objects.requireNonNull(callers);
        this.clock = Objects.requireNonNull(clock);
        this.transactions = Objects.requireNonNull(transactions);
        this.store = Objects.requireNonNull(store);
        this.settings = Objects.requireNonNull(settings);
    }

    @Override
    public NodeCounts liveNodeCounts(String definitionKey, int version) {
        String tenant = tenant();
        requirePermission(PERMISSION_READ);
        return transactions.inTransaction(() -> {
            String definitionId = store.definitionId(tenant, definitionKey, version).orElseThrow(
                    () -> new DefinitionNotFoundException("No definition " + definitionKey + " version " + version));
            Key key = new Key(tenant, definitionId);
            Instant now = clock.instant();
            Entry cached = cache.get(key);
            if (cached != null && now.isBefore(cached.expiresAt())) {
                return cached.counts();
            }
            Map<String, Map<String, Long>> nodes = new HashMap<>();
            for (ProcessQueryStore.LiveCount row : store.liveTokenCounts(tenant, definitionId)) {
                nodes.computeIfAbsent(row.nodeId(), n -> new HashMap<>()).merge(row.status(), row.count(), Long::sum);
            }
            NodeCounts counts = new NodeCounts(definitionKey, version, now, nodes);
            Duration ttl = counts.total() > settings.liveCacheLargeThreshold()
                    ? settings.liveCacheTtlLarge() : settings.liveCacheTtl();
            cache.values().removeIf(entry -> !now.isBefore(entry.expiresAt()));
            cache.put(key, new Entry(counts, now.plus(ttl)));
            return counts;
        });
    }

    private String tenant() {
        String tenant = tenants.currentTenantId();
        if (tenant == null || tenant.isBlank()) {
            throw new UnauthenticatedException("No tenant in the security context");
        }
        return tenant;
    }

    private void requirePermission(String permission) {
        String caller = callers.currentCallerId();
        if (caller == null || caller.isBlank()) {
            throw new UnauthenticatedException("No caller in the security context");
        }
        if (!callers.currentPermissions().contains(permission)) {
            throw new PermissionDeniedException("Needs " + permission);
        }
    }
}
