package io.github.dhluong90.bpm.process.api;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Counts and typical durations per node of one definition version over a window of whole hours, read from the hourly
 * rollup (PE-F-49, PRE-029) and never from history.
 *
 * @param definitionKey the process key
 * @param version the version number
 * @param from start of the first hour covered
 * @param to end of the last hour covered
 * @param asOf the rollup watermark: history before it is counted, history after it is not yet; null before the first
 *     run
 * @param lag how far the watermark is behind now; null before the first run
 * @param nodes node id to its figures; a node with no activity in the window is absent
 */
public record NodeWindowStats(String definitionKey, int version, Instant from, Instant to, Instant asOf,
        Duration lag, Map<String, NodeWindow> nodes) {

    /**
     * The figures of one node.
     *
     * @param entered tokens that entered
     * @param completed completions
     * @param failed failures
     * @param incidents incidents opened
     * @param slaBreaches SLA breaches
     * @param meanMs mean completed duration, null without a completion
     * @param p50Ms median completed duration within 19 percent, null without a completion
     * @param p95Ms 95th percentile completed duration within 19 percent, null without a completion
     */
    public record NodeWindow(long entered, long completed, long failed, long incidents, long slaBreaches, Long meanMs,
            Long p50Ms, Long p95Ms) {
    }

    public NodeWindowStats {
        Objects.requireNonNull(definitionKey, "definitionKey");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        nodes = Collections.unmodifiableMap(new TreeMap<>(nodes));
    }
}
