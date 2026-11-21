package io.github.dhluong90.bpm.process.api;

/** No such incident in the caller's tenant, also when another tenant has it. Code {@code INCIDENT_NOT_FOUND}. */
public final class IncidentNotFoundException extends ProcessException {

    private static final long serialVersionUID = 1L;

    /** @param message the detail */
    public IncidentNotFoundException(String message) {
        super("INCIDENT_NOT_FOUND", message);
    }
}
