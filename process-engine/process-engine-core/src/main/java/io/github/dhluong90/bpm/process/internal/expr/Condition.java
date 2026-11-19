package io.github.dhluong90.bpm.process.internal.expr;

import io.github.dhluong90.bpm.rules.api.Money;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Compiled sequence-flow condition in the comparison subset of {@code urn:horus-bpm:expr:v1}.
 *
 * <pre>
 * expr    := or
 * or      := and ("or" and)*
 * and     := unary ("and" unary)*
 * unary   := "not" unary | compare
 * compare := operand (("==" | "!=" | "&lt;" | "&lt;=" | "&gt;" | "&gt;=") operand | "is" ["not"] "null")?
 * operand := number [CURRENCY] | 'text' | true | false | null | path | "(" expr ")"
 * path    := identifier ("." identifier)*
 * </pre>
 *
 * <p>Paths read instance variables; {@code 50000 VND} is a money literal in major units. Semantics follow the Rule
 * Engine's (RLE-006, PD-058): null follows FEEL three-valued logic, so an ordering with a null operand is null,
 * {@code ==} and {@code !=} are two-valued ({@code null != 'GOLD'} is true), {@code and}, {@code or} and
 * {@code not} follow the FEEL truth tables, and {@code x is null} is always true or false. A condition holds only
 * when it is {@code true}; null does not hold. Numbers are {@code BigDecimal} in {@code DECIMAL128}; money compares
 * only with money of the same currency. A type error (text against a number, two currencies) is a
 * {@link ConditionException} at run time, never a silent {@code false}. Parsed once at import; evaluated
 * without reflection or allocation beyond the operands. A full shared {@code ExpressionLanguage} SPI from the
 * Rule Engine replaces this class when it is published (DESIGN section 2, DN-05).
 */
public final class Condition {

    /** Longest condition text accepted. */
    public static final int MAX_LENGTH = 1000;
    /** Deepest nesting accepted. */
    public static final int MAX_DEPTH = 32;

    private final String source;
    private final Node root;

    private Condition(String source, Node root) {
        this.source = source;
        this.root = root;
    }

    /**
     * Parses a condition.
     *
     * @param source the text
     * @return the compiled condition
     * @throws ConditionException with the position when the text is not valid
     */
    public static Condition parse(String source) {
        Objects.requireNonNull(source, "source must not be null");
        if (source.isBlank() || source.length() > MAX_LENGTH) {
            throw new ConditionException("A condition must have 1 to " + MAX_LENGTH + " characters");
        }
        Parser parser = new Parser(tokenize(source));
        Node root = parser.or(0);
        parser.expectEnd();
        return new Condition(source, root);
    }

    /**
     * Evaluates against instance variables.
     *
     * @param variables the instance variables
     * @return the boolean result
     * @throws ConditionException when an operand has the wrong type or the result is not boolean
     */
    public boolean test(Map<String, Object> variables) {
        Object value = root.eval(variables);
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        throw new ConditionException("Condition '" + source + "' did not produce a boolean");
    }

    /** @return the source text */
    public String source() {
        return source;
    }

    // ---- AST ----------------------------------------------------------------------------------------------

    private sealed interface Node permits Literal, Path, Not, Logical, Compare, IsNull {
        Object eval(Map<String, Object> variables);
    }

    private record Literal(Object value) implements Node {
        @Override
        public Object eval(Map<String, Object> variables) {
            return value;
        }
    }

    private record Path(List<String> parts) implements Node {
        @Override
        public Object eval(Map<String, Object> variables) {
            Object current = variables;
            for (String part : parts) {
                if (current instanceof Map<?, ?> map) {
                    current = map.get(part);
                } else {
                    return null;
                }
            }
            return normalize(current);
        }
    }

    private record Not(Node operand) implements Node {
        @Override
        public Object eval(Map<String, Object> variables) {
            Boolean value = bool(operand.eval(variables), "not");
            return value == null ? null : !value;
        }
    }

    private record IsNull(Node operand, boolean negated) implements Node {
        @Override
        public Object eval(Map<String, Object> variables) {
            return (operand.eval(variables) == null) != negated;
        }
    }

