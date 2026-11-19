package io.github.dhluong90.bpm.rules.internal.dmn.ruleset;

import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * The FEEL subset of DMN decision table cells that the engine reads and writes before the full FEEL engine
 * (ADR-0019): unary tests of input entries and literals of output entries. Hand-written, no reflection, nothing
 * evaluated here; every construct outside the subset is refused with a message that says what was found.
 *
 * <pre>
 * unaryTests = "-" | "not" "(" positives ")" | positives
 * positives  = positive { "," positive }
 * positive   = ("&lt;" | "&lt;=" | "&gt;" | "&gt;=" | "=") endpoint | interval | endpoint | "null"
 * interval   = ("[" | "(" | "]") endpoint ".." endpoint ("]" | ")" | "[")
 * endpoint   = ["-"] number | string | "true" | "false" | date("…") | date and time("…") | money(number, "CUR")
 * literal    = endpoint | "null"
 * </pre>
 *
 * {@code money(amount, "CUR")} is the HORUS money extension; it is not portable to other DMN engines.
 */
final class Feel {

    /** Longest cell text accepted, in characters. */
    static final int MAX_LENGTH = 4096;

    /** Most tests in one unary-tests list. */
    static final int MAX_TESTS = 256;

    private Feel() {
    }

    /** A FEEL literal value of the subset. */
    sealed interface Value {
        /** @param value the string */
        record Str(String value) implements Value {
        }

        /** @param value the number */
        record Num(BigDecimal value) implements Value {
        }

        /** @param value the boolean */
        record Bool(boolean value) implements Value {
        }

        /** @param value the date */
        record Date(LocalDate value) implements Value {
        }

        /** @param value the instant; FEEL {@code date and time} with an offset */
        record DateTime(Instant value) implements Value {
        }

        /**
         * The HORUS money extension.
         *
         * @param amount the amount in major units
         * @param currency the ISO 4217 code
         */
        record Money(BigDecimal amount, String currency) implements Value {
        }

        /** FEEL {@code null}. */
        record Null() implements Value {
        }
    }

    /** Comparison operators of a positive unary test; {@code EQ} is the bare endpoint. */
    enum Op { EQ, LT, LE, GT, GE }

    /** One positive unary test. */
    sealed interface Test {
        /**
         * @param op the operator
         * @param value the endpoint
         */
        record Compare(Op op, Value value) implements Test {
        }

        /**
         * @param lowClosed whether the low endpoint is included
         * @param low the low endpoint
         * @param high the high endpoint
         * @param highClosed whether the high endpoint is included
         */
        record Interval(boolean lowClosed, Value low, Value high, boolean highClosed) implements Test {
        }

        /** The test {@code null}: the input is null. */
        record IsNull() implements Test {
        }
    }

    /**
     * Parsed unary tests of an input entry.
     *
     * @param any true for {@code -}
     * @param negated true for {@code not(...)}
     * @param tests the positive tests, combined with OR; empty for {@code -}
     */
    record UnaryTests(boolean any, boolean negated, List<Test> tests) {
        static final UnaryTests ANY = new UnaryTests(true, false, List.of());

        UnaryTests {
            tests = List.copyOf(tests);
        }
    }

