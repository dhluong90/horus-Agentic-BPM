package io.github.dhluong90.bpm.rules.api;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * The evidence of what a version would change, run and stored by the engine and bound to the version's canonical hash
 * (PD-115, RLE-199, RLE-200). A report never changes after it is stored. It holds counts, and up to
 * {@link #ROWS_MAX} changed or failed rows read in plain language; it holds no input value, masked or not, and no
 * sample label.
 *
 * @param id the report id
 * @param ruleSet the rule set name
 * @param version the version the report was run on
 * @param canonicalHash the {@code canonicalHash} of that version when the report was run
 * @param status {@code CURRENT} while the version still has that hash, {@code OUT_OF_DATE} after the draft changed
 * @param attached true when the version's publication request carries this report
 * @param createdBy who ran the report
 * @param createdAt when it was run
 * @param scope what it ran over
 * @param counts how the decisions or samples fall
 * @param languages the language codes the rows are read in, sorted
 * @param rows the changed or failed items, at most {@link #ROWS_MAX}; empty in a list
 * @param rowsTruncated true when more items changed or failed than the rows hold
 */
public record SimulationReport(String id, String ruleSet, int version, String canonicalHash, Status status,
        boolean attached, String createdBy, Instant createdAt, Scope scope, Counts counts, List<String> languages,
        List<Row> rows, boolean rowsTruncated) {

    /** The most changed or failed rows a report keeps. */
    public static final int ROWS_MAX = 50;

    /** Whether the report still describes the version. */
    public enum Status {
        /** The version has the hash the report was run on. */
        CURRENT,
        /** The draft changed after the report; it cannot be attached. */
        OUT_OF_DATE
    }

    public SimulationReport {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(ruleSet, "ruleSet must not be null");
        Objects.requireNonNull(canonicalHash, "canonicalHash must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(createdBy, "createdBy must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(scope, "scope must not be null");
        Objects.requireNonNull(counts, "counts must not be null");
        languages = List.copyOf(languages);
        rows = List.copyOf(rows);
    }

    /**
     * What the report ran over.
     *
     * @param source {@code SAMPLES} or {@code REPLAY}
     * @param from for a replay, the first instant of the period, inclusive; otherwise null
     * @param to for a replay, the end of the period, exclusive; otherwise null
     * @param limit for a replay, the limit it ran with; otherwise null
     * @param truncated for a replay, true when the period held more decisions than were replayed
     */
    public record Scope(SimulationReportRequest.Source source, Instant from, Instant to, Integer limit,
            boolean truncated) {

        public Scope {
            Objects.requireNonNull(source, "source must not be null");
        }
    }

    /**
     * The counts. Each item falls in exactly one: not replayable (a masked input had a value the log holds only as a
     * fingerprint), else failed (the simulated version failed), else changed, else unchanged.
     *
     * @param total all items
     * @param changed items whose outcome differs
     * @param unchanged items whose outcome is the same
     * @param failed items the simulated version failed on
     * @param notReplayable replayed decisions that could not be, because of a masked input
     */
    public record Counts(int total, int changed, int unchanged, int failed, int notReplayable) {
    }

    /**
     * One changed or failed item.
     *
     * @param reference {@code S1}, {@code S2}, for a sample; null for a replayed decision
     * @param decisionId for a replayed decision, its id; null for a sample, and null when the caller lacks
     *     {@code rules:log:read}
     * @param category {@code CHANGED} or {@code FAILED}
     * @param before what was decided before: the version in effect for a sample, the logged decision for a replay
     * @param after what the reported version decides
     */
    public record Row(String reference, String decisionId, String category, RuleReadings.OutcomeReading before,
            RuleReadings.OutcomeReading after) {

        public Row {
            Objects.requireNonNull(category, "category must not be null");
        }

        /**
         * Returns a copy without the decision id.
         *
         * @return the row
         */
        public Row withoutDecisionId() {
            return new Row(reference, null, category, before, after);
        }
    }
}
