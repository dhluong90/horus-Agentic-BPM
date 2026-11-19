package io.github.dhluong90.bpm.rules.internal.expr;

/** An expression that cannot be parsed, with the character position of the problem. */
public final class ExpressionException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int position;

    ExpressionException(String message, int position) {
        super(message + " at position " + position);
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
}
