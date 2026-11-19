package io.github.dhluong90.bpm.rules.autoconfigure;

import io.github.dhluong90.bpm.host.spi.ModuleDescriptor;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
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
        // Always published, false unless the host turns it on (PD-094), so clients never guess.
        Map<String, Object> settings = Map.of(COCKPIT_DRAFTS, Boolean.TRUE.equals(cockpitDrafts));
        return new ModuleDescriptor("rules", engineVersion(), "v1", FEATURES, settings, PERMISSIONS, rolePermissions);
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
