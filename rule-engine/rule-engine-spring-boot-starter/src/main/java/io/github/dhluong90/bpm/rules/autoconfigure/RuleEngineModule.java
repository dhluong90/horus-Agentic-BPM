package io.github.dhluong90.bpm.rules.autoconfigure;

import io.github.dhluong90.bpm.host.autoconfigure.ApproverMustDifferFrom;
import io.github.dhluong90.bpm.host.spi.ModuleDescriptor;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * The Rule Engine's entry in the host contract: {@code rules}, REST API {@code v1}, the engine version of
 * this build and the permissions of ADR-0010, with {@code rules:pin} (pinned evaluation, held by no role by default).
 */
final class RuleEngineModule {

    /** Every permission the Rule Engine checks, in the order clients show them (ADR-0010). */
    static final List<String> PERMISSIONS = List.of(
            "rules:evaluate", "rules:read", "rules:author", "rules:approve", "rules:simulate", "rules:log:read",
            "rules:masked:search", "rules:pin");

    /**
     * Feature flags turned on in this build. None yet: a flag is added with the feature it announces, so a
     * client never offers a screen whose endpoints are missing.
     */
    static final List<String> FEATURES = List.of();

    /** The rules setting clients read to offer or hide draft editing (PD-075, PD-094). */
    static final String COCKPIT_DRAFTS = "authoring.cockpit-drafts";

    /** The rules setting clients read to show how a retirement is approved (PD-080, ADR-0025). */
    static final String RETIRE_APPROVAL = io.github.dhluong90.bpm.host.autoconfigure.RetireApproval.META_SETTING;

    /** The rules setting clients read before retiring the last version in effect (PD-101). */
    static final String RETIRE_LAST_VERSION = io.github.dhluong90.bpm.host.autoconfigure.RetireLastVersion.META_SETTING;

    private static final String VERSION_RESOURCE = "rule-engine.properties";

    private RuleEngineModule() {
    }

    /**
     * The descriptor for this host.
     *
     * @param rolePermissions {@code horus.bpm.rules.security.role-permissions}
     * @param cockpitDrafts {@code horus.bpm.rules.authoring.cockpit-drafts}; null (unset) is published as false
     * @param retireApproval the validated {@code horus.bpm.lifecycle.retire-approval}
     * @param retireLastVersion the validated {@code horus.bpm.lifecycle.retire-last-version}
     * @throws IllegalArgumentException when the mapping grants a permission the engine does not check
     */
    static ModuleDescriptor descriptor(Map<String, List<String>> rolePermissions, Boolean cockpitDrafts,
            String retireApproval, String retireLastVersion) {
        // Always published, so clients never guess: draft editing false unless the host turns it on (PD-094), and
        // the retirement mode in force (PD-080). The approver rule is always makers here, whatever the host sets
        // (#273): V2 rle_version_four_eyes refuses an approval by the author in the database.
        Map<String, Object> settings = new java.util.LinkedHashMap<>();
        settings.put(COCKPIT_DRAFTS, Boolean.TRUE.equals(cockpitDrafts));
        settings.put(ApproverMustDifferFrom.META_SETTING, ApproverMustDifferFrom.MAKERS.value());
        settings.put(RETIRE_APPROVAL, retireApproval);
        settings.put(RETIRE_LAST_VERSION, retireLastVersion);
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
