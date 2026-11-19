package com.horus.bpm.rules.internal.expr;

import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * The pattern of {@code matches(s, 'pattern')}: checked against the subset of the XPath {@code fn:matches} regex
 * dialect that means the same in Java (rule-engine DESIGN 3.2 item 3) and compiled once, at import. Searching is
 * unanchored, as FEEL {@code matches}, and stops with {@link EvaluationException} once it has read
 * {@value #MAX_STEPS} characters, so a pattern that backtracks without end fails deterministically instead of holding
 * the thread. The time limit of {@code TR-SEC-02} comes on top of this budget with {@code RLE-007}.
 *
 * <p>Refused at import: back-references, look-around, inline flags and every other {@code (?} group except
 * {@code (?:}, possessive quantifiers, character class intersection and nesting, the Unicode block escapes and the
 * Java escapes that XPath does not have. Two patterns are equal when their source text is.
 */
public final class Regex {

    /** Characters a search may read before it fails: a step budget, the same on every replica. */
    public static final int MAX_STEPS = 1_000_000;

    /** Longest pattern accepted, in characters. */
    public static final int MAX_LENGTH = 1024;

    /** Escapes of single characters that XPath and Java read the same. */
    private static final String CHARACTER_ESCAPES = "nrt\\|.-^?*+{}()[]$/";

    /** Escapes of character classes that XPath and Java read the same. */
    private static final String CLASS_ESCAPES = "sSdDwW";

    private final String source;
    private final Pattern pattern;

    private Regex(String source, Pattern pattern) {
        this.source = source;
        this.pattern = pattern;
    }

    /**
     * Checks and compiles a pattern.
     *
     * @param source the pattern text
     * @return the compiled pattern
     * @throws IllegalArgumentException naming the construct that is not allowed; the message starts with "pattern"
     */
    public static Regex compile(String source) {
        if (source.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("pattern longer than " + MAX_LENGTH + " characters");
        }
        checkDialect(source);
        try {
            return new Regex(source, Pattern.compile(source));
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException("pattern is not a valid regular expression: " + e.getDescription());
        }
    }

    /** The pattern as written. */
    public String source() {
        return source;
    }

    /**
     * Searches the text for the pattern, unanchored.
     *
     * @param text the text
     * @return true when the pattern occurs in the text
     * @throws EvaluationException when the search reads more than {@value #MAX_STEPS} characters
     */
    public boolean find(String text) {
        try {
            return pattern.matcher(new BoundedChars(text, new int[] {0}, 0, text.length())).find();
        } catch (BudgetExceeded e) {
            throw new EvaluationException("matches stopped at its step budget of " + MAX_STEPS
                    + " characters read; simplify the pattern");
        }
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Regex regex && regex.source.equals(source);
    }

    @Override
    public int hashCode() {
        return source.hashCode();
    }

    @Override
    public String toString() {
        return source;
    }

    private static void checkDialect(String source) {
        boolean inClass = false;
        int n = source.length();
        for (int i = 0; i < n; i++) {
            char c = source.charAt(i);
            if (c == '\\') {
                if (i + 1 >= n) {
                    throw refused("ends with a lone backslash");
                }
                char escaped = source.charAt(++i);
                if (escaped >= '0' && escaped <= '9') {
                    throw refused("uses a back-reference, which is not allowed");
                }
                if (escaped == 'p' || escaped == 'P') {
                    i = checkCategory(source, i);
                } else if (CHARACTER_ESCAPES.indexOf(escaped) < 0 && CLASS_ESCAPES.indexOf(escaped) < 0) {
                    throw refused("uses the escape \\" + escaped + ", which is not in the XPath regex dialect");
                }
                continue;
            }
            if (inClass) {
                if (c == '[') {
                    throw refused("nests a character class, which is not allowed");
                }
                if (c == '&' && i + 1 < n && source.charAt(i + 1) == '&') {
                    throw refused("uses character class intersection, which is not allowed");
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
                    if (i + 1 < n && source.charAt(i + 1) == '?'
                            && !(i + 2 < n && source.charAt(i + 2) == ':')) {
                        throw refused("uses look-around or an inline flag, which is not allowed");
                    }
                }
                case '*', '+', '?', '}' -> {
                    if (i + 1 < n && source.charAt(i + 1) == '+') {
                        throw refused("uses a possessive quantifier, which is not allowed");
                    }
                }
                default -> {
                    // an ordinary character
                }
            }
        }
    }

    /** {@code \p{L}} or {@code \P{Nd}}: a general category; block names ({@code IsBasicLatin}) differ from Java. */
    private static int checkCategory(String source, int at) {
        int open = at + 1;
        int close = source.indexOf('}', open);
        if (open >= source.length() || source.charAt(open) != '{' || close < 0) {
            throw refused("uses \\p without a {category}");
        }
        String name = source.substring(open + 1, close);
        if (!name.matches("[CLMNPSZ][a-z]?")) {
            throw refused("uses the category \\p{" + name + "}; only general categories such as L, Lu or Nd are allowed");
        }
        return close;
    }

    private static IllegalArgumentException refused(String reason) {
        return new IllegalArgumentException("pattern " + reason);
    }

    /** Thrown inside the matcher when the budget is spent; never leaves this class. */
    private static final class BudgetExceeded extends RuntimeException {
        private static final long serialVersionUID = 1L;

        BudgetExceeded() {
            super(null, null, false, false);
        }
    }

    /** The searched text, counting every character the matcher reads against one shared budget. */
    private record BoundedChars(String text, int[] steps, int start, int end) implements CharSequence {

        @Override
        public int length() {
            return end - start;
        }

        @Override
        public char charAt(int index) {
            if (++steps[0] > MAX_STEPS) {
                throw new BudgetExceeded();
            }
            return text.charAt(start + index);
        }

        @Override
        public CharSequence subSequence(int from, int to) {
            return new BoundedChars(text, steps, start + from, start + to);
        }

        @Override
        public String toString() {
            return text.substring(start, end);
        }
    }
}
