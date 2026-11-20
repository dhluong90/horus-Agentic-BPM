package io.github.dhluong90.bpm.rules.internal.expr;

/**
 * Bounds of one regular expression match (TR-SEC-02, RLE-007): the number of characters one match may read (the
 * deterministic primary bound), the longest text {@code matches} looks at, and the longest time one match may run. A host may tighten both; neither can be switched off.
 *
 * @param timeoutMillis {@code evaluation.regex-timeout-ms}, 1 to {@value #TIMEOUT_MILLIS_MAX}
 * @param maxInputLength {@code evaluation.regex-max-input-length}, 1 to {@value #MAX_INPUT_LENGTH_CEILING}
 * @param maxReads {@code evaluation.regex-max-reads}, 1 to {@value #MAX_READS}: the deterministic primary bound, the
 *     number of characters one match may read before it stops. The same on every machine, so a decision that stops
 *     here replays the same everywhere; the timeout is the backstop for a slow machine
 */
public record RegexLimits(int timeoutMillis, int maxInputLength, int maxReads) {

    /** Default and ceiling of {@code evaluation.regex-max-reads}. */
    public static final int MAX_READS = 1_000_000;

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

    public RegexLimits {
        if (maxReads < 1 || maxReads > MAX_READS) {
            throw new IllegalArgumentException("horus.bpm.rules.evaluation.regex-max-reads must be 1 to " + MAX_READS
                    + " (it may be lowered, never raised), was " + maxReads);
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
     * Returns the defaults: 1,000,000 reads, 50 ms and 4,096 characters.
     *
     * @return the limits
     */
    public static RegexLimits defaults() {
        return new RegexLimits(TIMEOUT_MILLIS, MAX_INPUT_LENGTH, MAX_READS);
    }
}
