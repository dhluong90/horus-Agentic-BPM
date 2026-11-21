package io.github.dhluong90.bpm.rules.api;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Many evaluations of one rule set in one call (RLE-067). One version is selected for the whole batch, at one clock.
 * The tenant is never part of the request (TR-SEC-03).
 *
 * @param ruleSet the rule set name
 * @param items 1 to {@code evaluation.max-batch} items, evaluated in order
 * @param idempotencyKey optional key; a replay returns the logged results and writes no row
 * @param clock optional instant the decisions reason about; needs {@code rules:simulate}. Null means the engine clock
 */
public record BatchRequest(String ruleSet, List<Item> items, String idempotencyKey, Instant clock) {

    public BatchRequest {
        Objects.requireNonNull(ruleSet, "ruleSet must not be null");
        Objects.requireNonNull(items, "items must not be null");
        items = java.util.Collections.unmodifiableList(new java.util.ArrayList<>(items));
    }

    /**
     * Creates a batch at the engine clock without an idempotency key.
     *
     * @param ruleSet the rule set name
     * @param items the items
     * @return the request
     */
    public static BatchRequest of(String ruleSet, List<Item> items) {
        return new BatchRequest(ruleSet, items, null, null);
    }

    /**
     * Returns a copy with an idempotency key.
     *
     * @param key the key, at most 200 characters
     * @return the new request
     */
    public BatchRequest withIdempotencyKey(String key) {
        return new BatchRequest(ruleSet, items, key, clock);
    }

    /**
     * Returns a copy that reasons about another instant.
     *
     * @param at the instant, or null for the engine clock
     * @return the new request
     */
    public BatchRequest withClock(Instant at) {
        return new BatchRequest(ruleSet, items, idempotencyKey, at);
    }

    /**
     * One item of a batch.
     *
     * @param input the inputs
     * @param correlationId the host's case reference written to this item's decision row, or null
     */
    public record Item(DecisionInput input, String correlationId) {

        public Item {
            Objects.requireNonNull(input, "input must not be null");
        }

        /**
         * Creates an item without a correlation id.
         *
         * @param input the inputs
         * @return the item
         */
        public static Item of(DecisionInput input) {
            return new Item(input, null);
        }
    }
}
