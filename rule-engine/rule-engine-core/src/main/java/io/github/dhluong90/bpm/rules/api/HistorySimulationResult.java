package io.github.dhluong90.bpm.rules.api;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * What a version would have decided for real logged decisions, beside what was decided (RLE-015). Nothing of it is
 * logged: a history simulation writes no decision row, idempotency record or event. It holds no logged input.
 *
 * @param ruleSet the rule set name
 * @param version the simulated version, normally a draft or a version pending approval
 * @param status the simulated version's status
 * @param from the first instant of the period, inclusive
 * @param to the end of the period, exclusive
 * @param truncated true when the period held more decisions than were replayed; the newest were replayed
 * @param items one per logged decision, newest first
 */
public record HistorySimulationResult(String ruleSet, int version, VersionStatus status, Instant from, Instant to,
        boolean truncated, List<Item> items) {

    public HistorySimulationResult {
        Objects.requireNonNull(ruleSet, "ruleSet must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(from, "from must not be null");
        Objects.requireNonNull(to, "to must not be null");
        items = List.copyOf(items);
    }

    /**
     * Counts the decisions replayed through both versions.
     *
     * @return the items that are {@link Item#replayable()}
     */
    public int replayedCount() {
        return (int) items.stream().filter(Item::replayable).count();
    }

    /**
     * Counts the decisions whose outcome the simulated version changes.
     *
     * @return the items with {@link Item#changed()} set
     */
    public int changedCount() {
        return (int) items.stream().filter(Item::changed).count();
    }

    /**
     * Counts the decisions that could not be replayed, because a masked input had a value the log does not hold.
     *
     * @return the items that are not {@link Item#replayable()}
     */
    public int notReplayableCount() {
        return items.size() - replayedCount();
    }

    /**
     * One logged decision, replayed with its logged version and through the simulated version, both at its logged
     * clock.
     *
     * @param decisionId the logged decision's id
     * @param loggedVersion the version the decision was made with
     * @param evalClock the logged clock both sides were evaluated at
     * @param simulated what the simulated version decides, or null when the decision is not replayable
     * @param logged what the logged version decides again, which is what was decided (TR-DET-05), or null when the
     *     decision is not replayable
     * @param changed true when the outcome kind, an output value or the failure code differs; a different matched
     *     rule giving the same outputs is not a change
     * @param maskedInputs the masked inputs that had a value, which the log holds only as fingerprints; empty when the
     *     decision was replayed. Names only, never values
     */
    public record Item(String decisionId, int loggedVersion, Instant evalClock, DecisionResult.Outcome simulated,
            DecisionResult.Outcome logged, boolean changed, List<String> maskedInputs) {

        public Item {
            Objects.requireNonNull(decisionId, "decisionId must not be null");
            Objects.requireNonNull(evalClock, "evalClock must not be null");
            maskedInputs = List.copyOf(maskedInputs);
        }

        /**
         * Tells whether the decision was replayed.
         *
         * @return true when no masked input with a value stopped it
         */
        public boolean replayable() {
            return maskedInputs.isEmpty();
        }
    }
}
