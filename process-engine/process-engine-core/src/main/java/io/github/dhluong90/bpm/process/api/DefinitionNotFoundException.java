package io.github.dhluong90.bpm.process.api;

/** No definition version with this key and number in the caller's tenant. Code {@code DEFINITION_NOT_FOUND}. */
public final class DefinitionNotFoundException extends ProcessException {

    private static final long serialVersionUID = 1L;

    /** @param message the detail */
    public DefinitionNotFoundException(String message) {
        super("DEFINITION_NOT_FOUND", message);
    }
}
