package io.github.dhluong90.bpm.rules.internal.expr;

import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Regular expressions of {@code matches(text, pattern)}, compiled once at import and run under a length and time bound
 * (TR-SEC-02, RLE-007). The pattern dialect is the XPath {@code fn:matches} subset the design names: no
 * back-references and no look-around, so a pattern means the same in the FEEL translation. The time bound does not
 * rely on the thread being interrupted: the matcher reads its text through a {@link CharSequence} that checks a
 * deadline, so a runaway match stops by itself on the calling thread and the thread is free at once.
 */
public final class BoundedRegex {

    /** Longest pattern accepted, in characters. A bound of the language. */
    public static final int MAX_PATTERN_LENGTH = 1000;

    /** Reason of a match that ran past {@code evaluation.regex-timeout-ms}. */
    public static final String REGEX_TIMEOUT = "REGEX_TIMEOUT";

    /** Reason of a text longer than {@code evaluation.regex-max-input-length}; the match is not run. */
    public static final String REGEX_INPUT_TOO_LONG = "REGEX_INPUT_TOO_LONG";

    /** Reads between two looks at the clock: often enough for millisecond precision, rare enough to cost nothing. */
    private static final int CHECK_EVERY = 1024;

    private BoundedRegex() {
    }

    /**
     * Checks and compiles a pattern.
     *
     * @param pattern the pattern text
     * @param position the position of the pattern literal, for the message
     * @return the compiled pattern
     * @throws ExpressionException for a pattern that is too long, uses a back-reference or look-around, or is not a
     *     valid regular expression
     */
    static Pattern compile(String pattern, int position) {
        if (pattern.length() > MAX_PATTERN_LENGTH) {
            throw new ExpressionException("Pattern longer than " + MAX_PATTERN_LENGTH + " characters", position);
        }
        String unsupported = unsupportedConstruct(pattern);
        if (unsupported != null) {
            throw new ExpressionException("Pattern uses " + unsupported + ", which matches() does not support",
                    position);
        }
        try {
            return Pattern.compile(pattern);
        } catch (PatternSyntaxException e) {
            throw new ExpressionException("Invalid pattern: " + e.getDescription(), position);
        }
    }

    /** The first back-reference or look-around of a pattern, or null; escapes and character classes are skipped. */
    static String unsupportedConstruct(String pattern) {
        boolean inClass = false;
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '\\' && i + 1 < pattern.length()) {
                char next = pattern.charAt(i + 1);
                if (!inClass && next >= '1' && next <= '9') {
                    return "a back-reference (\\" + next + ")";
                }
                if (!inClass && next == 'k') {
                    return "a named back-reference (\\k)";
                }
                i++;
            } else if (inClass) {
                if (c == ']') {
                    inClass = false;
                }
            } else if (c == '[') {
                inClass = true;
                // A ']' right after '[' or '[^' is a literal member of the class.
                if (i + 1 < pattern.length() && pattern.charAt(i + 1) == '^') {
                    i++;
                }
                if (i + 1 < pattern.length() && pattern.charAt(i + 1) == ']') {
                    i++;
                }
            } else if (c == '(' && pattern.startsWith("?", i + 1)) {
                if (pattern.startsWith("?=", i + 1) || pattern.startsWith("?!", i + 1)) {
                    return "a look-ahead";
                }
                if (pattern.startsWith("?<=", i + 1) || pattern.startsWith("?<!", i + 1)) {
                    return "a look-behind";
                }
            }
        }
        return null;
    }

    /**
     * Runs an unanchored search of a compiled pattern in a text, within the limits.
     *
     * @param pattern the compiled pattern
     * @param text the text
     * @param limits the length and time bounds
     * @return true when the pattern is found
     * @throws EvaluationException with reason {@value #REGEX_INPUT_TOO_LONG} before matching a text over the length
     *     limit, or {@value #REGEX_TIMEOUT} when the match runs past the time limit
     */
    static boolean find(Pattern pattern, String text, RegexLimits limits) {
        if (text.length() > limits.maxInputLength()) {
            throw new EvaluationException(REGEX_INPUT_TOO_LONG, "matches() was given " + text.length()
                    + " characters; the limit is " + limits.maxInputLength() + " (evaluation.regex-max-input-length)");
        }
        long deadline = System.nanoTime() + limits.timeoutMillis() * 1_000_000L;
        try {
            return pattern.matcher(new DeadlineText(text, deadline)).find();
        } catch (Expired e) {
            throw new EvaluationException(REGEX_TIMEOUT, "matches() ran longer than " + limits.timeoutMillis()
                    + " ms and was stopped (evaluation.regex-timeout-ms)");
        }
    }

    /** The text as the matcher reads it, with a look at the clock every {@value #CHECK_EVERY} reads. */
    private static final class DeadlineText implements CharSequence {

        private final String text;
        private final long deadline;
        private int reads;

        DeadlineText(String text, long deadline) {
            this.text = text;
            this.deadline = deadline;
        }

        @Override
        public char charAt(int index) {
            if (++reads == CHECK_EVERY) {
                reads = 0;
                if (System.nanoTime() - deadline > 0) {
                    throw new Expired();
                }
            }
            return text.charAt(index);
        }

        @Override
        public int length() {
            return text.length();
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            return new DeadlineText(text.substring(start, end), deadline);
        }

        @Override
        public String toString() {
            return text;
        }
    }

    /** Thrown out of the matcher when the deadline has passed; no stack trace, it is control flow. */
    private static final class Expired extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Expired() {
            super(null, null, false, false);
        }
    }
}
