package io.github.dhluong90.bpm.process.internal.store;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Storage port of the hourly node statistics (PE-F-49). The write methods are called by the rollup inside one slice
 * transaction; the read methods take the tenant and filter by it first. Implementations must be thread-safe.
 */
public interface NodeStatsStore {

    /** The name of the rollup's row in the watermark table. */
    String JOB_NAME = "node-stats";

    /**
     * The counts and the duration histogram of one node in one hour.
     *
     * @param tenantId tenant
     * @param definitionId definition version id
     * @param bucketStart the hour, UTC
     * @param nodeId the flow node
     * @param entered tokens that entered the node
     * @param completed completions
     * @param failed failures
     * @param incidents incidents opened
     * @param slaBreaches SLA breaches
     * @param durationSumMs sum of completed durations
     * @param histogram 64 counts, see {@code DurationHistogram}
     */
    record NodeHour(String tenantId, String definitionId, Instant bucketStart, String nodeId, long entered,
            long completed, long failed, long incidents, long slaBreaches, long durationSumMs, long[] histogram) {
    }

    /** Creates the watermark row at {@code initial} when no replica has yet; never changes an existing one. */
    void ensureWatermark(String job, Instant initial, String replicaId, Instant now);

    /**
     * Locks the watermark row for the current transaction. A row another replica holds is skipped, not waited for.
     *
     * @return the watermark, or empty when another replica holds the row
     */
    Optional<Instant> lockWatermark(String job);

    /**
     * Reads {@code pre_history} from {@code from} up to {@code to} (exclusive) and aggregates it by tenant,
     * definition, hour and node. History is read by time range only and never grouped in SQL.
     */
    Collection<NodeHour> aggregate(Instant from, Instant to);

    /** Adds the counts and histograms to the stored hours; creates an hour that is absent. */
    void add(Collection<NodeHour> hours, Instant now);

    /** Moves the watermark to {@code to}, in the transaction that added the slice. */
    void advance(String job, Instant to, String replicaId, Instant now);

    /** @return the watermark, empty before the first run */
    Optional<Instant> watermark(String job);

    /**
     * Sums the hours of one definition version whose start is in {@code [from, to)}, per node, histograms merged in
     * SQL. Bounded by the tenant, the definition and a time range of at most {@code query.max-range}.
     */
    List<NodeHour> window(String tenant, String definitionId, Instant from, Instant to);
}
