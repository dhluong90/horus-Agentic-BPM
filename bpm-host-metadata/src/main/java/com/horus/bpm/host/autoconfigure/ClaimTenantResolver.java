package com.horus.bpm.host.autoconfigure;

import com.horus.bpm.host.spi.CallerContext;
import com.horus.bpm.host.spi.TenantResolver;
import java.util.Optional;

/** {@code horus.bpm.tenancy.resolver: security-context}: the tenant is a claim of the caller's token. */
final class ClaimTenantResolver implements TenantResolver {

    private final String claim;

    ClaimTenantResolver(String claim) {
        this.claim = claim;
    }

    @Override
    public Optional<String> resolve(CallerContext caller) {
        return caller.stringClaim(claim);
    }

    @Override
    public String toString() {
        return "the " + claim + " claim";
    }
}
