package com.horus.bpm.rules.spi;

import java.util.Set;

/**
 * Resolves the identity and permissions of the current caller from the host's authenticated context.
 * Must be thread-safe.
 */
public interface CallerResolver {

    /**
     * Returns the stable id of the current caller, used for maker-checker and the audit trail.
     *
     * @return the caller id, or null when the call is not authenticated
     */
    String currentCallerId();

    /**
     * Returns the engine permissions the caller holds, for example {@code rules:approve} (ADR-0010).
     *
     * @return the permissions; empty when none
     */
    Set<String> currentPermissions();
}
