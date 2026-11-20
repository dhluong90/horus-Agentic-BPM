package io.github.dhluong90.bpm.process.api;

import java.time.Instant;
import java.util.Set;

/**
 * Filters of the instance list of the caller's tenant.
 *
 * @param statuses statuses to list; empty means {@code RUNNING}
 * @param definitionKey only this key, or null for every key
 * @param startedFrom inclusive lower bound of the start time, or null
 * @param startedTo exclusive upper bound of the start time, or null; with one bound the other is
 *     {@code query.max-range} away, and a range longer than that is {@code QUERY_INVALID}
 * @param page which page
 */
public record InstanceQuery(Set<String> statuses, String definitionKey, Instant startedFrom, Instant startedTo,
        PageRequest page) {

    public InstanceQuery {
        statuses = statuses == null ? Set.of() : Set.copyOf(statuses);
        page = page == null ? PageRequest.first() : page;
    }

    /** @return the running instances of every key, first page */
    public static InstanceQuery running() {
        return new InstanceQuery(Set.of(), null, null, null, PageRequest.first());
    }
}
