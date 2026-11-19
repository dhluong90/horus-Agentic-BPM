package com.horus.bpm.rules.autoconfigure;

import com.horus.bpm.host.spi.CallerContext;
import com.horus.bpm.rules.spi.TenantResolver;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;

/**
 * The engine's tenant from the host contract's {@link com.horus.bpm.host.spi.TenantResolver}, so evaluation
 * and lifecycle calls use the tenant {@code GET /api/bpm/me} shows. The same two guards apply: the tenant must
 * be 1 to 64 letters, digits, {@code .}, {@code _} or {@code -}, and when the principal carries the tenant
 * claim the resolved tenant must equal it. A refused tenant is no tenant, which the services answer with
 * {@code UNAUTHENTICATED}. Request headers are never read. Thread-safe.
 */
final class HostTenantResolverBridge implements TenantResolver {

    private static final Pattern TENANT = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$");

    private final com.horus.bpm.host.spi.TenantResolver host;
    private final String tenantClaim;
    private final Function<Authentication, Map<String, Object>> claims;

    HostTenantResolverBridge(com.horus.bpm.host.spi.TenantResolver host, String tenantClaim,
            Function<Authentication, Map<String, Object>> claims) {
        this.host = Objects.requireNonNull(host, "host must not be null");
        this.tenantClaim = Objects.requireNonNull(tenantClaim, "tenantClaim must not be null");
        this.claims = Objects.requireNonNull(claims, "claims must not be null");
    }

    @Override
    public String currentTenantId() {
        Authentication authentication = SecurityContextCallerResolver.authentication();
        if (authentication == null) {
            return null;
        }
        Map<String, Object> attributes = claims.apply(authentication);
        Set<String> authorities = authentication.getAuthorities().stream().map(GrantedAuthority::getAuthority)
                .filter(Objects::nonNull).collect(Collectors.toUnmodifiableSet());
        CallerContext caller = new CallerContext() {
            @Override
            public String principalName() {
                return authentication.getName();
            }

            @Override
            public Object principal() {
                return authentication.getPrincipal();
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
    }
}
