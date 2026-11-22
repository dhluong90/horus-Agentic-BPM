package io.github.dhluong90.bpm.host.autoconfigure;

import io.github.dhluong90.bpm.host.internal.CorrelationIds;
import io.github.dhluong90.bpm.host.spi.CallerContext;
import io.github.dhluong90.bpm.host.spi.EngineAccess;
import io.github.dhluong90.bpm.host.spi.EngineAccessDeniedException.Reason;
import io.github.dhluong90.bpm.host.spi.EngineAccessDeniedException;
import io.github.dhluong90.bpm.host.spi.EngineCaller;
import io.github.dhluong90.bpm.host.spi.ModuleDescriptor;
import io.github.dhluong90.bpm.host.spi.TenantResolver;
import io.github.dhluong90.bpm.host.spi.TenantSwitchAudit;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
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
 * tenant attribute, the resolved tenant must equal it; and an {@code X-Tenant-Id} header, the switch request
 * of ADR-0014 decision 5, must equal the resolved tenant, unless tenant switching is on and the header names
 * a tenant in the allowed-tenants attribute of the verified principal (the header alone never grants a tenant;
 * the signed-in tenant is always allowed). A mismatch, an unlisted tenant, a repeated header, or a tenant
 * outside the allowed format, is {@code 403 TENANT_NOT_ALLOWED} with a {@code tenant.switch.refused}
 * security log entry (ADR-0014); an accepted switch writes {@code tenant.switch.accepted} with the user, both
 * tenants and the correlation id, and hands one record to the {@link TenantSwitchAudit} (#451) before the call
 * reaches engine data; a refused switch writes no audit record. The current request is taken from Spring's
 * request context; outside a request (a host job running as a user) there is no header to check.
 *
 * <p>Before any of that, a caller whose id starts with {@value #RESERVED_PREFIX} is refused with
 * {@code 403 PERMISSION_DENIED} and a {@code caller.reserved.refused} security log entry: the engines record
 * their own actions under such ids (a start-up import, a timer, a supersession), so a person signed in with
 * one could have their actions credited to the engine in the audit trail. The match is exact and
 * case-sensitive, so {@code systems-admin} is an ordinary caller. Neither claims nor the resolver are read
 * for a refused caller.
 *
 * <p>Within one request the caller is resolved once: the result, or the refusal, is kept as a request
 * attribute for the same authentication, so an operation that asks several times (the controller, then
 * the engine service) runs the resolver once and writes at most one security log entry. A refused
 * permission writes a {@code permission.denied} entry.
 */
final class DefaultEngineAccess implements EngineAccess {

    static final String TENANT_SWITCH_HEADER = "X-Tenant-Id";
    /** 1 to 64 characters, starting with a letter or digit. */
    static final Pattern TENANT = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$");
    /**
     * The engines' own namespace for the actions they record themselves. Not a setting: the engines check
     * the same prefix, so a host cannot move it.
     */
    static final String RESERVED_PREFIX = "system:";
    private static final Log SECURITY_LOG = LogFactory.getLog("io.github.dhluong90.bpm.security");
    private static final String RESOLVED_ATTRIBUTE = DefaultEngineAccess.class.getName() + ".resolved";

    private final TenantResolver resolver;
    private final String tenantClaim;
    private final boolean switching;
    private final String allowedTenantsClaim;
    private final Function<Authentication, Map<String, Object>> claims;
    private final String challenge;
    private final String groupsClaim;
    private final List<String> displayNameClaims;
    private final List<ModuleDescriptor> modules;
    private final TenantSwitchAudit audit;
    private final Clock clock;

    DefaultEngineAccess(TenantResolver resolver, HostMetadataProperties.Tenancy tenancy,
            Function<Authentication, Map<String, Object>> claims, HostMetadataProperties.Security security,
            List<ModuleDescriptor> modules) {
        this(resolver, tenancy, claims, security, modules, new LoggingTenantSwitchAudit(), Clock.systemUTC());
    }

    DefaultEngineAccess(TenantResolver resolver, HostMetadataProperties.Tenancy tenancy,
            Function<Authentication, Map<String, Object>> claims, HostMetadataProperties.Security security,
            List<ModuleDescriptor> modules, TenantSwitchAudit audit, Clock clock) {
        this.audit = java.util.Objects.requireNonNull(audit, "audit must not be null");
        this.clock = java.util.Objects.requireNonNull(clock, "clock must not be null");
        this.resolver = resolver;
        this.tenantClaim = tenancy.claim();
        this.switching = tenancy.switching().enabled();
        this.allowedTenantsClaim = tenancy.allowedTenantsClaim();
        this.claims = claims;
        this.challenge = security.challenge();
        this.groupsClaim = security.groupsClaim();
        this.displayNameClaims = security.displayNameClaims();
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
        if (request == null) {
            return resolve(authentication, null);
        }
        if (request.getAttribute(RESOLVED_ATTRIBUTE) instanceof Resolved resolved && resolved.authentication() == authentication) {
            return resolved.outcome();
        }
        try {
            EngineCaller caller = resolve(authentication, request);
            request.setAttribute(RESOLVED_ATTRIBUTE, new Resolved(authentication, caller, null));
            return caller;
        } catch (EngineAccessDeniedException refused) {
            request.setAttribute(RESOLVED_ATTRIBUTE, new Resolved(authentication, null, refused));
            throw refused;
        }
    }

    /** Logs the refusal on the security logger, then refuses with {@code PERMISSION_DENIED}. */
    @Override
    public EngineCaller requirePermission(String permission) {
        EngineCaller caller = currentCaller();
        if (hasPermission(caller, permission)) {
            return caller;
        }
        HttpServletRequest request = currentRequest();
        String correlationId = correlationId(request);
        // The permission is one an installed engine declares (hasPermission throws otherwise), so it is logged as is
        SECURITY_LOG.warn("permission.denied permission=" + permission
                + " user=" + CorrelationIds.loggable(caller.userId())
                + " tenant=" + CorrelationIds.loggable(caller.tenant())
                + " correlationId=" + correlationId
                + " clientCorrelationId=" + clientCorrelationId(request));
        throw new EngineAccessDeniedException(Reason.PERMISSION_DENIED, null, correlationId);
    }

    private EngineCaller resolve(Authentication authentication, @Nullable HttpServletRequest request) {
        if (authentication.getName().startsWith(RESERVED_PREFIX)) {
            throw reservedIdentity(request, authentication);
        }
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
        List<String> allowed = switching ? allowedTenants(context, tenant) : List.of(tenant);
        String requested = request == null ? null : request.getHeader(TENANT_SWITCH_HEADER);
        String effective = tenant;
        if (requested != null && !requested.equals(tenant)) {
            if (!switching) {
                throw refused(request, authentication, tokenTenant, requested, "switching-off");
            }
            if (Collections.list(request.getHeaders(TENANT_SWITCH_HEADER)).size() > 1) {
                throw refused(request, authentication, tokenTenant, requested, "repeated-header");
            }
            if (!TENANT.matcher(requested).matches()) {
                throw refused(request, authentication, tokenTenant, requested, "invalid-tenant");
            }
            if (!allowed.contains(requested)) {
                throw refused(request, authentication, tokenTenant, requested, "not-in-allowed-list");
            }
            effective = requested;
            String correlationId = correlationId(request);
            SECURITY_LOG.info("tenant.switch.accepted user=" + CorrelationIds.loggable(authentication.getName())
                    + " fromTenant=" + CorrelationIds.loggable(tenant)
                    + " requestedTenant=" + CorrelationIds.loggable(requested)
                    + " correlationId=" + correlationId
                    + " clientCorrelationId=" + clientCorrelationId(request));
            audit.accepted(new TenantSwitchAudit.TenantSwitch(authentication.getName(), tenant, requested,
                    clock.instant(), correlationId));
        }
        return new EngineCaller(authentication.getName(), displayName(context), effective, authorities,
                stringList(context.attributes().get(groupsClaim)), allowed);
    }

    /**
     * The signed-in tenant first, then the well-formed tenants of the verified principal's allowed-tenants
     * attribute: a JSON array, or one space-separated string as the OAuth scope claim is written. Nothing the
     * request carries is read.
     */
    private List<String> allowedTenants(CallerContext context, String signedIn) {
        Set<String> allowed = new LinkedHashSet<>();
        allowed.add(signedIn);
        Object claim = context.attributes().get(allowedTenantsClaim);
        Collection<?> values = claim instanceof Collection<?> list ? list
                : claim instanceof String text ? Arrays.asList(text.trim().split("\\s+")) : List.of();
        for (Object value : values) {
            if (value instanceof String tenant && TENANT.matcher(tenant).matches()) {
                allowed.add(tenant);
            }
        }
        return List.copyOf(allowed);
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

    /**
     * The user value is encoded and cut exactly as in {@code tenant.switch.refused}; nothing else of the
     * caller is logged.
     */
    private static EngineAccessDeniedException reservedIdentity(@Nullable HttpServletRequest request,
            Authentication authentication) {
        String correlationId = correlationId(request);
        SECURITY_LOG.warn("caller.reserved.refused user=" + CorrelationIds.loggable(authentication.getName())
                + " correlationId=" + correlationId
                + " clientCorrelationId=" + clientCorrelationId(request));
        return new EngineAccessDeniedException(Reason.PERMISSION_DENIED, null, correlationId);
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

    /** The first of {@code security.display-name-claims} the principal carries as a non-blank string. */
    private @Nullable String displayName(CallerContext context) {
        for (String claim : displayNameClaims) {
            Optional<String> value = context.stringAttribute(claim);
            if (value.isPresent()) {
                return value.get();
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

    @Override
    public String toString() {
        return "the engines' default access check";
    }

    /** The caller resolved for one authentication in this request, or the refusal. */
    private record Resolved(Authentication authentication, @Nullable EngineCaller caller,
            @Nullable EngineAccessDeniedException refused) {

        EngineCaller outcome() {
            if (refused != null) {
                throw refused;
            }
            return java.util.Objects.requireNonNull(caller);
        }
    }

    private record Context(String principalName, Object principal, Set<String> authorities,
            Map<String, Object> attributes) implements CallerContext {
    }
}
