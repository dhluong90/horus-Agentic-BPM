package io.github.dhluong90.bpm.expr.internal;

import io.github.dhluong90.bpm.expr.api.Money;
import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;

/**
 * Hand-written lexer and recursive-descent parser of {@code urn:horus-bpm:expr:v1} (ADR-0003). No
 * reflection, no scripting; the only calls are the constant literals {@code date('...')} and
 * {@code datetime('...')}, the clock readings {@code today()} and {@code now()}, which take no argument and are
 * bound to the slots the resolver names {@code today()} and {@code now()} (-1 when it has none), the bounded
 * regular expression search {@code matches(text, 'pattern')}, whose pattern is a string literal compiled here,
 * once (TR-SEC-02), and the closed set of pure functions {@code round} and {@code daysBetween}. In a condition
 * cell, {@code matches('pattern')} tests the cell's own input. Identifiers are bound to input slots at parse time;
 * an unknown identifier or function is a parse error.
 *
 * <pre>
 * or         = and { "or" and }
 * and        = not { "and" not }
 * not        = "not" not | comparison
 * comparison = additive [ ("=="|"!="|"<"|"<="|">"|">=") additive
 *                       | ["not"] "in" list | "is" ["not"] "null" | "between" additive "and" additive ]
 * additive   = multiplicative { ("+"|"-") multiplicative }
 * multiplicative = unary { ("*"|"/") unary }
 * unary      = "-" unary | primary
 * primary    = number [CURRENCY] | string | "true" | "false" | "null" | identifier | "(" or ")" | list
 *            | "date" "(" string ")" | "datetime" "(" string ")" | "today" "(" ")" | "now" "(" ")"
 *            | "matches" "(" [ or "," ] string ")" | call
 * call       = ("round" | "daysBetween") "(" or "," or ")"
 * list       = "[" [ or { "," or } ] "]"
 * </pre>
 */
public final class Parser {

    /** Longest expression accepted, in characters. */
    public static final int MAX_LENGTH = 4096;

    /** Deepest nesting accepted. */
    public static final int MAX_DEPTH = 64;

    /** Largest scale of {@code round}, either way: the 34 digits of {@code DECIMAL128}. */
    public static final int MAX_SCALE = 34;

    private final String source;
    private final ToIntFunction<String> slots;
    private final String column;
    private final List<Token> tokens;
    private int index;
    private int depth;

    private Parser(String source, ToIntFunction<String> slots, String column, boolean paths) {
        this.source = source;
        this.slots = slots;
        this.column = column;
        this.tokens = lex(source, paths);
    }

    /**
     * Parses an expression.
     *
     * @param source the expression text
     * @param slots maps an input name to its slot index, or -1 when there is no such input
     * @return the tree
     * @throws ExpressionException when the text is not a valid expression
     */
    public static Expr parse(String source, ToIntFunction<String> slots) {
        return parse(source, slots, null);
    }

    /**
     * Parses the expression of a condition cell, where {@code matches('pattern')} with one argument tests the
     * cell's own input.
     *
     * @param source the expression text
     * @param slots maps an input name to its slot index, or -1 when there is no such input
     * @param column the input the cell is keyed by, or null outside a condition cell
     * @return the tree
     * @throws ExpressionException when the text is not a valid expression
     */
    public static Expr parse(String source, ToIntFunction<String> slots, String column) {
        return parse(source, slots, column, false);
    }

    /**
     * Parses an expression whose names may be dotted paths, such as {@code customer.tier}: each path is one name,
     * resolved through {@code slots} like any other. Used for names typed only at evaluation (process variables).
     *
     * @param source the expression text
     * @param slots maps a name or path to its slot index, or -1 when there is no such name
     * @return the tree
     * @throws ExpressionException when the text is not a valid expression
     */
    public static Expr parseWithPaths(String source, ToIntFunction<String> slots) {
        return parse(source, slots, null, true);
    }

