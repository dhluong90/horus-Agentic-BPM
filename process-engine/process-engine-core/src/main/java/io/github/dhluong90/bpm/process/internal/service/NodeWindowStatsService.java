package io.github.dhluong90.bpm.process.internal.service;

import io.github.dhluong90.bpm.process.api.DefinitionNotFoundException;
import io.github.dhluong90.bpm.process.api.NodeWindowStats;
import io.github.dhluong90.bpm.process.api.PermissionDeniedException;
import io.github.dhluong90.bpm.process.api.QueryInvalidException;
import io.github.dhluong90.bpm.process.api.UnauthenticatedException;
import io.github.dhluong90.bpm.process.internal.store.NodeStatsStore;
import io.github.dhluong90.bpm.process.internal.store.ProcessQueryStore;
import io.github.dhluong90.bpm.process.internal.store.TransactionRunner;
import io.github.dhluong90.bpm.process.spi.CallerResolver;
import io.github.dhluong90.bpm.process.spi.TenantResolver;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The window mode of the node statistics read (PE-F-49, PRE-029): the tenant and caller from the resolvers, the
 * permission check, one bounded read of the hourly rollup of the caller's tenant, and the percentiles from the merged
 * histograms. A version of another tenant is {@code DEFINITION_NOT_FOUND}, the same answer as a missing one. Reads
 * only {@code pre_node_stats_hourly}, never {@code pre_history}. Thread-safe.
 */
public final class NodeWindowStatsService {

    static final String PERMISSION_READ = "processes:read";

    /** Window when the caller gives none. */
    static final Duration DEFAULT_WINDOW = Duration.ofHours(24);

    private final TenantResolver tenants;
    private final CallerResolver callers;
    private final Clock clock;
    private final TransactionRunner transactions;
    private final ProcessQueryStore queries;
    private final NodeStatsStore stats;
    private final Duration maxRange;

    /**
     * @param tenants tenant resolver
     * @param callers caller resolver
     * @param clock engine clock
     * @param transactions transaction runner on the host's transaction manager
     * @param queries definition lookup
     * @param stats statistics storage
     * @param maxRange {@code query.max-range}
     */
    public NodeWindowStatsService(TenantResolver tenants, CallerResolver callers, Clock clock,
            TransactionRunner transactions, ProcessQueryStore queries, NodeStatsStore stats, Duration maxRange) {
        this.tenants = Objects.requireNonNull(tenants);
        this.callers = Objects.requireNonNull(callers);
        this.clock = Objects.requireNonNull(clock);
        this.transactions = Objects.requireNonNull(transactions);
        this.queries = Objects.requireNonNull(queries);
        this.stats = Objects.requireNonNull(stats);
        this.maxRange = Objects.requireNonNull(maxRange);
    }

    /** {@link io.github.dhluong90.bpm.process.api.ProcessQueryService#nodeWindowStats}. */
    public NodeWindowStats nodeWindowStats(String definitionKey, int version, Instant from, Instant to) {
        String tenant = tenants.currentTenantId();
        if (tenant == null || tenant.isBlank()) {
            throw new UnauthenticatedException("No tenant in the security context");
        }
        String caller = callers.currentCallerId();
        if (caller == null || caller.isBlank()) {
            throw new UnauthenticatedException("No caller in the security context");
        }
        if (!callers.currentPermissions().contains(PERMISSION_READ)) {
            throw new PermissionDeniedException("Needs " + PERMISSION_READ);
        }
        Instant now = clock.instant();
        Instant end = to == null ? now : to;
        Instant start = from == null ? end.minus(DEFAULT_WINDOW) : from;
        if (!start.isBefore(end)) {
            throw new QueryInvalidException("from must be before to");
        }
        Instant bucketFrom = start.truncatedTo(ChronoUnit.HOURS);
        Instant bucketTo = end.truncatedTo(ChronoUnit.HOURS).equals(end) ? end : end.truncatedTo(ChronoUnit.HOURS)
                .plus(1, ChronoUnit.HOURS);
        if (Duration.between(bucketFrom, bucketTo).compareTo(maxRange) > 0) {
            throw new QueryInvalidException("The range is longer than " + maxRange + " (query.max-range, widened to "
                    + "whole hours)");
        }
        return transactions.inTransaction(() -> {
            String definitionId = queries.definitionId(tenant, definitionKey, version).orElseThrow(
                    () -> new DefinitionNotFoundException("No definition " + definitionKey + " version " + version));
            List<NodeStatsStore.NodeHour> rows = stats.window(tenant, definitionId, bucketFrom, bucketTo);
            Instant watermark = stats.watermark(NodeStatsStore.JOB_NAME).orElse(null);
            Map<String, NodeWindowStats.NodeWindow> nodes = new LinkedHashMap<>();
            for (NodeStatsStore.NodeHour row : rows) {
                Long mean = row.completed() == 0 ? null : Math.round((double) row.durationSumMs() / row.completed());
                nodes.put(row.nodeId(), new NodeWindowStats.NodeWindow(row.entered(), row.completed(), row.failed(),
                        row.incidents(), row.slaBreaches(), mean, DurationHistogram.quantileMs(row.histogram(), 0.50),
                        DurationHistogram.quantileMs(row.histogram(), 0.95)));
            }
            Duration lag = watermark == null ? null : Duration.between(watermark, now).isNegative() ? Duration.ZERO
                    : Duration.between(watermark, now);
            return new NodeWindowStats(definitionKey, version, bucketFrom, bucketTo, watermark, lag, nodes);
        });
    }
}
