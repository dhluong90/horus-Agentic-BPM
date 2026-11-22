package io.github.dhluong90.bpm.expr.internal;

import java.util.Objects;

/** An expression that cannot be parsed or type-checked, with the character position and the code of the problem. */
public final class ExpressionException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int position;

    private final ExpressionError error;

    private final String problem;

    private final SyntaxHint hint;

    ExpressionException(String message, int position) {
        this(ExpressionError.SYNTAX_ERROR, message, position);
    }

    ExpressionException(SyntaxHint hint, String message, int position) {
        super(message + " at position " + position);
        this.error = ExpressionError.SYNTAX_ERROR;
        this.problem = message;
        this.position = position;
        this.hint = Objects.requireNonNull(hint, "hint must not be null");
    }

    ExpressionException(ExpressionError error, String message, int position) {
        super(message + " at position " + position);
        this.error = Objects.requireNonNull(error, "error must not be null");
        this.problem = message;
        this.position = position;
        this.hint = null;
    }

    /**
     * Returns what a syntax error expected, for a sentence and an example from a message catalogue (#419).
     *
     * @return the hint, or null for an error that is not a syntax error or has no more to say
     */
    public SyntaxHint hint() {
        return hint;
    }

    /**
     * Returns the zero-based character position of the problem.
     *
     * @return the position
     */
    public int position() {
        return position;
    }

    /**
     * Returns why the expression is refused.
     *
     * @return the error code
     */
    public ExpressionError error() {
        return error;
    }

    /**
     * Returns the problem without its position, for a violation that carries the position separately.
     *
     * @return the message
     */
    public String problem() {
        return problem;
    }
}
