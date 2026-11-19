package io.github.dhluong90.bpm.process.api;

/** The caller's tenant differs from the tenant of the instance. Code {@code TENANT_NOT_ALLOWED}. */
public final class TenantNotAllowedException extends ProcessException {

    private static final long serialVersionUID = 1L;

    /** @param message the detail */
    public TenantNotAllowedException(String message) {
        super("TENANT_NOT_ALLOWED", message);
    }
}
