package com.horus.bpm.rules.api;

import java.util.List;

/**
 * Decisions found by a bounded search, newest first.
 *
 * @param items the decisions
 * @param truncated true when more decisions match than the limit; narrow the period to see them
 */
public record DecisionSearchResult(List<LoggedDecision> items, boolean truncated) {

    public DecisionSearchResult {
        items = List.copyOf(items);
    }
}
