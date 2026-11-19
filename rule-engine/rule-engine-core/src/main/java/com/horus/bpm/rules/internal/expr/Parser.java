package com.horus.bpm.rules.internal.expr;

import com.horus.bpm.rules.api.Money;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;

/**
 * Hand-written lexer and recursive-descent parser of {@code urn:horus-bpm:expr:v1} (ADR-0003). No
 * reflection, no scripting, no function calls in this release. Identifiers are bound to input slots
 * at parse time; an unknown identifier is a parse error.
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
 * list       = "[" [ or { "," or } ] "]"
 * </pre>
 */
public final class Parser {

    /** Longest expression accepted, in characters. */
    public static final int MAX_LENGTH = 4096;

    /** Deepest nesting accepted. */
    public static final int MAX_DEPTH = 64;

    private final String source;
    private final ToIntFunction<String> slots;
    private final List<Token> tokens;
    private int index;
    private int depth;

    private Parser(String source, ToIntFunction<String> slots) {
        this.source = source;
        this.slots = slots;
        this.tokens = lex(source);
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
        if (source.length() > MAX_LENGTH) {
            throw new ExpressionException("Expression longer than " + MAX_LENGTH + " characters", MAX_LENGTH);
        }
        Parser parser = new Parser(source, slots);
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
                        if (peek().kind() == Kind.LPAREN) {
                            throw new ExpressionException("Unknown function '" + token.text() + "'", token.position());
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

    private static boolean isAsciiDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static List<Token> lex(String source) {
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
                while (i < n && (isAsciiLetter(source.charAt(i)) || isAsciiDigit(source.charAt(i)) || source.charAt(i) == '_')) {
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
