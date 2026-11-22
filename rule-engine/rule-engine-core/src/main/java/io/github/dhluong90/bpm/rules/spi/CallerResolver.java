package io.github.dhluong90.bpm.rules.spi;

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

    /**
     * The readable name of a user in the caller's tenant and language, for lists that show who did something, such
     * as the requester in {@code GET /approvals} (#442). The starter's default asks the host's {@code DisplayNames}.
     * A label only: no check ever reads it.
     *
     * @param userId the user id as the engine recorded it
     * @return the name, or empty to show the id
     */
    default java.util.Optional<String> displayNameOf(String userId) {
        return java.util.Optional.empty();
    }
}
