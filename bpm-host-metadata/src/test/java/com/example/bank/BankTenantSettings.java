package com.example.bank;

import io.github.dhluong90.bpm.host.spi.ModuleTenantSettings;
import java.util.List;
import java.util.Map;

/** A host's own implementation of the engine-only SPI, which the host metadata module refuses. */
public final class BankTenantSettings implements ModuleTenantSettings {

    @Override
    public String module() {
        return "rules";
    }

    @Override
    public List<String> names() {
        return List.of("bank.branch-code");
    }

    @Override
    public Map<String, Object> settings(String tenantId) {
        return Map.of("bank.branch-code", "HCM-01");
    }
}