    private record Logical(boolean and, Node left, Node right) implements Node {
        @Override
        public Object eval(Map<String, Object> variables) {
            // FEEL: false dominates and, true dominates or; otherwise any null gives null.
            Boolean l = bool(left.eval(variables), and ? "and" : "or");
            if (l != null && l != and) {
                return l;
            }
            Boolean r = bool(right.eval(variables), and ? "and" : "or");
            if (r != null && r != and) {
                return r;
            }
            return l == null || r == null ? null : and;
        }
    }

    private record Compare(String op, Node left, Node right) implements Node {
        @Override
        public Object eval(Map<String, Object> variables) {
            Object l = left.eval(variables);
            Object r = right.eval(variables);
            if (op.equals("==") || op.equals("!=")) {
                boolean equal = equal(l, r);
                return op.equals("==") == equal;
            }
            if (l == null || r == null) {
                return null;
            }
            int c = order(l, r, op);
            return switch (op) {
                case "<" -> c < 0;
                case "<=" -> c <= 0;
                case ">" -> c > 0;
                default -> c >= 0;
            };
        }
    }

    private static boolean equal(Object l, Object r) {
        if (l == null || r == null) {
            return l == r;
        }
        if (l instanceof Money || r instanceof Money || l instanceof BigDecimal || r instanceof BigDecimal) {
            return order(l, r, "==") == 0;
        }
        if (l.getClass() != r.getClass()) {
            throw new ConditionException("Cannot compare " + kind(l) + " with " + kind(r));
        }
        return l.equals(r);
    }

    private static int order(Object l, Object r, String op) {
        if (l instanceof BigDecimal a && r instanceof BigDecimal b) {
            return a.compareTo(b);
        }
        if (l instanceof Money a && r instanceof Money b) {
            if (!a.currency().equals(b.currency())) {
                throw new ConditionException("Cannot compare " + a.currency() + " with " + b.currency());
            }
            return Long.compare(a.minorUnits(), b.minorUnits());
        }
        throw new ConditionException("Operator " + op + " needs two numbers or two money values of one currency, got "
                + kind(l) + " and " + kind(r));
    }

    private static Boolean bool(Object value, String op) {
        if (value == null || value instanceof Boolean) {
            return (Boolean) value;
        }
        throw new ConditionException("Operator " + op + " needs booleans, got " + kind(value));
    }

    private static String kind(Object value) {
        return switch (value) {
            case null -> "null";
            case Money m -> "money";
            case BigDecimal d -> "number";
            case String s -> "text";
            case Boolean b -> "boolean";
            default -> "a " + value.getClass().getSimpleName();
        };
    }

    private static Object normalize(Object value) {
        return switch (value) {
            case Long l -> BigDecimal.valueOf(l);
            case Integer i -> BigDecimal.valueOf(i);
            case BigInteger i -> new BigDecimal(i, java.math.MathContext.DECIMAL128);
            case BigDecimal d -> d.round(java.math.MathContext.DECIMAL128);
            case null, default -> value;
        };
    }

    // ---- lexer and parser ---------------------------------------------------------------------------------

    private record Token(String kind, String text, int position) {
    }

