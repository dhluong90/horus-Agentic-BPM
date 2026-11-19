package io.github.dhluong90.bpm.rules.internal.expr;

/**
 * Bounds of one regular expression match (TR-SEC-02, RLE-007): the longest text {@code matches} looks at and the
 * longest time one match may run. A host may tighten both; neither can be switched off.
 *
 * @param timeoutMillis {@code evaluation.regex-timeout-ms}, 1 to {@value #TIMEOUT_MILLIS_MAX}
 * @param maxInputLength {@code evaluation.regex-max-input-length}, 1 to {@value #MAX_INPUT_LENGTH_CEILING}
 */
public record RegexLimits(int timeoutMillis, int maxInputLength) {

    /** Default of {@code evaluation.regex-timeout-ms}. */
    public static final int TIMEOUT_MILLIS = 50;

    /** Ceiling of {@code evaluation.regex-timeout-ms}: one match never holds a thread longer than a second. */
    public static final int TIMEOUT_MILLIS_MAX = 1000;

    /** Default of {@code evaluation.regex-max-input-length}, in UTF-16 code units. */
    public static final int MAX_INPUT_LENGTH = 4096;

    /** Ceiling of {@code evaluation.regex-max-input-length}: the longest string input the engine accepts. */
    public static final int MAX_INPUT_LENGTH_CEILING = 65_536;

    public RegexLimits {
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
     * Returns the defaults: 50 ms and 4,096 characters.
     *
     * @return the limits
     */
    public static RegexLimits defaults() {
        return new RegexLimits(TIMEOUT_MILLIS, MAX_INPUT_LENGTH);
    }
}
