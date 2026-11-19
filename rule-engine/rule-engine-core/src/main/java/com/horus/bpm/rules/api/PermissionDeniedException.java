package com.horus.bpm.rules.api;

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
}
