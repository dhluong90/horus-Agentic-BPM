package io.github.dhluong90.bpm.rules.autoconfigure;

import io.github.dhluong90.bpm.rules.internal.service.MaskingStartupCheck;
import io.github.dhluong90.bpm.rules.spi.MaskingPolicy;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Runs the masking start-up check (RLE-035) once the schema is current: a tenant whose rule sets mask an input
 * must have a valid key, and a configured key must be valid, or the host does not start. Then logs every tenant
 * whose {@code decision-log.mask-default} changed since the previous start-up, with the old and new value, and
 * records the value in force (RLE-052). Called by {@link RuleEngineSchemaInitializer} after the migrations; a host
 * bean of this type replaces it.
 */
public final class RuleEngineMaskingCheck {

    private final Runnable verify;

    RuleEngineMaskingCheck(Runnable verify) {
        this.verify = Objects.requireNonNull(verify, "verify must not be null");
    }

    static RuleEngineMaskingCheck of(MaskingStartupCheck check, MaskingPolicy policy) {
        return of(check, policy, Clock.systemUTC());
    }

    static RuleEngineMaskingCheck of(MaskingStartupCheck check, MaskingPolicy policy, Clock clock) {
        Objects.requireNonNull(check, "check must not be null");
        Objects.requireNonNull(policy, "policy must not be null");
        Objects.requireNonNull(clock, "clock must not be null");
        return new RuleEngineMaskingCheck(() -> {
            check.verify(policy instanceof PropertyMaskingPolicy properties ? properties.invalidSettings() : List.of());
            check.recordMaskDefaults(policy instanceof PropertyMaskingPolicy properties
                    ? properties.configuredTenants() : Set.of(), clock.instant());
        });
    }

    void verify() {
        verify.run();
    }
}
