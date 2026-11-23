package io.github.dhluong90.bpm.process.api;

/** The task's step declares no form. Code {@code FORM_NOT_DECLARED}. */
public final class FormNotDeclaredException extends ProcessException {

    private static final long serialVersionUID = 1L;

    /** @param message the detail */
    public FormNotDeclaredException(String message) {
        super("FORM_NOT_DECLARED", message);
    }
}
