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
}
