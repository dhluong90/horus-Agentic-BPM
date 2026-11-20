package io.github.dhluong90.bpm.rules.internal.expr;

import java.util.Objects;

/** An expression that cannot be parsed or type-checked, with the character position and the code of the problem. */
public final class ExpressionException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int position;

    private final ExpressionError error;

    private final String problem;

    ExpressionException(String message, int position) {
        this(ExpressionError.SYNTAX_ERROR, message, position);
    }

    ExpressionException(ExpressionError error, String message, int position) {
        super(message + " at position " + position);
        this.error = Objects.requireNonNull(error, "error must not be null");
        this.problem = message;
        this.position = position;
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
