package com.horus.bpm.rules.internal.service;

import com.horus.bpm.rules.spi.CallerResolver;
import com.horus.bpm.rules.spi.TenantResolver;
import java.util.Set;

/** A switchable caller and tenant, standing in for the host security context. */
final class TestCaller implements TenantResolver, CallerResolver {

    private volatile String tenant;
    private volatile String user;

    TestCaller(String tenant, String user) {
        this.tenant = tenant;
        this.user = user;
    }

    void become(String tenant, String user) {
        this.tenant = tenant;
        this.user = user;
    }

    @Override
    public String currentTenantId() {
        return tenant;
    }

    @Override
    public String currentCallerId() {
        return user;
    }

    @Override
    public Set<String> currentPermissions() {
        return Set.of("rules:author", "rules:approve", "rules:evaluate", "rules:read");
    }
}