    private static Expr parse(String source, ToIntFunction<String> slots, String column, boolean paths) {
        if (source.length() > MAX_LENGTH) {
            throw new ExpressionException("Expression longer than " + MAX_LENGTH + " characters", MAX_LENGTH);
        }
        Parser parser = new Parser(source, slots, column, paths);
        Expr expr = parser.or();
        Token rest = parser.peek();
        if (rest.kind() != Kind.EOF) {
            throw new ExpressionException("Unexpected '" + rest.text() + "'", rest.position());
        }
        return expr;
    }

    private Expr or() {
        enter();
        Expr left = and();
        while (acceptWord("or")) {
            left = new Expr.Binary(Expr.BinaryOp.OR, left, and());
        }
        leave();
        return left;
    }

    private Expr and() {
        Expr left = not();
        while (acceptWord("and")) {
            left = new Expr.Binary(Expr.BinaryOp.AND, left, not());
        }
        return left;
    }

    private Expr not() {
        if (peekWord("not") && !peekWordAt(1, "in")) {
            next();
            enter();
            Expr operand = not();
            leave();
            return new Expr.Unary(Expr.UnaryOp.NOT, operand);
        }
        return comparison();
    }

    private Expr comparison() {
        Expr left = additive();
        Token token = peek();
        if (token.kind() == Kind.OP) {
            Expr.BinaryOp op = switch (token.text()) {
                case "==" -> Expr.BinaryOp.EQ;
                case "!=" -> Expr.BinaryOp.NE;
                case "<" -> Expr.BinaryOp.LT;
                case "<=" -> Expr.BinaryOp.LE;
                case ">" -> Expr.BinaryOp.GT;
                case ">=" -> Expr.BinaryOp.GE;
                default -> null;
            };
            if (op != null) {
                next();
                return new Expr.Binary(op, left, additive());
            }
        }
        if (peekWord("not") && peekWordAt(1, "in")) {
            next();
            next();
            return new Expr.InList(left, list(), true);
        }
        if (acceptWord("in")) {
            return new Expr.InList(left, list(), false);
        }
        if (acceptWord("is")) {
            boolean negated = acceptWord("not");
            expectWord("null");
            return new Expr.IsNull(left, negated);
        }
        if (acceptWord("between")) {
            Expr low = additive();
            expectWord("and");
            return new Expr.Between(left, low, additive());
        }
        return left;
    }

    private Expr additive() {
        Expr left = multiplicative();
        while (true) {
            if (acceptOp("+")) {
                left = new Expr.Binary(Expr.BinaryOp.ADD, left, multiplicative());
            } else if (acceptOp("-")) {
                left = new Expr.Binary(Expr.BinaryOp.SUB, left, multiplicative());
            } else {
                return left;
            }
        }
    }

    private Expr multiplicative() {
        Expr left = unary();
        while (true) {
            if (acceptOp("*")) {
                left = new Expr.Binary(Expr.BinaryOp.MUL, left, unary());
            } else if (acceptOp("/")) {
                left = new Expr.Binary(Expr.BinaryOp.DIV, left, unary());
            } else {
                return left;
            }
        }
    }

    private Expr unary() {
        if (acceptOp("-")) {
            enter();
            Expr operand = unary();
            leave();
            if (operand instanceof Expr.Literal literal && literal.value() instanceof BigDecimal number) {
                return new Expr.Literal(number.negate());
            }
            if (operand instanceof Expr.Literal literal && literal.value() instanceof Money money) {
                return new Expr.Literal(Money.ofMinor(money.currency(), Math.negateExact(money.minorUnits())));
            }
            return new Expr.Unary(Expr.UnaryOp.NEGATE, operand);
        }
        return primary();
    }

