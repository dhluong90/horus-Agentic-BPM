package io.github.dhluong90.bpm.process.internal.expr;

/** A condition that does not parse, or an operand of the wrong type at run time. */
public final class ConditionException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** @param message the detail, with the position for parse errors */
    public ConditionException(String message) {
        super(message);
    }
}
