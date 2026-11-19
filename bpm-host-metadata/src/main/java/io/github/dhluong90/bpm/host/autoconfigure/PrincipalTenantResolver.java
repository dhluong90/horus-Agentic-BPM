package io.github.dhluong90.bpm.host.autoconfigure;

import io.github.dhluong90.bpm.host.spi.CallerContext;
import io.github.dhluong90.bpm.host.spi.TenantResolver;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * The engines' default {@link TenantResolver} (PD-079, ADR-0014 decision 6): the {@code tenancy.claim}
 * attribute of the authenticated principal, whether that is a validated token or a server-side session
 * principal (an OIDC or OAuth2 login, for example). Nothing the browser sends is read. On the
 * {@code dev} profile only, {@code tenancy.dev-tenant} is the tenant of a principal that carries none.
 */
final class PrincipalTenantResolver implements TenantResolver {

    private final String attribute;
    private final @Nullable String devTenant;

    PrincipalTenantResolver(String attribute, @Nullable String devTenant) {
        this.attribute = attribute;
        this.devTenant = devTenant;
    }

    @Override
    public Optional<String> resolve(CallerContext caller) {
        return caller.stringAttribute(attribute).or(() -> Optional.ofNullable(devTenant));
    }

    @Override
    public String toString() {
        return "the " + attribute + " attribute of the principal";
    }
}
