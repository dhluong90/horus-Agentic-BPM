package io.github.dhluong90.bpm.rules.autoconfigure;

import io.github.dhluong90.bpm.host.spi.ModuleDescriptor;
import io.github.dhluong90.bpm.host.spi.TenantModuleSettings;
import io.github.dhluong90.bpm.rules.spi.MaskingPolicy;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * The Rule Engine's entry in the host contract: {@code rules}, REST API {@code v1}, the engine version of
 * this build and the six permissions of ADR-0010.
 */
final class RuleEngineModule {

    /** Every permission the Rule Engine checks, in the order clients show them (ADR-0010). */
    static final List<String> PERMISSIONS = List.of(
            "rules:evaluate", "rules:read", "rules:author", "rules:approve", "rules:simulate", "rules:log:read",
            "rules:masked:search");

    /**
     * Feature flags turned on in this build. None yet: a flag is added with the feature it announces, so a
     * client never offers a screen whose endpoints are missing.
     */
    static final List<String> FEATURES = List.of();

    /** The rules setting clients read to offer or hide draft editing (PD-075, PD-094). */
    static final String COCKPIT_DRAFTS = "authoring.cockpit-drafts";

    /** The warning codes an approval must acknowledge (PD-104, RLE-053), comma-separated; empty when none. */
    static final String ACKNOWLEDGE_WARNINGS = "lifecycle.acknowledge-warnings";

    /** The masking default in force for the caller's tenant (PD-099 mitigation 4, RLE-052). */
    static final String MASK_DEFAULT = "decision-log.mask-default";

    /** Strict mode in force for the caller's tenant (PD-099 mitigation 2, RLE-050). */
    static final String REQUIRE_EXPLICIT_MASK = "decision-log.require-explicit-mask";

    private static final String VERSION_RESOURCE = "rule-engine.properties";

    private RuleEngineModule() {
    }

    /**
     * The descriptor for this host.
     *
     * @param rolePermissions {@code horus.bpm.rules.security.role-permissions}
     * @param cockpitDrafts {@code horus.bpm.rules.authoring.cockpit-drafts}; null (unset) is published as false
     * @throws IllegalArgumentException when the mapping grants a permission the engine does not check
     */
    static ModuleDescriptor descriptor(Map<String, List<String>> rolePermissions, Boolean cockpitDrafts) {
        return descriptor(rolePermissions, cockpitDrafts, io.github.dhluong90.bpm.rules.internal.service.EngineDefaults
                .ACKNOWLEDGE_WARNINGS);
    }

    /**
     * The descriptor for this host.
     *
     * @param rolePermissions {@code horus.bpm.rules.security.role-permissions}
     * @param cockpitDrafts {@code horus.bpm.rules.authoring.cockpit-drafts}; null (unset) is published as false
     * @param acknowledgeWarnings the validated {@code lifecycle.acknowledge-warnings}, published comma-separated
     *     because {@code /meta} settings hold scalars only
     * @throws IllegalArgumentException when the mapping grants a permission the engine does not check
     */
    static ModuleDescriptor descriptor(Map<String, List<String>> rolePermissions, Boolean cockpitDrafts,
            List<String> acknowledgeWarnings) {
        // Always published, false unless the host turns it on (PD-094), so clients never guess.
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put(COCKPIT_DRAFTS, Boolean.TRUE.equals(cockpitDrafts));
        settings.put(ACKNOWLEDGE_WARNINGS, String.join(",", acknowledgeWarnings));
        return new ModuleDescriptor("rules", engineVersion(), "v1", FEATURES, settings, PERMISSIONS, rolePermissions);
    }

    /**
     * The rules settings that depend on the caller's tenant, read from the masking policy on each {@code /meta}
     * request: the masking default and strict mode in force. Both are always present and boolean.
     *
     * @param policy the host's masking policy
     * @return the tenant settings of the {@code rules} module
     */
    static TenantModuleSettings tenantSettings(MaskingPolicy policy) {
        return new TenantModuleSettings() {
            @Override
            public String module() {
                return "rules";
            }

            @Override
            public Map<String, Object> settings(String tenantId) {
                Map<String, Object> settings = new LinkedHashMap<>();
                settings.put(MASK_DEFAULT, policy.maskByDefault(tenantId));
                settings.put(REQUIRE_EXPLICIT_MASK, policy.requireExplicitMask(tenantId));
                return settings;
            }
        };
    }

    static String engineVersion() {
        try (InputStream in = RuleEngineModule.class.getResourceAsStream(VERSION_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("The Rule Engine starter is missing " + VERSION_RESOURCE
                        + "; rebuild it with Maven.");
            }
            Properties properties = new Properties();
            properties.load(in);
            return properties.getProperty("engine-version");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
