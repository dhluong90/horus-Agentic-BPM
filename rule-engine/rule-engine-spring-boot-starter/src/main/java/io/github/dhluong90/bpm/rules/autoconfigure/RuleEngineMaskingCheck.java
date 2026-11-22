package io.github.dhluong90.bpm.rules.autoconfigure;

import io.github.dhluong90.bpm.rules.internal.service.MaskingStartupCheck;
import io.github.dhluong90.bpm.rules.spi.MaskingPolicy;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * Runs the masking start-up check (RLE-035) once the schema is current: a tenant whose rule sets mask an input
 * must have a valid key, and a configured key must be valid, or the host does not start. Then logs every tenant
 * whose {@code decision-log.mask-default} changed since the previous start-up, with the old and new value, and
 * records the value in force (RLE-052). Called by {@link RuleEngineSchemaInitializer} after the migrations; a host
 * bean of this type replaces it. A host's own {@link MaskingPolicy} that declares no keys in
 * {@link MaskingPolicy#configuredKeys()} gets a warning: the key id check (TR-SEC-10) then covers nothing until a live
 * rule set makes the engine refuse its undeclared keys.
 */
public final class RuleEngineMaskingCheck {

    private static final Log LOG = LogFactory.getLog(RuleEngineMaskingCheck.class);

    private final Runnable verify;

    RuleEngineMaskingCheck(Runnable verify) {
        this.verify = Objects.requireNonNull(verify, "verify must not be null");
    }

    static RuleEngineMaskingCheck of(MaskingStartupCheck check, MaskingPolicy policy) {
        return of(check, policy, Clock.systemUTC());
    }

    static RuleEngineMaskingCheck of(MaskingStartupCheck check, MaskingPolicy policy, Clock clock) {
        return of(check, policy, clock, false);
    }

    /**
     * @param summariseUnclassified whether to log the one start-up WARN of PD-146 that counts the unclassified inputs
     *     of approved versions: outside the {@code dev} profile
     */
    static RuleEngineMaskingCheck of(MaskingStartupCheck check, MaskingPolicy policy, Clock clock,
            boolean summariseUnclassified) {
        Objects.requireNonNull(check, "check must not be null");
        Objects.requireNonNull(policy, "policy must not be null");
        Objects.requireNonNull(clock, "clock must not be null");
        return new RuleEngineMaskingCheck(() -> {
            if (policy instanceof PropertyMaskingPolicy properties) {
                check.verify(properties.invalidSettings());
            } else {
                if (policy.configuredKeys().isEmpty()) {
                    LOG.warn("The masking policy " + policy.getClass().getName() + " declares no keys in"
                            + " configuredKeys(), so masking key ids are not checked (TR-SEC-10). If it supplies keys,"
                            + " return every key, current and previous, from configuredKeys(): start-up refuses a key"
                            + " in use for a live rule set that is not declared there.");
                }
                check.verify(List.of());
            }
            check.recordMaskDefaults(policy instanceof PropertyMaskingPolicy properties
                    ? properties.configuredTenants() : Set.of(), clock.instant());
            if (summariseUnclassified) {
                check.unclassifiedSummary().ifPresent(LOG::warn);
            }
        });
    }

    void verify() {
        verify.run();
    }
}
