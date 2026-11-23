package io.github.dhluong90.bpm.examples.ruleandprocess;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.oauth2.core.ClaimAccessor;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;

/**
 * The demo users. A user listed in {@code allowedTenants} signs in as a principal that also carries token-like claims
 * ({@code tenant_id} and {@code allowed_tenants}), so the cockpit's tenant switch has more than one tenant to offer
 * without an identity provider. The claims come from this store and never from the request, so the engines treat the
 * user exactly as they treat a verified token. Every other user stays a plain session principal and belongs to the
 * development tenant only.
 */
final class DemoUserStore extends InMemoryUserDetailsManager {

    private final String signedInTenant;
    private final Map<String, List<String>> allowedTenants;

    DemoUserStore(Collection<UserDetails> users, String signedInTenant, Map<String, List<String>> allowedTenants) {
        super(users);
        this.signedInTenant = signedInTenant;
        this.allowedTenants = Map.copyOf(allowedTenants);
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        UserDetails user = super.loadUserByUsername(username);
        List<String> tenants = allowedTenants.get(user.getUsername());
        if (tenants == null || signedInTenant == null || signedInTenant.isBlank()) {
            return user;
        }
        return new MultiTenantUser(user, Map.of("tenant_id", signedInTenant, "allowed_tenants", tenants));
    }

    /** A demo user whose principal carries the claims of a token. */
    static final class MultiTenantUser extends User implements ClaimAccessor {

        private static final long serialVersionUID = 1L;

        private final transient Map<String, Object> claims;

        MultiTenantUser(UserDetails user, Map<String, Object> claims) {
            super(user.getUsername(), user.getPassword(), user.isEnabled(), user.isAccountNonExpired(),
                    user.isCredentialsNonExpired(), user.isAccountNonLocked(), user.getAuthorities());
            this.claims = claims;
        }

        @Override
        public Map<String, Object> getClaims() {
            return claims == null ? Map.of() : claims;
        }
    }
}
