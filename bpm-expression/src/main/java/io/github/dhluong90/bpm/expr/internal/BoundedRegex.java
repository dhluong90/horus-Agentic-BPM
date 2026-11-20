package io.github.dhluong90.bpm.expr.internal;

import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Regular expressions of {@code matches(text, 'pattern')}, compiled once at import and run under bounds (TR-SEC-02,
 * RLE-007). The dialect is the subset of the XPath {@code fn:matches} regex dialect that Java reads the same way
 * (DESIGN 3.2): refused at import are back-references, look-around, inline flags and every {@code (?} group except
 * {@code (?:}, possessive quantifiers, character class nesting and intersection, Unicode block names and the Java
 * escapes XPath does not have. Searching is unanchored.
 *
 * <p>Bounds, in order:
 * <ol>
 *   <li>a text longer than {@code evaluation.regex-max-input-length} is refused before matching
 *       ({@value #REGEX_INPUT_TOO_LONG});</li>
 *   <li>the primary bound is deterministic: the matcher reads its text through a {@link CharSequence} that counts
 *       every character read and stops at {@code evaluation.regex-max-reads} (1,000,000 by default), so a decision
 *       that stops replays the same on every machine ({@value #REGEX_TIMEOUT});</li>
 *   <li>the same reads are charged to the decision: every match of one evaluation reads from
 *       {@code evaluation.regex-max-reads-per-decision} (5,000,000 by default), so a rule set of many {@code matches}
 *       rows stops at that total, deterministically too ({@value #REGEX_TIMEOUT});</li>
 *   <li>the wall-clock {@code evaluation.regex-timeout-ms} is the backstop on a slow machine, checked every
 *       {@value #CHECK_EVERY} reads ({@value #REGEX_TIMEOUT});</li>
 *   <li>a pattern whose backtracking recurses past the thread's stack fails the decision instead of the thread
 *       ({@value #REGEX_TOO_DEEP}).</li>
 * </ol>
 * None of these relies on interrupts: the match stops by itself on the calling thread, which is free at once.
 */
public final class BoundedRegex {

    /** Longest pattern accepted, in characters. A bound of the language. */
    public static final int MAX_PATTERN_LENGTH = 1000;

    /** Reason of a match that ran past its read budget or its time limit. */
    public static final String REGEX_TIMEOUT = "REGEX_TIMEOUT";

    /** Reason of a text longer than {@code evaluation.regex-max-input-length}; the match is not run. */
    public static final String REGEX_INPUT_TOO_LONG = "REGEX_INPUT_TOO_LONG";

    /** Reason of a match whose backtracking ran out of thread stack. */
    public static final String REGEX_TOO_DEEP = "REGEX_TOO_DEEP";

    /** Reads between two looks at the clock. */
    private static final int CHECK_EVERY = 1024;

    /** Escapes of single characters that XPath and Java read the same. */
    private static final String CHARACTER_ESCAPES = "nrt\\|.-^?*+{}()[]$/";

    /** Escapes of character classes that XPath and Java read the same. */
    private static final String CLASS_ESCAPES = "sSdDwW";

    private BoundedRegex() {
    }

    /**
     * Checks and compiles a pattern.
     *
     * @param pattern the pattern text
     * @param position the position of the pattern literal, for the message
     * @return the compiled pattern
     * @throws ExpressionException for a pattern that is too long, outside the dialect or not a valid regular
     *     expression
     */
    static Pattern compile(String pattern, int position) {
        if (pattern.length() > MAX_PATTERN_LENGTH) {
            throw new ExpressionException("Pattern longer than " + MAX_PATTERN_LENGTH + " characters", position);
        }
        String refused = unsupportedConstruct(pattern);
        if (refused != null) {
            throw new ExpressionException("Pattern " + refused, position);
        }
        try {
            return Pattern.compile(pattern);
        } catch (PatternSyntaxException e) {
            throw new ExpressionException("Invalid pattern: " + e.getDescription(), position);
        }
    }

    /** Why a pattern is outside the dialect, or null when it is inside. */
    static String unsupportedConstruct(String source) {
        boolean inClass = false;
        int n = source.length();
        for (int i = 0; i < n; i++) {
            char c = source.charAt(i);
            if (c == '\\') {
                if (i + 1 >= n) {
                    return "ends with a lone backslash";
                }
                char escaped = source.charAt(++i);
                if (escaped >= '0' && escaped <= '9') {
                    return "uses a back-reference, which matches() does not support";
                }
                if (escaped == 'p' || escaped == 'P') {
                    String problem = categoryProblem(source, i);
                    if (problem != null) {
                        return problem;
                    }
                    i = source.indexOf('}', i);
                } else if (CHARACTER_ESCAPES.indexOf(escaped) < 0 && CLASS_ESCAPES.indexOf(escaped) < 0) {
                    return "uses the escape \\" + escaped + ", which is not in the XPath regex dialect";
                }
                continue;
            }
            if (inClass) {
                if (c == '[') {
                    return "nests a character class, which matches() does not support";
                }
                if (c == '&' && i + 1 < n && source.charAt(i + 1) == '&') {
                    return "uses character class intersection, which matches() does not support";
                }
                if (c == ']') {
                    inClass = false;
                }
                continue;
            }
            switch (c) {
                case '[' -> {
                    inClass = true;
                    if (i + 1 < n && source.charAt(i + 1) == '^') {
                        i++;
                    }
                    if (i + 1 < n && source.charAt(i + 1) == ']') {
                        i++; // a leading ] is a literal
                    }
                }
                case '(' -> {
                    if (i + 1 < n && source.charAt(i + 1) == '?' && !(i + 2 < n && source.charAt(i + 2) == ':')) {
                        return "uses look-around or an inline flag, which matches() does not support";
                    }
                }
                case '*', '+', '?', '}' -> {
                    if (i + 1 < n && source.charAt(i + 1) == '+') {
                        return "uses a possessive quantifier, which matches() does not support";
                    }
                }
                default -> {
                    // an ordinary character
                }
            }
        }
        return null;
    }

    /** {@code \p{L}} or {@code \P{Nd}}: a general category; block names such as {@code IsBasicLatin} differ. */
    private static String categoryProblem(String source, int at) {
        int open = at + 1;
        int close = source.indexOf('}', open);
        if (open >= source.length() || source.charAt(open) != '{' || close < 0) {
            return "uses \\p without a {category}";
        }
        String name = source.substring(open + 1, close);
        if (!name.matches("[CLMNPSZ][a-z]?")) {
            return "uses the category \\p{" + name + "}; only general categories such as L, Lu or Nd are allowed";
        }
        return null;
    }

    /**
     * Runs an unanchored search of a compiled pattern in a text, within the limits, as the only match of its decision.
     *
     * @param pattern the compiled pattern
     * @param text the text
     * @param limits the bounds
     * @return true when the pattern is found
     * @throws EvaluationException see {@link #find(Pattern, String, RegexLimits, DecisionReads)}
     */
    static boolean find(Pattern pattern, String text, RegexLimits limits) {
        return find(pattern, text, limits, limits.newDecision());
    }

    /**
     * Runs an unanchored search of a compiled pattern in a text, within the limits, charging its reads to its
     * decision.
     *
     * @param pattern the compiled pattern
     * @param text the text
     * @param limits the bounds
     * @param decision the reads left to the decision this match belongs to; charged with the reads it makes
     * @return true when the pattern is found
     * @throws EvaluationException with reason {@value #REGEX_INPUT_TOO_LONG}, {@value #REGEX_TIMEOUT} or
     *     {@value #REGEX_TOO_DEEP}; the detail never contains the text
     */
    static boolean find(Pattern pattern, String text, RegexLimits limits, DecisionReads decision) {
        if (text.length() > limits.maxInputLength()) {
            throw new EvaluationException(REGEX_INPUT_TOO_LONG, "matches() was given " + text.length()
                    + " characters; the limit is " + limits.maxInputLength() + " (evaluation.regex-max-input-length)");
        }
        // The decision's remainder binds when it is below one match's own cap.
        boolean decisionBinds = decision.left() < limits.maxReads();
        int reads = decisionBinds ? (int) decision.left() : limits.maxReads();
        Budget budget = new Budget(reads, System.nanoTime() + limits.timeoutMillis() * 1_000_000L);
        try {
            return pattern.matcher(new BudgetedText(text, budget, 0, text.length())).find();
        } catch (ReadsSpent e) {
            throw new EvaluationException(REGEX_TIMEOUT, decisionBinds
                    ? "matches() stopped: the decision's regular expressions read " + limits.maxReadsPerDecision()
                            + " characters in all (evaluation.regex-max-reads-per-decision); simplify the patterns"
                            + " or use fewer matches() rows"
                    : "matches() stopped after reading " + limits.maxReads()
                            + " characters (evaluation.regex-max-reads); simplify the pattern");
        } catch (Expired e) {
            throw new EvaluationException(REGEX_TIMEOUT, "matches() ran longer than " + limits.timeoutMillis()
                    + " ms and was stopped (evaluation.regex-timeout-ms)");
        } catch (StackOverflowError e) {
            // Backtracking recursion grows with the text for some legal patterns, such as (a|b)*c; the stack has
            // unwound by the time we are here, so the thread carries on and only this decision fails.
            throw new EvaluationException(REGEX_TOO_DEEP, "matches() ran out of thread stack on a text of "
                    + text.length() + " characters; simplify the pattern or lower evaluation.regex-max-input-length");
        } finally {
            decision.spend(budget.used());
        }
    }

    /** Reads left and the deadline of one match, shared by every view of its text. */
    private static final class Budget {
        private final int reads;
        private int readsLeft;
        private int untilClock = CHECK_EVERY;
        private final long deadline;

        Budget(int reads, long deadline) {
            this.reads = reads;
            this.readsLeft = reads;
            this.deadline = deadline;
        }

        /** Characters read so far, at most the budget. */
        int used() {
            return reads - Math.max(0, readsLeft);
        }

        void read() {
            if (--readsLeft < 0) {
                throw new ReadsSpent();
            }
            if (--untilClock == 0) {
                untilClock = CHECK_EVERY;
                if (System.nanoTime() - deadline > 0) {
                    throw new Expired();
                }
            }
        }
    }

    /** The text as the matcher reads it, every read counted against the match's budget. */
    private record BudgetedText(String text, Budget budget, int start, int end) implements CharSequence {

        @Override
        public char charAt(int index) {
            budget.read();
            return text.charAt(start + index);
        }

        @Override
        public int length() {
            return end - start;
        }

        @Override
        public CharSequence subSequence(int from, int to) {
            return new BudgetedText(text, budget, start + from, start + to);
        }

        @Override
        public String toString() {
            return text.substring(start, end);
        }
    }

    /** The read budget is spent; control flow without a stack trace. */
    private static final class ReadsSpent extends RuntimeException {
        private static final long serialVersionUID = 1L;

        ReadsSpent() {
            super(null, null, false, false);
        }
    }

    /** The deadline has passed; control flow without a stack trace. */
    private static final class Expired extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Expired() {
            super(null, null, false, false);
        }
    }
}