    /** A cell text outside the subset. */
    static final class UnsupportedException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        UnsupportedException(String message) {
            super(message);
        }
    }

    /**
     * Parses the text of an input entry.
     *
     * @param text the FEEL unary tests
     * @return the tests
     * @throws UnsupportedException when the text is outside the subset
     */
    static UnaryTests parseUnaryTests(String text) {
        Cursor cursor = new Cursor(text);
        if (cursor.peekIs("-") && cursor.peekAt(1).kind == Kind.EOF) {
            return UnaryTests.ANY;
        }
        boolean negated = false;
        if (cursor.peekWord("not") && cursor.peekAt(1).text.equals("(")) {
            cursor.next();
            cursor.next();
            negated = true;
        }
        List<Test> tests = new ArrayList<>();
        do {
            if (tests.size() >= MAX_TESTS) {
                throw new UnsupportedException("more than " + MAX_TESTS + " tests in one cell");
            }
            tests.add(positive(cursor));
        } while (cursor.accept(","));
        if (negated) {
            cursor.expect(")");
        }
        cursor.expectEnd();
        return new UnaryTests(false, negated, tests);
    }

    /**
     * Parses the text of an output entry.
     *
     * @param text the FEEL literal
     * @return the value
     * @throws UnsupportedException when the text is not a literal of the subset
     */
    static Value parseLiteral(String text) {
        Cursor cursor = new Cursor(text);
        Value value = cursor.peekWord("null") ? nullValue(cursor) : endpoint(cursor);
        cursor.expectEnd();
        return value;
    }

    /**
     * Prints unary tests.
     *
     * @param tests the tests
     * @return the FEEL text
     */
    static String print(UnaryTests tests) {
        if (tests.any()) {
            return "-";
        }
        StringBuilder out = new StringBuilder();
        for (Test test : tests.tests()) {
            if (!out.isEmpty()) {
                out.append(", ");
            }
            out.append(switch (test) {
                case Test.Compare(Op op, Value value) -> switch (op) {
                    case EQ -> print(value);
                    case LT -> "< " + print(value);
                    case LE -> "<= " + print(value);
                    case GT -> "> " + print(value);
                    case GE -> ">= " + print(value);
                };
                case Test.Interval(boolean lowClosed, Value low, Value high, boolean highClosed) ->
                        (lowClosed ? "[" : "(") + print(low) + ".." + print(high) + (highClosed ? "]" : ")");
                case Test.IsNull ignored -> "null";
            });
        }
        return tests.negated() ? "not(" + out + ")" : out.toString();
    }

    /**
     * Prints a literal.
     *
     * @param value the value
     * @return the FEEL text
     */
    static String print(Value value) {
        return switch (value) {
            case Value.Str(String s) -> quote(s);
            case Value.Num(BigDecimal n) -> n.toPlainString();
            case Value.Bool(boolean b) -> Boolean.toString(b);
            case Value.Date(LocalDate d) -> "date(\"" + d + "\")";
            case Value.DateTime(Instant i) -> "date and time(\"" + i + "\")";
            case Value.Money(BigDecimal amount, String currency) -> "money(" + amount.toPlainString() + ", \"" + currency + "\")";
            case Value.Null() -> "null";
        };
    }

    private static String quote(String s) {
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> out.append(c);
            }
        }
        return out.append('"').toString();
    }

    private static Test positive(Cursor cursor) {
        Token token = cursor.peek();
        if (token.kind == Kind.PUNCT) {
            switch (token.text) {
                case "<", "<=", ">", ">=", "=" -> {
                    cursor.next();
                    Op op = switch (token.text) {
                        case "<" -> Op.LT;
                        case "<=" -> Op.LE;
                        case ">" -> Op.GT;
                        case ">=" -> Op.GE;
                        default -> Op.EQ;
                    };
                    return new Test.Compare(op, endpoint(cursor));
                }
                case "!=" -> throw new UnsupportedException("'!=' is not a unary test; write not(" + "value)");
                case "[", "(", "]" -> {
                    cursor.next();
                    boolean lowClosed = token.text.equals("[");
                    Value low = endpoint(cursor);
                    cursor.expect("..");
                    Value high = endpoint(cursor);
                    Token close = cursor.next();
                    boolean highClosed;
                    switch (close.text) {
                        case "]" -> highClosed = true;
                        case ")", "[" -> highClosed = false;
                        default -> throw unexpected(close, "']', ')' or '['");
                    }
                    if (!low.getClass().equals(high.getClass())) {
                        throw new UnsupportedException("range endpoints " + Quote.text(print(low)) + " and " + Quote.text(print(high))
                                + " are of different types");
                    }
                    return new Test.Interval(lowClosed, low, high, highClosed);
                }
                default -> {
                    // an endpoint below
                }
            }
        }
        if (cursor.peekWord("null")) {
            cursor.next();
            return new Test.IsNull();
        }
        return new Test.Compare(Op.EQ, endpoint(cursor));
    }

    private static Value nullValue(Cursor cursor) {
        cursor.next();
        return new Value.Null();
    }

    private static Value endpoint(Cursor cursor) {
        Token token = cursor.next();
        switch (token.kind) {
            case NUMBER -> {
                return new Value.Num(new BigDecimal(token.text));
            }
            case STRING -> {
                return new Value.Str(token.text);
            }
            case PUNCT -> {
                if (token.text.equals("-") && cursor.peek().kind == Kind.NUMBER) {
                    return new Value.Num(new BigDecimal(cursor.next().text).negate());
                }
                if (token.text.equals("?")) {
                    throw new UnsupportedException("'?' (a unary test expression) is not supported before full FEEL");
                }
                throw unexpected(token, "a value");
            }
            case NAME -> {
                switch (token.text) {
                    case "true" -> {
                        return new Value.Bool(true);
                    }
                    case "false" -> {
                        return new Value.Bool(false);
                    }
                    case "date" -> {
                        if (cursor.peekWord("and") && cursor.peekAt(1).text.equals("time")) {
                            cursor.next();
                            cursor.next();
                            String text = stringArgument(cursor, "date and time");
                            try {
                                return new Value.DateTime(OffsetDateTime.parse(text).toInstant());
                            } catch (DateTimeException e) {
                                throw new UnsupportedException("date and time(\"" + Quote.text(text)
                                        + "\") needs an ISO 8601 date and time with Z or an offset");
                            }
                        }
                        String text = stringArgument(cursor, "date");
                        try {
                            return new Value.Date(LocalDate.parse(text));
                        } catch (DateTimeException e) {
                            throw new UnsupportedException("date(\"" + Quote.text(text) + "\") is not an ISO 8601 date");
                        }
                    }
                    case "money" -> {
                        cursor.expect("(");
                        boolean negative = cursor.accept("-");
                        Token amount = cursor.next();
                        if (amount.kind != Kind.NUMBER) {
                            throw unexpected(amount, "a number");
                        }
                        cursor.expect(",");
                        Token currency = cursor.next();
                        if (currency.kind != Kind.STRING || !currency.text.matches("[A-Z]{3}")) {
                            throw new UnsupportedException("money(...) needs a three-letter currency code string");
                        }
                        cursor.expect(")");
                        BigDecimal value = new BigDecimal(amount.text);
                        return new Value.Money(negative ? value.negate() : value, currency.text);
                    }
                    case "null" -> throw new UnsupportedException("null is not allowed here");
                    default -> {
                        if (cursor.peek().text.equals("(")) {
                            throw new UnsupportedException("function " + Quote.of(token.text) + " is not supported before full FEEL");
                        }
                        throw new UnsupportedException("name " + Quote.of(token.text)
                                + " is not supported; cells may hold only literal values before full FEEL");
                    }
                }
            }
            default -> throw unexpected(token, "a value");
        }
    }

    private static String stringArgument(Cursor cursor, String function) {
        cursor.expect("(");
        Token argument = cursor.next();
        if (argument.kind != Kind.STRING) {
            throw new UnsupportedException(function + "(...) needs a string literal");
        }
        cursor.expect(")");
        return argument.text;
    }

    private static UnsupportedException unexpected(Token token, String expected) {
        String found = token.kind == Kind.EOF ? "end of text" : Quote.of(token.text);
        return new UnsupportedException("expected " + expected + " at position " + (token.position + 1) + " but found "
                + found);
    }

    private enum Kind { NUMBER, STRING, NAME, PUNCT, EOF }

    private record Token(Kind kind, String text, int position) {
    }

    private static final class Cursor {
        private final List<Token> tokens;
        private int index;

        Cursor(String text) {
            if (text.length() > MAX_LENGTH) {
                throw new UnsupportedException("cell text longer than " + MAX_LENGTH + " characters");
            }
            this.tokens = lex(text);
        }

        Token peek() {
            return tokens.get(index);
        }

        Token peekAt(int offset) {
            return tokens.get(Math.min(index + offset, tokens.size() - 1));
        }

        boolean peekIs(String punct) {
            return peek().kind == Kind.PUNCT && peek().text.equals(punct);
        }

        boolean peekWord(String word) {
            return peek().kind == Kind.NAME && peek().text.equals(word);
        }

        Token next() {
            Token token = tokens.get(index);
            if (token.kind != Kind.EOF) {
                index++;
            }
            return token;
        }

        boolean accept(String punct) {
            if (peekIs(punct)) {
                next();
                return true;
            }
            return false;
        }

        void expect(String punct) {
            Token token = next();
            if (token.kind != Kind.PUNCT || !token.text.equals(punct)) {
                throw unexpected(token, "'" + punct + "'");
            }
        }

        void expectEnd() {
            Token token = peek();
            if (token.kind != Kind.EOF) {
                if (token.kind == Kind.NAME && (token.text.equals("and") || token.text.equals("or"))) {
                    throw new UnsupportedException("'" + token.text + "' is not supported in a cell; use a list or a range");
                }
                throw unexpected(token, "end of text");
            }
        }
    }

    private static List<Token> lex(String text) {
        List<Token> tokens = new ArrayList<>();
        int i = 0;
        int n = text.length();
        while (i < n) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (c >= '0' && c <= '9' || (c == '.' && i + 1 < n && isDigit(text.charAt(i + 1)))) {
                int start = i;
                while (i < n && isDigit(text.charAt(i))) {
                    i++;
                }
                if (i + 1 < n && text.charAt(i) == '.' && isDigit(text.charAt(i + 1))) {
                    i++;
                    while (i < n && isDigit(text.charAt(i))) {
                        i++;
                    }
                }
                String number = text.substring(start, i);
                tokens.add(new Token(Kind.NUMBER, number.startsWith(".") ? "0" + number : number, start));
            } else if (c == '"') {
                int start = i++;
                StringBuilder value = new StringBuilder();
                boolean closed = false;
                while (i < n) {
                    char s = text.charAt(i++);
                    if (s == '\\') {
                        if (i >= n) {
                            break;
                        }
                        char e = text.charAt(i++);
                        switch (e) {
                            case '"' -> value.append('"');
                            case '\\' -> value.append('\\');
                            case '\'' -> value.append('\'');
                            case 'n' -> value.append('\n');
                            case 'r' -> value.append('\r');
                            case 't' -> value.append('\t');
                            case 'u' -> {
                                if (i + 4 > n || !text.substring(i, i + 4).matches("[0-9A-Fa-f]{4}")) {
                                    throw new UnsupportedException("invalid \\u escape at position " + i);
                                }
                                value.append((char) Integer.parseInt(text.substring(i, i + 4), 16));
                                i += 4;
                            }
                            default -> throw new UnsupportedException("unknown escape " + Quote.of("\\" + e) + " at position " + (i - 1));
                        }
                    } else if (s == '"') {
                        closed = true;
                        break;
                    } else {
                        value.append(s);
                    }
                }
                if (!closed) {
                    throw new UnsupportedException("unterminated string at position " + (start + 1));
                }
                tokens.add(new Token(Kind.STRING, value.toString(), start));
            } else if (Character.isLetter(c) || c == '_') {
                int start = i;
                while (i < n && (Character.isLetterOrDigit(text.charAt(i)) || text.charAt(i) == '_')) {
                    i++;
                }
                tokens.add(new Token(Kind.NAME, text.substring(start, i), start));
            } else {
                String two = i + 1 < n ? text.substring(i, i + 2) : "";
                if (two.equals("<=") || two.equals(">=") || two.equals("!=") || two.equals("..")) {
                    tokens.add(new Token(Kind.PUNCT, two, i));
                    i += 2;
                } else if ("<>=()[],-?".indexOf(c) >= 0) {
                    tokens.add(new Token(Kind.PUNCT, String.valueOf(c), i));
                    i++;
                } else {
                    throw new UnsupportedException("character " + Quote.of(String.valueOf(c)) + " at position " + (i + 1)
                            + " is not supported before full FEEL");
                }
            }
        }
        tokens.add(new Token(Kind.EOF, "", n));
        return tokens;
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }
}
