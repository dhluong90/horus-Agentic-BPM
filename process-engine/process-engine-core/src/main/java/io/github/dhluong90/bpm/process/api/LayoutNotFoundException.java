package io.github.dhluong90.bpm.process.api;

/** The version has no such layout revision. Code {@code LAYOUT_NOT_FOUND}. */
public final class LayoutNotFoundException extends ProcessException {

    private static final long serialVersionUID = 1L;

    /** @param message the detail */
    public LayoutNotFoundException(String message) {
        super("LAYOUT_NOT_FOUND", message);
    }
}
