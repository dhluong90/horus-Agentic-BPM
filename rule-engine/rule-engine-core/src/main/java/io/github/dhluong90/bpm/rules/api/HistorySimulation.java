package io.github.dhluong90.bpm.rules.api;

import java.time.Instant;

/**
 * Which logged decisions a history simulation replays through a draft (RLE-015): the caller's tenant's decisions of
 * the rule set made in {@code [from, to)}, newest first, at most {@code limit} of them.
 *
 * @param from the first instant, inclusive; required
 * @param to the end, exclusive; required, after {@code from} and at most {@code query.max-range} (31 days) later
 * @param limit the most decisions to replay, 1 to {@code simulation.max-inputs} (1,000); null for that maximum
 */
public record HistorySimulation(Instant from, Instant to, Integer limit) {

    /**
     * Replays the decisions of a period, up to the host's maximum.
     *
     * @param from the first instant, inclusive
     * @param to the end, exclusive
     * @return the request
     */
    public static HistorySimulation between(Instant from, Instant to) {
        return new HistorySimulation(from, to, null);
    }

    /**
     * Returns a copy that replays at most {@code limit} decisions.
     *
     * @param limit the most decisions
     * @return the copy
     */
    public HistorySimulation withLimit(int limit) {
        return new HistorySimulation(from, to, limit);
    }
}
