package io.github.dhluong90.bpm.process.spi;

/**
 * Answers whether a named user could work a role or group task, for {@code reassign} and {@code delegate}
 * (PD-110, DESIGN section 3.5). The engine knows the caller's roles and groups from the {@link CallerResolver}, but
 * not another person's; the host's user directory does. Implementations must be thread-safe, must answer for the
 * given tenant only and must not throw: a user the directory does not know holds nothing.
 *
 * <p>The default holds nothing, so a host that has not supplied a directory cannot move a role or group task to a
 * named user: every such request is {@code 409 ASSIGNEE_NOT_ALLOWED}. A task assigned to one user can be moved to
 * any other without the directory.
 */
public interface AssigneeDirectory {

    /** Holds no role and no group: the fail-closed default. */
    AssigneeDirectory NONE = new AssigneeDirectory() {
        @Override
        public boolean holdsRole(String tenantId, String userId, String role) {
            return false;
        }

        @Override
        public boolean inGroup(String tenantId, String userId, String group) {
            return false;
        }
    };

    /**
     * @param tenantId the tenant of the task
     * @param userId the user the task would be handed to
     * @param role a role name without a {@code ROLE_} prefix, as in a {@code role:} assignee
     * @return whether the user holds the role in that tenant
     */
    boolean holdsRole(String tenantId, String userId, String role);

    /**
     * @param tenantId the tenant of the task
     * @param userId the user the task would be handed to
     * @param group a group name, as in a {@code group:} assignee
     * @return whether the user belongs to the group in that tenant
     */
    boolean inGroup(String tenantId, String userId, String group);
}
