package io.github.dhluong90.bpm.expr.feel;

/** Thrown by every contract stub until its issue replaces it. Names the part and the issue, so a missing piece is never silent. */
public final class FeelNotImplementedException extends UnsupportedOperationException {

    private static final long serialVersionUID = 1L;

    /**
     * @param part the contract part, for example {@code FeelParser}
     * @param issue the issue that implements it, for example {@code #197}
     */
    public FeelNotImplementedException(String part, String issue) {
        super("FEEL " + part + " is not yet implemented (" + issue + ")");
    }
}
