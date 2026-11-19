package com.horus.bpm.host.spi;

import java.util.Optional;

/**
 * Finds the tenant of a request (PD-079, ADR-0014 decision 6). The engines' default reads the
 * {@code horus.bpm.tenancy.claim} attribute (default {@code tenant_id}) of the authenticated principal, so
 * a validated token and a server-side session principal from an OIDC or OAuth2 login are handled alike.
 * A host whose principal carries no such attribute, for example after a form, SAML or LDAP sign-in,
 * declares its own bean of this type, which replaces the default:
 * <pre>{@code
 * @Bean
 * TenantResolver hostTenantResolver(BankUserDirectory directory) {
 *     return caller -> caller.principal() instanceof BankUser user
 *             ? Optional.of(user.tenant())
 *             : directory.tenantOf(caller.principalName());
 * }
 * }</pre>
 *
 * <p>The engines ship no resolver that reads a request header, and {@link CallerContext} offers none: in
 * a browser any header can be set by script, and a header set by a misconfigured gateway would expose one
 * tenant's data to another tenant's operator. A host that writes such a resolver owns that risk.
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
