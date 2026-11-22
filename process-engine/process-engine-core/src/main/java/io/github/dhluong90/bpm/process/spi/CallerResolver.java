package io.github.dhluong90.bpm.process.spi;

import java.util.Set;

/**
 * Resolves the caller of the current call. Must be thread-safe.
 */
public interface CallerResolver {

    /**
     * @return the caller id, or null when not authenticated
     */
    String currentCallerId();

    /**
     * @return the engine permissions ({@code processes:*}) the caller holds
     */
    Set<String> currentPermissions();

    /**
     * @return the caller's role names without a {@code ROLE_} prefix, matched against {@code role:} assignees
     */
    default Set<String> currentRoles() {
        return Set.of();
    }

    /**
     * @return the caller's groups, matched against {@code group:} assignees
     */
    default Set<String> currentGroups() {
        return Set.of();
    }

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
