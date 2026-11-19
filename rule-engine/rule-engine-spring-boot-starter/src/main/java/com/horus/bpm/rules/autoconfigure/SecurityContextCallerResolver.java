package com.horus.bpm.rules.autoconfigure;

import com.horus.bpm.rules.spi.CallerResolver;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Default {@link CallerResolver} on Spring Security: the caller is the authentication name; the permissions
 * are the granted authorities that are {@code rules:*} strings plus those that
 * {@code horus.bpm.rules.security.role-permissions} grants to the caller's roles. This is the same rule
 * {@code GET /api/bpm/me} lists, so what a client is shown is what the services allow. Thread-safe.
 */
final class SecurityContextCallerResolver implements CallerResolver {

    private final Map<String, List<String>> rolePermissions;

    SecurityContextCallerResolver(Map<String, List<String>> rolePermissions) {
        this.rolePermissions = Map.copyOf(rolePermissions);
    }

    @Override
    public String currentCallerId() {
        Authentication authentication = authentication();
        return authentication == null ? null : authentication.getName();
    }

    @Override
    public Set<String> currentPermissions() {
        Authentication authentication = authentication();
        if (authentication == null) {
            return Set.of();
        }
        Set<String> permissions = new HashSet<>();
        for (GrantedAuthority granted : authentication.getAuthorities()) {
            String authority = granted.getAuthority();
            if (authority == null) {
                continue;
            }
            if (authority.startsWith("rules:")) {
                permissions.add(authority);
            }
            permissions.addAll(rolePermissions.getOrDefault(authority, List.of()));
        }
        return Set.copyOf(permissions);
    }

    static Authentication authentication() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            return null;
        }
        return authentication;
    }
}
