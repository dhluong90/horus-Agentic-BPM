package com.horus.bpm.host.autoconfigure;

import com.horus.bpm.host.spi.CallerContext;
import com.horus.bpm.host.spi.TenantResolver;
import java.util.Optional;

/**
 * {@code horus.bpm.tenancy.resolver: header:<name>}: a trusted gateway sets the tenant header after it has
 * authenticated the caller. When the caller's token also carries the tenant claim, the engines refuse a
 * header that differs from it (see {@link TenantResolver}).
 */
final class HeaderTenantResolver implements TenantResolver {

    private final String header;

    HeaderTenantResolver(String header) {
        this.header = header;
    }

    @Override
    public Optional<String> resolve(CallerContext caller) {
        return caller.header(header).filter(value -> !value.isBlank());
    }

    @Override
    public String toString() {
        return "the " + header + " header";
    }
}
