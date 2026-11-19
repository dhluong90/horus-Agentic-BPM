package io.github.dhluong90.bpm.process.api;

/** A stored definition no longer matches its recorded hashes; the version is not started or advanced. Code {@code DEFINITION_INTEGRITY_FAILED}. */
public final class DefinitionIntegrityException extends ProcessException {

    private static final long serialVersionUID = 1L;

    /** @param message the detail */
    public DefinitionIntegrityException(String message) {
        super("DEFINITION_INTEGRITY_FAILED", message);
    }
}
