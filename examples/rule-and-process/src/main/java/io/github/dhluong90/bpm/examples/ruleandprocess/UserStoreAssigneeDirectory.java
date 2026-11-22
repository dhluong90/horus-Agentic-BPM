package io.github.dhluong90.bpm.examples.ruleandprocess;

import io.github.dhluong90.bpm.process.spi.AssigneeDirectory;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

/**
 * The host's answer to "may this person work a role task?" for reassign and delegate (PD-110): a user holds a role
 * when the host's own user store gives it to them. The demo users carry no tenant and belong to the {@code dev}
 * tenant only, so any other tenant holds nobody; outside {@code dev} the store is empty and nobody holds anything.
 * The demo has no groups. A real host asks its identity provider instead.
 */
final class UserStoreAssigneeDirectory implements AssigneeDirectory {

    private final UserDetailsService users;
    private final String tenant;

    UserStoreAssigneeDirectory(UserDetailsService users, String tenant) {
        this.users = users;
        this.tenant = tenant == null || tenant.isBlank() ? null : tenant;
    }

    @Override
    public boolean holdsRole(String tenantId, String userId, String role) {
        if (tenant == null || !tenant.equals(tenantId) || userId == null || role == null) {
            return false;
        }
        try {
            return users.loadUserByUsername(userId).getAuthorities().stream()
                    .anyMatch(authority -> ("ROLE_" + role).equals(authority.getAuthority()));
        } catch (UsernameNotFoundException unknown) {
            return false;
        }
    }

    @Override
    public boolean inGroup(String tenantId, String userId, String group) {
        return false;
    }
}
