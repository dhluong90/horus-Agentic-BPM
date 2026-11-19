package com.horus.bpm.host.spi;

import java.util.Optional;

/**
 * Finds the tenant of a request. A host declares a bean of this type to take the tenant from somewhere the
 * built-in modes do not cover; the bean replaces the resolver chosen by {@code horus.bpm.tenancy.resolver}.
 *
 * <p><b>Host-session mode.</b> When callers sign in to the host itself (form, SAML or LDAP sign-in, and the
 * embedded cockpit, which uses that session), the resolver reads the tenant from the signed-in principal,
 * {@link CallerContext#principal()}, for example the host's own user type. It must not read a header: in a
 * browser any header can be set by script. Hosts that accept both sessions and bearer tokens return the
 * token claim for token callers and the principal's tenant for session callers:
 * <pre>{@code
 * @Bean
 * TenantResolver hostTenantResolver() {
 *     return caller -> caller.stringClaim("tenant_id")
 *             .or(() -> caller.principal() instanceof BankUser user ? Optional.of(user.tenant()) : Optional.empty());
 * }
 * }</pre>
 *
 * <p>Whatever the resolver returns, the engines then apply two checks of their own, so a resolver cannot
 * widen access: when the caller's token carries the tenant claim ({@code horus.bpm.tenancy.claim}), the
 * resolved tenant must equal it; and an {@code X-Tenant-Id} header must equal the resolved tenant while
 * tenant switching is off. Either mismatch is {@code 403 TENANT_NOT_ALLOWED} with a security log entry.
 * The resolved tenant must also be 1 to 64 letters, digits, {@code .}, {@code _} or {@code -}, starting
 * with a letter or digit.
 *
 * <p>Implementations must be thread-safe and must not perform network I/O.
 */
@FunctionalInterface
public interface TenantResolver {

    /**
     * The tenant of the caller's request.
     *
     * @param caller the authenticated caller
     * @return the tenant, or empty when none can be resolved, which the engines answer with
     *     {@code 401 UNAUTHENTICATED}
     */
    Optional<String> resolve(CallerContext caller);
}
