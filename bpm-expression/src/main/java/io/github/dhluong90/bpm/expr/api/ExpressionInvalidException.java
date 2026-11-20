package io.github.dhluong90.bpm.expr.api;

/** An expression text that does not compile: a syntax error, an unknown name or function, a literal out of range. */
public final class ExpressionInvalidException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int position;

    /**
     * @param message what is wrong, without the input values
     * @param position the zero-based character position, or -1 when the whole text is at fault
     */
    public ExpressionInvalidException(String message, int position) {
        super(message);
        this.position = position;
    }

    /** @return the zero-based character position, or -1 when the whole text is at fault */
    public int position() {
        return position;
    }
}
