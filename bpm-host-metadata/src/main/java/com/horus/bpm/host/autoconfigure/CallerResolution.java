package com.horus.bpm.host.autoconfigure;

import com.horus.bpm.host.spi.CallerContext;
import com.horus.bpm.host.spi.TenantResolver;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.regex.Pattern;
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
 * <p>The {@link TenantResolver} finds the tenant. Two checks then apply to every resolver, the host's own
 * included: when the token carries the tenant claim, the resolved tenant must equal it, so a gateway
 * header cannot move a caller into another tenant; and while tenant switching is off, an
 * {@code X-Tenant-Id} header must equal the resolved tenant. A mismatch, or a tenant outside the allowed
 * format, is {@code 403 TENANT_NOT_ALLOWED} with a {@code tenant.switch.refused} security log entry
 * (ADR-0014).
 */
final class CallerResolution {

    static final String TENANT_SWITCH_HEADER = "X-Tenant-Id";
    /** 1 to 64 characters, starting with a letter or digit. */
    static final Pattern TENANT = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$");
    private static final Log SECURITY_LOG = LogFactory.getLog("com.horus.bpm.security");

    private final TenantResolver resolver;
    private final String tenantClaim;
    private final Function<Authentication, Map<String, Object>> claims;
    private final String challenge;

    CallerResolution(TenantResolver resolver, String tenantClaim, Function<Authentication, Map<String, Object>> claims,
            String challenge) {
        this.resolver = resolver;
        this.tenantClaim = tenantClaim;
        this.claims = claims;
        this.challenge = challenge;
    }

    /**
     * The caller of the current request.
     *
     * @throws ProblemException {@code 401 UNAUTHENTICATED} without a principal or without a tenant,
     *     {@code 403 TENANT_NOT_ALLOWED} for a tenant the caller is not signed in for
     */
    Caller resolve(HttpServletRequest request) {
        Authentication authentication = SecurityContextHolder.getContextHolderStrategy().getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            throw new ProblemException(Problem.unauthenticated(), challenge);
        }
        Map<String, Object> tokenClaims = claims.apply(authentication);
        Set<String> authorities = authorities(authentication.getAuthorities());
        Context context = new Context(authentication.getName(), authentication.getPrincipal(), authorities, tokenClaims,
                request);
        String tokenTenant = context.stringClaim(tenantClaim).orElse(null);

        Optional<String> resolved = resolver.resolve(context);
        if (resolved.isEmpty()) {
            SECURITY_LOG.warn("tenant.unresolved user=" + CorrelationIds.loggable(authentication.getName())
                    + " source=" + CorrelationIds.loggable(resolver.toString())
                    + " correlationId=" + CorrelationIds.of(request)
                    + " clientCorrelationId=" + CorrelationIds.clientValue(request));
            throw new ProblemException(Problem.noTenant(), invalidTokenChallenge());
        }
        String tenant = resolved.get();
        if (!TENANT.matcher(tenant).matches()) {
            throw refused(request, authentication, tokenTenant, tenant, "invalid-tenant");
        }
        if (tokenTenant != null && !tokenTenant.equals(tenant)) {
            throw refused(request, authentication, tokenTenant, tenant, "differs-from-token-tenant");
        }
        String requested = request.getHeader(TENANT_SWITCH_HEADER);
        if (requested != null && !requested.equals(tenant)) {
            throw refused(request, authentication, tokenTenant, requested, "switching-off");
        }
        return new Caller(authentication.getName(), displayName(context), tenant, authorities,
                stringList(tokenClaims.get("groups")));
    }

    private static ProblemException refused(HttpServletRequest request, Authentication authentication,
            @Nullable String tokenTenant, String requested, String reason) {
        SECURITY_LOG.warn("tenant.switch.refused reason=" + reason
                + " user=" + CorrelationIds.loggable(authentication.getName())
                + " tokenTenant=" + CorrelationIds.loggable(tokenTenant)
                + " requestedTenant=" + CorrelationIds.loggable(requested)
                + " correlationId=" + CorrelationIds.of(request)
                + " clientCorrelationId=" + CorrelationIds.clientValue(request));
        return new ProblemException(Problem.tenantNotAllowed());
    }

    /** A bearer challenge says the token is the problem, so a client knows a fresh sign-in will not help. */
    private String invalidTokenChallenge() {
        return challenge.regionMatches(true, 0, "Bearer", 0, 6) ? challenge + " error=\"invalid_token\"" : challenge;
    }

    private static Set<String> authorities(Collection<? extends GrantedAuthority> granted) {
        return granted.stream()
                .map(GrantedAuthority::getAuthority)
                .filter(a -> a != null && !a.isBlank())
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private static @Nullable String displayName(CallerContext context) {
        return context.stringClaim("name").or(() -> context.stringClaim("preferred_username")).orElse(null);
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

    private record Context(String principalName, Object principal, Set<String> authorities,
            Map<String, Object> claims, HttpServletRequest request) implements CallerContext {

        @Override
        public Optional<String> header(String name) {
            return Optional.ofNullable(request.getHeader(name));
        }
    }
}
