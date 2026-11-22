package io.github.dhluong90.bpm.host.autoconfigure;

import io.github.dhluong90.bpm.host.spi.CallerContext;
import io.github.dhluong90.bpm.host.spi.TenantResolver;
import java.util.Optional;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;

/**
 * The engines' default {@link TenantResolver} (PD-079, ADR-0014 decision 6): the {@code tenancy.claim}
 * attribute of the authenticated principal, whether that is a validated token or a server-side session
 * principal (an OIDC or OAuth2 login, for example). Nothing the browser sends is read. On a purely local
 * start-up only, {@code tenancy.dev-tenant} is the tenant of a principal that carries none and is not a token
 * (RLE-037): a validated token without a tenant is always refused, so a client-credentials token is never served
 * as the development tenant.
 */
final class PrincipalTenantResolver implements TenantResolver {

    private final String attribute;
    private final @Nullable String devTenant;
    private final Predicate<Object> isToken;

    PrincipalTenantResolver(String attribute, @Nullable String devTenant, Predicate<Object> isToken) {
        this.attribute = attribute;
        this.devTenant = devTenant;
        this.isToken = isToken;
    }

    @Override
    public Optional<String> resolve(CallerContext caller) {
        Optional<String> tenant = caller.stringAttribute(attribute);
        if (tenant.isPresent() || devTenant == null || isToken.test(caller.principal())) {
            return tenant;
        }
        return Optional.of(devTenant);
    }

    @Override
    public String toString() {
        return "the " + attribute + " attribute of the principal";
    }
}
