package io.github.dhluong90.bpm.rules.api;

/** {@code 403 PERMISSION_DENIED}: the caller lacks the permission the operation needs (ADR-0010). */
public final class PermissionDeniedException extends HorusBpmException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param permission the missing permission, for example {@code rules:approve}
     */
    public PermissionDeniedException(String permission) {
        super("PERMISSION_DENIED", "The caller lacks permission " + permission);
    }

    private PermissionDeniedException(String detail, boolean reserved) {
        super("PERMISSION_DENIED", detail);
    }

    /**
     * A caller whose id is in the namespace the engine reserves for its own actors ({@code system:}, such as the
     * supersession actor), so no host user can act, or appear in the audit trail, under the engine's name.
     *
     * @return the exception; it does not echo the caller id
     */
    public static PermissionDeniedException reservedCallerId() {
        return new PermissionDeniedException("Caller ids starting with system: are reserved for the engine's own"
                + " actors and cannot call the engine", true);
    }
}
