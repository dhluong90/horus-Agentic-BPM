package io.github.dhluong90.bpm.process.api;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Live tokens per node of one definition version, read from the live token index (PRE-028).
 *
 * @param definitionKey the process key
 * @param version the version number
 * @param asOf when the counts were read; a cached answer keeps the time of its read
 * @param nodes node id to token status ({@code ACTIVE}, {@code WAITING_USER}, {@code WAITING_ASYNC},
 *     {@code WAITING_TIMER}, {@code WAITING_EVENT}) to count; a node without live tokens is absent
 */
public record NodeCounts(String definitionKey, int version, Instant asOf, Map<String, Map<String, Long>> nodes) {

    public NodeCounts {
        Objects.requireNonNull(definitionKey, "definitionKey");
        Objects.requireNonNull(asOf, "asOf");
        TreeMap<String, Map<String, Long>> sorted = new TreeMap<>();
        nodes.forEach((node, byStatus) -> sorted.put(node, Map.copyOf(new TreeMap<>(byStatus))));
        nodes = java.util.Collections.unmodifiableMap(sorted);
    }

    /** @return the number of live tokens over every node */
    public long total() {
        return nodes.values().stream().flatMap(m -> m.values().stream()).mapToLong(Long::longValue).sum();
    }
}
