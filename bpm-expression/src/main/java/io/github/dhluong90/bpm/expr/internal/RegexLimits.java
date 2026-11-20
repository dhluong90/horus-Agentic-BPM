package io.github.dhluong90.bpm.expr.internal;

/**
 * Bounds of one regular expression match (TR-SEC-02, RLE-007): the number of characters one match may read (the
 * deterministic primary bound), the number of characters all the matches of one decision may read together, the
 * longest text {@code matches} looks at, and the longest time one match may run. A host may tighten each; none can be
 * switched off.
 *
 * @param timeoutMillis {@code evaluation.regex-timeout-ms}, 1 to {@value #TIMEOUT_MILLIS_MAX}
 * @param maxInputLength {@code evaluation.regex-max-input-length}, 1 to {@value #MAX_INPUT_LENGTH_CEILING}
 * @param maxReads {@code evaluation.regex-max-reads}, 1 to {@value #MAX_READS}: the deterministic primary bound, the
 *     number of characters one match may read before it stops. The same on every machine, so a decision that stops
 *     here replays the same everywhere; the timeout is the backstop for a slow machine
 * @param maxReadsPerDecision {@code evaluation.regex-max-reads-per-decision}, 1 to {@value #MAX_READS_PER_DECISION}:
 *     the characters every {@code matches} of one evaluation may read together, counted the same deterministic way.
 *     Bounds a rule set of many {@code matches} rows, whose cost would otherwise be rows times {@code maxReads}
 */
public record RegexLimits(int timeoutMillis, int maxInputLength, int maxReads, int maxReadsPerDecision) {

    /** Default and ceiling of {@code evaluation.regex-max-reads}. */
    public static final int MAX_READS = 1_000_000;

    /** Default and ceiling of {@code evaluation.regex-max-reads-per-decision}. */
    public static final int MAX_READS_PER_DECISION = 5_000_000;

    /** Default of {@code evaluation.regex-timeout-ms}. */
    public static final int TIMEOUT_MILLIS = 50;

    /** Ceiling of {@code evaluation.regex-timeout-ms}: one match never holds a thread longer than a second. */
    public static final int TIMEOUT_MILLIS_MAX = 1000;

    /** Default of {@code evaluation.regex-max-input-length}, in UTF-16 code units. */
    public static final int MAX_INPUT_LENGTH = 4096;

    /** Ceiling of {@code evaluation.regex-max-input-length}: the longest string input the engine accepts. */
    public static final int MAX_INPUT_LENGTH_CEILING = 65_536;

    /**
     * Creates limits with the default read budget.
     *
     * @param timeoutMillis see the record
     * @param maxInputLength see the record
     */
    public RegexLimits(int timeoutMillis, int maxInputLength) {
        this(timeoutMillis, maxInputLength, MAX_READS);
    }

    /**
     * Creates limits with the default per-decision read budget.
     *
     * @param timeoutMillis see the record
     * @param maxInputLength see the record
     * @param maxReads see the record
     */
    public RegexLimits(int timeoutMillis, int maxInputLength, int maxReads) {
        this(timeoutMillis, maxInputLength, maxReads, MAX_READS_PER_DECISION);
    }

    public RegexLimits {
        if (maxReads < 1 || maxReads > MAX_READS) {
            throw new IllegalArgumentException("horus.bpm.rules.evaluation.regex-max-reads must be 1 to " + MAX_READS
                    + " (it may be lowered, never raised), was " + maxReads);
        }
        if (maxReadsPerDecision < 1 || maxReadsPerDecision > MAX_READS_PER_DECISION) {
            throw new IllegalArgumentException("horus.bpm.rules.evaluation.regex-max-reads-per-decision must be 1 to "
                    + MAX_READS_PER_DECISION + " (it may be lowered, never raised), was " + maxReadsPerDecision);
        }
        if (timeoutMillis < 1 || timeoutMillis > TIMEOUT_MILLIS_MAX) {
            throw new IllegalArgumentException("horus.bpm.rules.evaluation.regex-timeout-ms must be 1 to "
                    + TIMEOUT_MILLIS_MAX + ", was " + timeoutMillis);
        }
        if (maxInputLength < 1 || maxInputLength > MAX_INPUT_LENGTH_CEILING) {
            throw new IllegalArgumentException("horus.bpm.rules.evaluation.regex-max-input-length must be 1 to "
                    + MAX_INPUT_LENGTH_CEILING + ", was " + maxInputLength);
        }
    }

    /**
     * Returns the defaults: 1,000,000 reads per match, 5,000,000 per decision, 50 ms and 4,096 characters.
     *
     * @return the limits
     */
    public static RegexLimits defaults() {
        return new RegexLimits(TIMEOUT_MILLIS, MAX_INPUT_LENGTH, MAX_READS, MAX_READS_PER_DECISION);
    }

    /**
     * Starts the read budget of one decision.
     *
     * @return a fresh counter holding {@link #maxReadsPerDecision()} reads
     */
    public DecisionReads newDecision() {
        return new DecisionReads(maxReadsPerDecision);
    }
}
