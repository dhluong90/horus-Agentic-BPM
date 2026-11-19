package io.github.dhluong90.bpm.process.autoconfigure;

import com.horus.bpm.host.spi.CallerContext;
import io.github.dhluong90.bpm.process.spi.CallerResolver;
import io.github.dhluong90.bpm.process.spi.TenantResolver;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.OAuth2AuthenticatedPrincipal;
import org.springframework.util.ClassUtils;

/**
 * The default resolvers on Spring Security: the caller is the authenticated principal; permissions are its
 * {@code processes:*} authorities plus those mapped from its roles; roles are its {@code ROLE_*} authorities; the
 * tenant comes from the host's {@code TenantResolver} (bpm-host-metadata), never from a request body.
 */
final class SecurityContextResolvers {

    private static final Pattern TENANT = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$");
    private static final boolean OAUTH2 = ClassUtils.isPresent(
            "org.springframework.security.oauth2.core.OAuth2AuthenticatedPrincipal",
            SecurityContextResolvers.class.getClassLoader());

    private SecurityContextResolvers() {
    }

    static Authentication authentication() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            return null;
        }
        return authentication;
    }

    static CallerResolver callers(Map<String, List<String>> rolePermissions) {
        Map<String, List<String>> mapping = Map.copyOf(rolePermissions);
        return new CallerResolver() {
            @Override
            public String currentCallerId() {
                Authentication a = authentication();
                return a == null ? null : a.getName();
            }

            @Override
            public Set<String> currentPermissions() {
                Authentication a = authentication();
                if (a == null) {
                    return Set.of();
                }
                Set<String> permissions = new HashSet<>();
                for (String authority : authorities(a)) {
                    if (authority.startsWith("processes:")) {
                        permissions.add(authority);
                    }
                    permissions.addAll(mapping.getOrDefault(authority, List.of()));
                }
                return Set.copyOf(permissions);
            }

            @Override
            public Set<String> currentRoles() {
                Authentication a = authentication();
                return a == null ? Set.of() : authorities(a).stream().filter(s -> s.startsWith("ROLE_"))
                        .map(s -> s.substring(5)).collect(Collectors.toUnmodifiableSet());
            }

            @Override
            public Set<String> currentGroups() {
                Authentication a = authentication();
                if (a == null || !(claims(a).get("groups") instanceof Collection<?> groups)) {
                    return Set.of();
                }
                return groups.stream().filter(String.class::isInstance).map(String.class::cast)
                        .collect(Collectors.toUnmodifiableSet());
            }
        };
    }

    static TenantResolver tenants(com.horus.bpm.host.spi.TenantResolver host, String tenantClaim) {
        Objects.requireNonNull(host, "host");
        return () -> {
            Authentication a = authentication();
            if (a == null) {
                return null;
            }
            Map<String, Object> attributes = claims(a);
            Set<String> authorities = Set.copyOf(authorities(a));
            CallerContext caller = new CallerContext() {
                @Override
                public String principalName() {
                    return a.getName();
                }

                @Override
                public Object principal() {
                    return a.getPrincipal();
                }

                @Override
                public Set<String> authorities() {
                    return authorities;
                }

                @Override
                public Map<String, Object> attributes() {
                    return attributes;
                }
            };
            Optional<String> resolved = host.resolve(caller);
            if (resolved.isEmpty() || !TENANT.matcher(resolved.get()).matches()) {
                return null;
            }
            Optional<String> tokenTenant = caller.stringAttribute(tenantClaim);
            if (tokenTenant.isPresent() && !tokenTenant.get().equals(resolved.get())) {
                return null;
            }
            return resolved.get();
        };
    }

    private static List<String> authorities(Authentication a) {
        return a.getAuthorities().stream().map(GrantedAuthority::getAuthority).filter(Objects::nonNull).toList();
    }

    private static Map<String, Object> claims(Authentication a) {
        if (OAUTH2 && a.getPrincipal() instanceof OAuth2AuthenticatedPrincipal principal) {
            return principal.getAttributes();
        }
        if (OAUTH2 && a.getCredentials() instanceof org.springframework.security.oauth2.core.ClaimAccessor claims) {
            return claims.getClaims();
        }
        return Map.of();
    }
}