    private Expr primary() {
        Token token = next();
        switch (token.kind()) {
            case NUMBER -> {
                BigDecimal number = new BigDecimal(token.text());
                Token after = peek();
                if (after.kind() == Kind.IDENT && after.text().matches("[A-Z]{3}") && slots.applyAsInt(after.text()) < 0) {
                    next();
                    try {
                        return new Expr.Literal(Money.ofMajor(after.text(), number.toPlainString()));
                    } catch (IllegalArgumentException | ArithmeticException e) {
                        throw new ExpressionException("Invalid money literal: " + e.getMessage(), token.position());
                    }
                }
                return new Expr.Literal(number);
            }
            case STRING -> {
                return new Expr.Literal(token.text());
            }
            case IDENT -> {
                switch (token.text()) {
                    case "true" -> {
                        return new Expr.Literal(Boolean.TRUE);
                    }
                    case "false" -> {
                        return new Expr.Literal(Boolean.FALSE);
                    }
                    case "null" -> {
                        return new Expr.Literal(null);
                    }
                    default -> {
                        if (peek().kind() == Kind.LPAREN && (token.text().equals("date") || token.text().equals("datetime"))) {
                            return temporal(token);
                        }
                        if (peek().kind() == Kind.LPAREN && (token.text().equals("today") || token.text().equals("now"))) {
                            return clock(token);
                        }
                        if (peek().kind() == Kind.LPAREN && token.text().equals("matches")) {
                            return matches(token);
                        }
                        if (peek().kind() == Kind.LPAREN) {
                            return call(token);
                        }
                        int slot = slots.applyAsInt(token.text());
                        if (slot < 0) {
                            throw new ExpressionException("Unknown input '" + token.text() + "'", token.position());
                        }
                        return new Expr.Var(slot, token.text());
                    }
                }
            }
            case LPAREN -> {
                Expr inner = or();
                expect(Kind.RPAREN, ")");
                return inner;
            }
            case LBRACK -> throw new ExpressionException(
                    "List values are allowed only after 'in' in this release", token.position());
            default -> throw new ExpressionException(
                    token.kind() == Kind.EOF ? "Unexpected end of expression" : "Unexpected '" + token.text() + "'",
                    token.position());
        }
    }

    /**
     * {@code date('2026-12-01')} or {@code datetime('2026-12-01T00:00:00Z')} with a string literal argument: a constant,
     * checked here, so a malformed date fails at import and never at evaluation (standards-compliance.md 4.4).
     */
    private Expr temporal(Token function) {
        expect(Kind.LPAREN, "(");
        Token argument = next();
        if (argument.kind() != Kind.STRING) {
            throw new ExpressionException(function.text() + "(...) takes one string literal", argument.position());
        }
        expect(Kind.RPAREN, ")");
        try {
            return new Expr.Literal(function.text().equals("date")
                    ? LocalDate.parse(argument.text())
                    : OffsetDateTime.parse(argument.text()).toInstant());
        } catch (DateTimeException e) {
            throw new ExpressionException("Invalid " + function.text() + " literal '" + argument.text() + "'",
                    argument.position());
        }
    }

    /** A call of {@code round} or {@code daysBetween}; any other name is refused. */
    private Expr call(Token name) {
        Expr.Function function = null;
        for (Expr.Function candidate : Expr.Function.values()) {
            if (candidate.text().equals(name.text())) {
                function = candidate;
            }
        }
        if (function == null) {
            throw new ExpressionException("Unknown function '" + name.text() + "'", name.position());
        }
        expect(Kind.LPAREN, "(");
        List<Expr> args = new ArrayList<>();
        if (peek().kind() != Kind.RPAREN) {
            do {
                args.add(or());
            } while (accept(Kind.COMMA));
        }
        expect(Kind.RPAREN, ")");
        if (args.size() != 2) {
            throw new ExpressionException(name.text() + " takes 2 arguments, found " + args.size(), name.position());
        }
        if (function == Expr.Function.ROUND && args.get(1) instanceof Expr.Literal literal
                && literal.value() instanceof BigDecimal scale && !isScale(scale)) {
            throw new ExpressionException("round: the scale must be a whole number from -" + MAX_SCALE + " to "
                    + MAX_SCALE + ", found " + scale.toPlainString(), name.position());
        }
        return new Expr.Call(function, args);
    }

