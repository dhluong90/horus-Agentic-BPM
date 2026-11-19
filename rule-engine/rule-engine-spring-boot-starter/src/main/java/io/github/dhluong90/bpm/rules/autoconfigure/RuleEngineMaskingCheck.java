package com.horus.bpm.rules.autoconfigure;

import com.horus.bpm.rules.internal.service.MaskingStartupCheck;
import com.horus.bpm.rules.spi.MaskingPolicy;
import java.util.List;
import java.util.Objects;

/**
 * Runs the masking start-up check (RLE-035) once the schema is current: a tenant whose rule sets mask an input
 * must have a valid key, and a configured key must be valid, or the host does not start. Called by
 * {@link RuleEngineSchemaInitializer} after the migrations; a host bean of this type replaces it.
 */
public final class RuleEngineMaskingCheck {

    private final Runnable verify;

    RuleEngineMaskingCheck(Runnable verify) {
        this.verify = Objects.requireNonNull(verify, "verify must not be null");
    }

    static RuleEngineMaskingCheck of(MaskingStartupCheck check, MaskingPolicy policy) {
        Objects.requireNonNull(check, "check must not be null");
        Objects.requireNonNull(policy, "policy must not be null");
        return new RuleEngineMaskingCheck(() -> check.verify(policy instanceof PropertyMaskingPolicy properties
                ? properties.invalidSettings() : List.of()));
    }

    void verify() {
        verify.run();
    }
}
