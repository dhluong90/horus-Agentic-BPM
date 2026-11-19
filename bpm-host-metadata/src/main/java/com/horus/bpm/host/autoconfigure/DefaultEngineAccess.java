package com.horus.bpm.host.autoconfigure;

import com.horus.bpm.host.spi.CallerContext;
import com.horus.bpm.host.spi.EngineAccess;
import com.horus.bpm.host.spi.EngineAccessDeniedException;
import com.horus.bpm.host.spi.EngineAccessDeniedException.Reason;
import com.horus.bpm.host.spi.EngineCaller;
import com.horus.bpm.host.spi.ModuleDescriptor;
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
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * The default {@link EngineAccess}: the caller from the host's Spring Security context, the tenant from the
 * {@link TenantResolver}, never from a request body or query parameter (TR-SEC-03), and permissions from
 * the authorities and the engines' role mappings.
 *
 * <p>Two checks then apply to every resolver, the host's own included: when the principal carries the
 * tenant attribute, the resolved tenant must equal it; and while tenant switching is off, an
 * {@code X-Tenant-Id} header, the switch request of ADR-0014 decision 5, must equal the resolved tenant. A
 * mismatch, or a tenant outside the allowed format, is {@code 403 TENANT_NOT_ALLOWED} with a
 * {@code tenant.switch.refused} security log entry (ADR-0014). The current request is taken from Spring's
 * request context; outside a request (a host job running as a user) there is no header to check.
 */
final class DefaultEngineAccess implements EngineAccess {

    static final String TENANT_SWITCH_HEADER = "X-Tenant-Id";
    /** 1 to 64 characters, starting with a letter or digit. */
    static final Pattern TENANT = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$");
    private static final Log SECURITY_LOG = LogFactory.getLog("com.horus.bpm.security");

    private final TenantResolver resolver;
    private final String tenantClaim;
    private final Function<Authentication, Map<String, Object>> claims;
    private final String challenge;
    private final List<ModuleDescriptor> modules;

    DefaultEngineAccess(TenantResolver resolver, String tenantClaim, Function<Authentication, Map<String, Object>> claims,
            String challenge, List<ModuleDescriptor> modules) {
        this.resolver = resolver;
        this.tenantClaim = tenantClaim;
        this.claims = claims;
        this.challenge = challenge;
        this.modules = List.copyOf(modules);
    }

    @Override
    public EngineCaller currentCaller() {
        Authentication authentication = SecurityContextHolder.getContextHolderStrategy().getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            throw new EngineAccessDeniedException(Reason.UNAUTHENTICATED, challenge, null);
        }
        HttpServletRequest request = currentRequest();
        Map<String, Object> tokenClaims = claims.apply(authentication);
        Set<String> authorities = authorities(authentication.getAuthorities());
        Context context = new Context(authentication.getName(), authentication.getPrincipal(), authorities, tokenClaims);
        String tokenTenant = context.stringAttribute(tenantClaim).orElse(null);

        Optional<String> resolved = resolver.resolve(context);
        if (resolved.isEmpty()) {
            String correlationId = correlationId(request);
            SECURITY_LOG.warn("tenant.unresolved user=" + CorrelationIds.loggable(authentication.getName())
                    + " source=" + CorrelationIds.loggable(resolver.toString())
                    + " correlationId=" + correlationId
                    + " clientCorrelationId=" + clientCorrelationId(request));
            throw new EngineAccessDeniedException(Reason.NO_TENANT, invalidTokenChallenge(), correlationId);
        }
        String tenant = resolved.get();
        if (!TENANT.matcher(tenant).matches()) {
            throw refused(request, authentication, tokenTenant, tenant, "invalid-tenant");
        }
        if (tokenTenant != null && !tokenTenant.equals(tenant)) {
            throw refused(request, authentication, tokenTenant, tenant, "differs-from-token-tenant");
        }
        String requested = request == null ? null : request.getHeader(TENANT_SWITCH_HEADER);
        if (requested != null && !requested.equals(tenant)) {
            throw refused(request, authentication, tokenTenant, requested, "switching-off");
        }
        return new EngineCaller(authentication.getName(), displayName(context), tenant, authorities,
                stringList(tokenClaims.get("groups")));
    }

    /**
     * An authority equal to the permission, or a host authority the declaring engine's role mapping grants
     * it for. Only the engine that declares a permission can grant it.
     */
    @Override
    public boolean hasPermission(EngineCaller caller, String permission) {
        for (ModuleDescriptor module : modules) {
            if (module.permissions().contains(permission)) {
                return caller.authorities().contains(permission)
                        || module.rolePermissions().entrySet().stream().anyMatch(mapping ->
                                caller.authorities().contains(mapping.getKey()) && mapping.getValue().contains(permission));
            }
        }
        throw new IllegalArgumentException("No installed engine declares the permission '" + permission + "'.");
    }

    private EngineAccessDeniedException refused(@Nullable HttpServletRequest request, Authentication authentication,
            @Nullable String tokenTenant, String requested, String reason) {
        String correlationId = correlationId(request);
        SECURITY_LOG.warn("tenant.switch.refused reason=" + reason
                + " user=" + CorrelationIds.loggable(authentication.getName())
                + " tokenTenant=" + CorrelationIds.loggable(tokenTenant)
                + " requestedTenant=" + CorrelationIds.loggable(requested)
                + " correlationId=" + correlationId
                + " clientCorrelationId=" + clientCorrelationId(request));
        return new EngineAccessDeniedException(Reason.TENANT_NOT_ALLOWED, null, correlationId);
    }

    private static @Nullable HttpServletRequest currentRequest() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        return attributes instanceof ServletRequestAttributes servlet ? servlet.getRequest() : null;
    }

    private static String correlationId(@Nullable HttpServletRequest request) {
        return request == null ? CorrelationIds.newUlid(System.currentTimeMillis()) : CorrelationIds.of(request);
    }

    private static String clientCorrelationId(@Nullable HttpServletRequest request) {
        return request == null ? "-" : CorrelationIds.clientValue(request);
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
        return context.stringAttribute("name").or(() -> context.stringAttribute("preferred_username")).orElse(null);
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

    @Override
    public String toString() {
        return "the engines' default access check";
    }

    private record Context(String principalName, Object principal, Set<String> authorities,
            Map<String, Object> attributes) implements CallerContext {
    }
}