    /**
     * Tells whether a number is a scale {@code round} accepts: a whole number within plus or minus {@value #MAX_SCALE}.
     *
     * @param scale the number
     * @return true when it is
     */
    public static boolean isScale(BigDecimal scale) {
        return (scale.signum() == 0 || scale.stripTrailingZeros().scale() <= 0)
                && scale.abs().compareTo(BigDecimal.valueOf(MAX_SCALE)) <= 0;
    }

    /** {@code today()} or {@code now()}: no argument, bound to the clock slot the resolver gives. */
    private Expr clock(Token function) {
        expect(Kind.LPAREN, "(");
        Token close = peek();
        if (close.kind() != Kind.RPAREN) {
            throw new ExpressionException(function.text() + "() takes no argument", close.position());
        }
        next();
        Expr.ClockFunction which = function.text().equals("today") ? Expr.ClockFunction.TODAY : Expr.ClockFunction.NOW;
        return new Expr.Clock(which, slots.applyAsInt(which.slotName()));
    }

    /**
     * {@code matches(text, 'pattern')}, or {@code matches('pattern')} in a condition cell, testing the cell's input.
     * The pattern must be a string literal, so it is checked and compiled here and never at evaluation.
     */
    private Expr matches(Token function) {
        expect(Kind.LPAREN, "(");
        enter();
        Expr first = or();
        Expr text;
        Token pattern;
        if (accept(Kind.COMMA)) {
            text = first;
            pattern = next();
            if (pattern.kind() != Kind.STRING) {
                throw new ExpressionException("matches(text, pattern) takes the pattern as a string literal",
                        pattern.position());
            }
        } else {
            if (!(first instanceof Expr.Literal literal && literal.value() instanceof String)) {
                throw new ExpressionException("matches(text, pattern) takes the pattern as a string literal",
                        function.position());
            }
            if (column == null) {
                throw new ExpressionException("matches('pattern') needs the text to test: write matches(input, "
                        + "'pattern')", function.position());
            }
            int slot = slots.applyAsInt(column);
            if (slot < 0) {
                throw new ExpressionException("Unknown input '" + column + "'", function.position());
            }
            text = new Expr.Var(slot, column);
            pattern = new Token(Kind.STRING, (String) ((Expr.Literal) first).value(), function.position());
        }
        expect(Kind.RPAREN, ")");
        leave();
        return new Expr.Matches(text, pattern.text(), BoundedRegex.compile(pattern.text(), pattern.position()));
    }

    private List<Expr> list() {
        expect(Kind.LBRACK, "[");
        List<Expr> elements = new ArrayList<>();
        if (peek().kind() != Kind.RBRACK) {
            do {
                elements.add(or());
            } while (accept(Kind.COMMA));
        }
        expect(Kind.RBRACK, "]");
        return elements;
    }

    private void enter() {
        if (++depth > MAX_DEPTH) {
            throw new ExpressionException("Expression nested deeper than " + MAX_DEPTH, peek().position());
        }
    }

    private void leave() {
        depth--;
    }

    private Token peek() {
        return tokens.get(index);
    }

    private Token next() {
        Token token = tokens.get(index);
        if (token.kind() != Kind.EOF) {
            index++;
        }
        return token;
    }

    private boolean accept(Kind kind) {
        if (peek().kind() == kind) {
            next();
            return true;
        }
        return false;
    }

    private void expect(Kind kind, String text) {
        Token token = peek();
        if (token.kind() != kind) {
            throw new ExpressionException("Expected '" + text + "'", token.position());
        }
        next();
    }

    private boolean acceptOp(String op) {
        if (peek().kind() == Kind.OP && peek().text().equals(op)) {
            next();
            return true;
        }
        return false;
    }

    private boolean peekWord(String word) {
        return peekWordAt(0, word);
    }

    private boolean peekWordAt(int offset, String word) {
        int at = Math.min(index + offset, tokens.size() - 1);
        Token token = tokens.get(at);
        return token.kind() == Kind.IDENT && token.text().equals(word);
    }

    private boolean acceptWord(String word) {
        if (peekWord(word)) {
            next();
            return true;
        }
        return false;
    }

