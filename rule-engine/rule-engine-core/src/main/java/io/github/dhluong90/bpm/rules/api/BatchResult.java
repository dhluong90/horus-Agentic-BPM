package io.github.dhluong90.bpm.rules.api;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * The outcome of a batch evaluation: one entry per item, in the order sent (RLE-067). An item that failed does not
 * stop the others. Results never echo the inputs, so a batch over many customers holds no personal data.
 *
 * @param ruleSet the rule set name
 * @param version the one version every item was evaluated with
 * @param clock the instant every decision reasoned about
 * @param items one entry per item
 */
public record BatchResult(String ruleSet, int version, Instant clock, List<Item> items) {

    public BatchResult {
        Objects.requireNonNull(ruleSet, "ruleSet must not be null");
        Objects.requireNonNull(clock, "clock must not be null");
        items = List.copyOf(items);
    }

    /** @return how many items produced a decision that is not a failure */
    public int succeeded() {
        return (int) items.stream().filter(item -> !item.failed()).count();
    }

    /** @return how many items failed: an invalid input, a failed evaluation, or an item that was not evaluated */
    public int failed() {
        return items.size() - succeeded();
    }

    /**
     * One item's entry. A logged decision carries its result, which may itself be a failure such as
     * {@code UNIQUE_HIT_VIOLATION}; an item that was refused before it was a decision, or never evaluated, carries
     * a code and a detail and no decision id.
     *
     * @param index the item's position in the request, from 0
     * @param decision the logged decision, or null for an item with no decision row
     * @param code the error code when there is no decision, else null
     * @param detail the detail when there is no decision, else null
     */
    public record Item(int index, DecisionResult decision, String code, String detail) {

        public Item {
            if (decision == null && (code == null || detail == null)) {
                throw new IllegalArgumentException("an item without a decision needs a code and a detail");
            }
        }

        /**
         * An item that became a logged decision.
         *
         * @param index the position
         * @param decision the result
         * @return the item
         */
        public static Item decided(int index, DecisionResult decision) {
            return new Item(index, decision, null, null);
        }

        /**
         * An item that is not a decision: no row was written.
         *
         * @param index the position
         * @param code the error code
         * @param detail the detail, without input values
         * @return the item
         */
        public static Item refused(int index, String code, String detail) {
            return new Item(index, null, code, detail);
        }

        /** @return true when the item has no decision, or its decision is a failure */
        public boolean failed() {
            return decision == null || decision.isError();
        }
    }
}
