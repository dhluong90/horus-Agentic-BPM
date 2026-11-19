package com.horus.bpm.host.autoconfigure;

import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.jspecify.annotations.Nullable;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Resolves the caller and tenant of a request from the authenticated principal, never from a request body
 * or query parameter (TR-SEC-03).
 *
 * <p>In {@code security-context} mode the tenant is a claim of the caller's token. Tenant switching is not
 * available yet, so an {@code X-Tenant-Id} header that names another tenant is refused with
 * {@code 403 TENANT_NOT_ALLOWED} and a {@code tenant.switch.refused} security log entry (ADR-0014). In
 * {@code header:<name>} mode a trusted gateway sets the tenant header after authentication.
 */
final class CallerResolution {

    static final String TENANT_SWITCH_HEADER = "X-Tenant-Id";
    private static final Log SECURITY_LOG = LogFactory.getLog("com.horus.bpm.security");

    private final HostMetadataProperties.Tenancy tenancy;
    private final Function<Authentication, Map<String, Object>> claims;

    CallerResolution(HostMetadataProperties.Tenancy tenancy, Function<Authentication, Map<String, Object>> claims) {
        this.tenancy = tenancy;
        this.claims = claims;
    }

    /**
     * The caller of the current request.
     *
     * @throws ProblemException {@code 401 UNAUTHENTICATED} without a principal or without a tenant,
     *     {@code 403 TENANT_NOT_ALLOWED} for a tenant header that differs from the token tenant
     */
    Caller resolve(HttpServletRequest request) {
        Authentication authentication = SecurityContextHolder.getContextHolderStrategy().getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            throw new ProblemException(Problem.unauthenticated());
        }
        Map<String, Object> tokenClaims = claims.apply(authentication);
        String tenant = tenant(request, authentication, tokenClaims);
        Set<String> authorities = authorities(authentication.getAuthorities());
        List<String> roles = authorities.stream().filter(a -> a.startsWith("ROLE_")).sorted().toList();
        return new Caller(authentication.getName(), displayName(tokenClaims), tenant, authorities, roles,
                stringList(tokenClaims.get("groups")));
    }

    private String tenant(HttpServletRequest request, Authentication authentication, Map<String, Object> tokenClaims) {
        String header = tenancy.tenantHeader();
        if (header != null) {
            String tenant = request.getHeader(header);
            if (tenant == null || tenant.isBlank()) {
                throw new ProblemException(Problem.noTenant("the " + header + " header"));
            }
            return tenant;
        }
        if (!(tokenClaims.get(tenancy.claim()) instanceof String tenant) || tenant.isBlank()) {
            throw new ProblemException(Problem.noTenant("the " + tenancy.claim() + " claim"));
        }
        String requested = request.getHeader(TENANT_SWITCH_HEADER);
        if (requested != null && !requested.equals(tenant)) {
            SECURITY_LOG.warn("tenant.switch.refused user=" + CorrelationIds.loggable(authentication.getName())
                    + " requestedTenant=" + CorrelationIds.loggable(requested)
                    + " correlationId=" + CorrelationIds.of(request));
            throw new ProblemException(Problem.tenantNotAllowed());
        }
        return tenant;
    }

    private static Set<String> authorities(Collection<? extends GrantedAuthority> granted) {
        return granted.stream()
                .map(GrantedAuthority::getAuthority)
                .filter(a -> a != null && !a.isBlank())
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private static @Nullable String displayName(Map<String, Object> tokenClaims) {
        for (String claim : List.of("name", "preferred_username")) {
            if (tokenClaims.get(claim) instanceof String value && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static List<String> stringList(@Nullable Object claim) {
        if (!(claim instanceof Collection<?> values)) {
            return List.of();
        }
        List<String> strings = new ArrayList<>();
        for (Object value : values) {
            if (value instanceof String s && !s.isBlank() && !strings.contains(s)) {
                strings.add(s);
            }
        }
        return strings;
    }
}
