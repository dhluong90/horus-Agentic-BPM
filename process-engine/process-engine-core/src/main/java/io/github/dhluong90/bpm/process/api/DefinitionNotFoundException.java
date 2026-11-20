package io.github.dhluong90.bpm.process.api;

/** No such definition version in the caller's tenant. Code {@code DEFINITION_NOT_FOUND}. */
public final class DefinitionNotFoundException extends ProcessException {

    private static final long serialVersionUID = 1L;

    /** @param message the detail */
    public DefinitionNotFoundException(String message) {
        super("DEFINITION_NOT_FOUND", message);
    }
}
