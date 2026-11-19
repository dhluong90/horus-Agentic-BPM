package com.horus.bpm.rules.autoconfigure;

import com.horus.bpm.rules.spi.CallerResolver;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Default {@link CallerResolver} on Spring Security: the caller is the authentication name, the
 * permissions are the granted authorities that start with {@code rules:} (ADR-0010). The host maps
 * its roles to those strings. Thread-safe.
 */
final class SecurityContextCallerResolver implements CallerResolver {

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
        return authentication.getAuthorities().stream().map(GrantedAuthority::getAuthority)
                .filter(a -> a != null && a.startsWith("rules:")).collect(Collectors.toUnmodifiableSet());
    }

    private static Authentication authentication() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            return null;
        }
        return authentication;
    }
}