    private void expectWord(String word) {
        if (!acceptWord(word)) {
            throw new ExpressionException("Expected '" + word + "'", peek().position());
        }
    }

    private enum Kind { NUMBER, STRING, IDENT, OP, LPAREN, RPAREN, LBRACK, RBRACK, COMMA, EOF }

    private record Token(Kind kind, String text, int position) {
    }

    private static boolean isAsciiLetter(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    private static boolean isHex(String source, int from) {
        for (int k = from; k < from + 4; k++) {
            if (Character.digit(source.charAt(k), 16) < 0) {
                return false;
            }
        }
        return true;
    }

    private static boolean isAsciiDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static List<Token> lex(String source, boolean paths) {
        List<Token> tokens = new ArrayList<>();
        int i = 0;
        int n = source.length();
        while (i < n) {
            char c = source.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (c >= '0' && c <= '9') {
                int start = i;
                while (i < n && isAsciiDigit(source.charAt(i))) {
                    i++;
                }
                if (i + 1 < n && source.charAt(i) == '.' && isAsciiDigit(source.charAt(i + 1))) {
                    i++;
                    while (i < n && isAsciiDigit(source.charAt(i))) {
                        i++;
                    }
                }
                if (i < n && Character.isDigit(source.charAt(i))) {
                    throw new ExpressionException("Only the digits 0 to 9 are allowed in numbers", i);
                }
                tokens.add(new Token(Kind.NUMBER, source.substring(start, i), start));
            } else if (c == '\'') {
                int start = i++;
                StringBuilder text = new StringBuilder();
                boolean closed = false;
                while (i < n) {
                    char s = source.charAt(i++);
                    if (s == '\\' && i < n) {
                        char escaped = source.charAt(i++);
                        if (escaped == 'u') {
                            // \\uXXXX: how the canonical printer writes control and format characters (ADR-0026)
                            if (i + 4 > n || !isHex(source, i)) {
                                throw new ExpressionException("Invalid \\u escape, expected four hexadecimal digits", i - 2);
                            }
                            text.append((char) Integer.parseInt(source.substring(i, i + 4), 16));
                            i += 4;
                            continue;
                        }
                        if (escaped != '\'' && escaped != '\\') {
                            throw new ExpressionException("Unknown escape '\\" + escaped + "'", i - 2);
                        }
                        text.append(escaped);
                    } else if (s == '\'') {
                        closed = true;
                        break;
                    } else {
                        text.append(s);
                    }
                }
                if (!closed) {
                    throw new ExpressionException("Unterminated string", start);
                }
                tokens.add(new Token(Kind.STRING, text.toString(), start));
            } else if (isAsciiLetter(c) || c == '_') {
                int start = i;
                while (i < n && (isAsciiLetter(source.charAt(i)) || isAsciiDigit(source.charAt(i)) || source.charAt(i) == '_'
                        || (paths && source.charAt(i) == '.' && i + 1 < n
                                && (isAsciiLetter(source.charAt(i + 1)) || source.charAt(i + 1) == '_')))) {
                    i++;
                }
                tokens.add(new Token(Kind.IDENT, source.substring(start, i), start));
            } else {
                int start = i;
                String two = i + 1 < n ? source.substring(i, i + 2) : "";
                switch (two) {
                    case "==", "!=", "<=", ">=" -> {
                        tokens.add(new Token(Kind.OP, two, start));
                        i += 2;
                        continue;
                    }
                    default -> {
                        // single character below
                    }
                }
                Kind kind = switch (c) {
                    case '<', '>', '+', '-', '*', '/' -> Kind.OP;
                    case '(' -> Kind.LPAREN;
                    case ')' -> Kind.RPAREN;
                    case '[' -> Kind.LBRACK;
                    case ']' -> Kind.RBRACK;
                    case ',' -> Kind.COMMA;
                    default -> throw new ExpressionException("Unexpected character '" + c + "'", start);
                };
                tokens.add(new Token(kind, String.valueOf(c), start));
                i++;
            }
        }
        tokens.add(new Token(Kind.EOF, "", n));
        return tokens;
    }
}