    private static List<Token> tokenize(String s) {
        List<Token> tokens = new ArrayList<>();
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (c == '\'') {
                int end = s.indexOf('\'', i + 1);
                if (end < 0) {
                    throw new ConditionException("Unterminated text at position " + i);
                }
                tokens.add(new Token("text", s.substring(i + 1, end), i));
                i = end + 1;
            } else if (Character.isDigit(c) || (c == '-' && i + 1 < s.length() && Character.isDigit(s.charAt(i + 1)))) {
                int start = i++;
                while (i < s.length() && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '.')) {
                    i++;
                }
                tokens.add(new Token("number", s.substring(start, i), start));
            } else if (Character.isLetter(c) || c == '_') {
                int start = i++;
                while (i < s.length() && (Character.isLetterOrDigit(s.charAt(i)) || s.charAt(i) == '_')) {
                    i++;
                }
                tokens.add(new Token("ident", s.substring(start, i), start));
            } else if ("=!<>".indexOf(c) >= 0) {
                String two = i + 1 < s.length() ? s.substring(i, i + 2) : "";
                if (two.equals("==") || two.equals("!=") || two.equals("<=") || two.equals(">=")) {
                    tokens.add(new Token("op", two, i));
                    i += 2;
                } else if (c == '<' || c == '>') {
                    tokens.add(new Token("op", String.valueOf(c), i++));
                } else {
                    throw new ConditionException("Unexpected '" + c + "' at position " + i);
                }
            } else if (c == '(' || c == ')' || c == '.') {
                tokens.add(new Token(String.valueOf(c), String.valueOf(c), i++));
            } else {
                throw new ConditionException("Unexpected '" + c + "' at position " + i);
            }
        }
        return tokens;
    }

    private static final class Parser {
        private final List<Token> tokens;
        private int at;

        Parser(List<Token> tokens) {
            this.tokens = tokens;
        }

        Node or(int depth) {
            Node left = and(depth);
            while (keyword("or")) {
                left = new Logical(false, left, and(depth));
            }
            return left;
        }

        Node and(int depth) {
            Node left = unary(depth);
            while (keyword("and")) {
                left = new Logical(true, left, unary(depth));
            }
            return left;
        }

        Node unary(int depth) {
            if (depth > MAX_DEPTH) {
                throw new ConditionException("Condition nested deeper than " + MAX_DEPTH);
            }
            if (keyword("not")) {
                return new Not(unary(depth + 1));
            }
            Node left = operand(depth);
            if (keyword("is")) {
                boolean negated = keyword("not");
                Token nul = take("'null'");
                if (!nul.kind().equals("ident") || !nul.text().equals("null")) {
                    throw new ConditionException("Expected 'null' after 'is' at position " + nul.position());
                }
                return new IsNull(left, negated);
            }
            Token next = peek();
            if (next != null && next.kind().equals("op")) {
                at++;
                return new Compare(next.text(), left, operand(depth));
            }
            return left;
        }

        Node operand(int depth) {
            Token t = take("an operand");
            switch (t.kind()) {
                case "(" -> {
                    Node inner = or(depth + 1);
                    Token close = take("')'");
                    if (!close.kind().equals(")")) {
                        throw new ConditionException("Expected ')' at position " + close.position());
                    }
                    return inner;
                }
                case "text" -> {
                    return new Literal(t.text());
                }
                case "number" -> {
                    Token next = peek();
                    if (next != null && next.kind().equals("ident") && next.text().matches("[A-Z]{3}")) {
                        at++;
                        try {
                            return new Literal(Money.ofMajor(next.text(), t.text()));
                        } catch (IllegalArgumentException | ArithmeticException e) {
                            throw new ConditionException("Invalid money literal at position " + t.position() + ": "
                                    + e.getMessage());
                        }
                    }
                    try {
                        return new Literal(new BigDecimal(t.text(), java.math.MathContext.DECIMAL128));
                    } catch (NumberFormatException e) {
                        throw new ConditionException("Invalid number at position " + t.position());
                    }
                }
                case "ident" -> {
                    switch (t.text()) {
                        case "true" -> {
                            return new Literal(Boolean.TRUE);
                        }
                        case "false" -> {
                            return new Literal(Boolean.FALSE);
                        }
                        case "null" -> {
                            return new Literal(null);
                        }
                        case "and", "or", "not", "is" -> throw new ConditionException("Unexpected '" + t.text()
                                + "' at position " + t.position());
                        default -> {
                            List<String> parts = new ArrayList<>(List.of(t.text()));
                            while (peek() != null && peek().kind().equals(".")) {
                                at++;
                                Token part = take("a name after '.'");
                                if (!part.kind().equals("ident")) {
                                    throw new ConditionException("Expected a name at position " + part.position());
                                }
                                parts.add(part.text());
                            }
                            return new Path(List.copyOf(parts));
                        }
                    }
                }
                default -> throw new ConditionException("Unexpected '" + t.text() + "' at position " + t.position());
            }
        }

        boolean keyword(String word) {
            Token t = peek();
            if (t != null && t.kind().equals("ident") && t.text().equals(word)) {
                at++;
                return true;
            }
            return false;
        }

        Token peek() {
            return at < tokens.size() ? tokens.get(at) : null;
        }

        Token take(String expected) {
            if (at >= tokens.size()) {
                throw new ConditionException("Expected " + expected + " at the end of the condition");
            }
            return tokens.get(at++);
        }

        void expectEnd() {
            if (at < tokens.size()) {
                throw new ConditionException("Unexpected '" + tokens.get(at).text() + "' at position "
                        + tokens.get(at).position());
            }
        }
    }
}
