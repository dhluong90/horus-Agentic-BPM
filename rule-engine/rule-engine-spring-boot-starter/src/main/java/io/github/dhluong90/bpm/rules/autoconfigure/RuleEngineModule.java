package io.github.dhluong90.bpm.rules.autoconfigure;

import io.github.dhluong90.bpm.host.autoconfigure.ApproverMustDifferFrom;
import io.github.dhluong90.bpm.host.spi.ModuleDescriptor;
import io.github.dhluong90.bpm.host.spi.ModuleTenantSettings;
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
 * this build and the permissions of ADR-0010, with {@code rules:pin} (pinned evaluation, held by no role by default).
 */
final class RuleEngineModule {

    /** Every permission the Rule Engine checks, in the order clients show them (ADR-0010). */
    static final List<String> PERMISSIONS = List.of(
            "rules:evaluate", "rules:read", "rules:author", "rules:approve", "rules:simulate", "rules:log:read",
            "rules:masked:search", "rules:pin");

    /**
     * Feature flags turned on in this build. A flag is added with the feature it announces, so a client never offers
     * a screen whose endpoints are missing: {@code rules.templates} (GET /rule-set-templates),
     * {@code rules.simulation-reports} (the report endpoints and {@code simulationReportId}) and
     * {@code rules.history-readings} ({@code :simulate-history-readings}).
     */
    static final List<String> FEATURES = List.of("rules.history-readings", "rules.simulation-reports",
            "rules.templates");

    /**
     * The feature announced while {@code horus.bpm.rules.authoring.condition-builder} is on (default): the nested
     * And, Or and Not condition builder of the cockpit (#501). A host that does not want the lens sets it false.
     */
    static final String CONDITION_BUILDER_FEATURE = "rules.condition-builder";

    /** The rules setting clients read to offer or hide draft editing (PD-075, PD-094). */
    static final String COCKPIT_DRAFTS = "authoring.cockpit-drafts";

    /** The warning codes an approval must acknowledge (PD-104, RLE-053), comma-separated; empty when none. */
    static final String ACKNOWLEDGE_WARNINGS = "lifecycle.acknowledge-warnings";

    /**
     * The rule sets whose publication request must carry a simulation report (PD-115, RLE-201), comma-separated; empty
     * when none. Clients read it to offer Request publish only after a report was run.
     */
    static final String REQUIRE_SIMULATION_REPORT = "lifecycle.require-simulation-report";

    /** Whether an approval or rejection must name the reviewed content hash (RLE-084), published for clients. */
    static final String REQUIRE_CONTENT_HASH = "approval.require-content-hash";

    /** The masking default in force for the caller's tenant (PD-099 mitigation 4, RLE-052). */
    static final String MASK_DEFAULT = "decision-log.mask-default";

    /** Strict mode in force for the caller's tenant (PD-099 mitigation 2, RLE-050). */
    static final String REQUIRE_EXPLICIT_MASK = "decision-log.require-explicit-mask";
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
        return descriptor(rolePermissions, cockpitDrafts, retireApproval, retireLastVersion,
                io.github.dhluong90.bpm.rules.internal.service.EngineDefaults.ACKNOWLEDGE_WARNINGS);
    }

    /**
     * The descriptor for this host.
     *
     * @param rolePermissions {@code horus.bpm.rules.security.role-permissions}
     * @param cockpitDrafts {@code horus.bpm.rules.authoring.cockpit-drafts}; null (unset) is published as false
     * @param retireApproval the validated {@code horus.bpm.lifecycle.retire-approval}
     * @param retireLastVersion the validated {@code horus.bpm.lifecycle.retire-last-version}
     * @param acknowledgeWarnings the validated {@code lifecycle.acknowledge-warnings}, published comma-separated
     *     because {@code /meta} settings hold scalars only
     * @throws IllegalArgumentException when the mapping grants a permission the engine does not check
     */
    static ModuleDescriptor descriptor(Map<String, List<String>> rolePermissions, Boolean cockpitDrafts,
            String retireApproval, String retireLastVersion, List<String> acknowledgeWarnings) {
        return descriptor(rolePermissions, cockpitDrafts, retireApproval, retireLastVersion, acknowledgeWarnings,
                io.github.dhluong90.bpm.rules.internal.service.EngineDefaults.REQUIRE_CONTENT_HASH);
    }

    /**
     * The descriptor for this host.
     *
     * @param rolePermissions {@code horus.bpm.rules.security.role-permissions}
     * @param cockpitDrafts {@code horus.bpm.rules.authoring.cockpit-drafts}; null (unset) is published as false
     * @param retireApproval the validated {@code horus.bpm.lifecycle.retire-approval}
     * @param retireLastVersion the validated {@code horus.bpm.lifecycle.retire-last-version}
     * @param acknowledgeWarnings the validated {@code lifecycle.acknowledge-warnings}
     * @param requireContentHash the validated {@code approval.require-content-hash}
     * @throws IllegalArgumentException when the mapping grants a permission the engine does not check
     */
    static ModuleDescriptor descriptor(Map<String, List<String>> rolePermissions, Boolean cockpitDrafts,
            String retireApproval, String retireLastVersion, List<String> acknowledgeWarnings,
            boolean requireContentHash) {
        return descriptor(rolePermissions, cockpitDrafts, retireApproval, retireLastVersion, acknowledgeWarnings,
                requireContentHash, io.github.dhluong90.bpm.rules.internal.service.EngineDefaults.REQUIRE_SIMULATION_REPORT);
    }

    /**
     * The descriptor for this host.
     *
     * @param rolePermissions {@code horus.bpm.rules.security.role-permissions}
     * @param cockpitDrafts {@code horus.bpm.rules.authoring.cockpit-drafts}; null (unset) is published as false
     * @param retireApproval the validated {@code horus.bpm.lifecycle.retire-approval}
     * @param retireLastVersion the validated {@code horus.bpm.lifecycle.retire-last-version}
     * @param acknowledgeWarnings the validated {@code lifecycle.acknowledge-warnings}
     * @param requireContentHash the validated {@code approval.require-content-hash}
     * @param requireSimulationReport the validated {@code lifecycle.require-simulation-report}, published
     *     comma-separated
     * @throws IllegalArgumentException when the mapping grants a permission the engine does not check
     */
    static ModuleDescriptor descriptor(Map<String, List<String>> rolePermissions, Boolean cockpitDrafts,
            String retireApproval, String retireLastVersion, List<String> acknowledgeWarnings,
            boolean requireContentHash, List<String> requireSimulationReport) {
        return descriptor(rolePermissions, cockpitDrafts, retireApproval, retireLastVersion, acknowledgeWarnings,
                requireContentHash, requireSimulationReport, true);
    }

    /**
     * The descriptor for this host.
     *
     * @param rolePermissions {@code horus.bpm.rules.security.role-permissions}
     * @param cockpitDrafts {@code horus.bpm.rules.authoring.cockpit-drafts}; null (unset) is published as false
     * @param retireApproval the validated {@code horus.bpm.lifecycle.retire-approval}
     * @param retireLastVersion the validated {@code horus.bpm.lifecycle.retire-last-version}
     * @param acknowledgeWarnings the validated {@code lifecycle.acknowledge-warnings}
     * @param requireContentHash the validated {@code approval.require-content-hash}
     * @param requireSimulationReport the validated {@code lifecycle.require-simulation-report}
     * @param conditionBuilder whether {@code rules.condition-builder} is announced
     *     ({@code horus.bpm.rules.authoring.condition-builder})
     * @throws IllegalArgumentException when the mapping grants a permission the engine does not check
     */
    static ModuleDescriptor descriptor(Map<String, List<String>> rolePermissions, Boolean cockpitDrafts,
            String retireApproval, String retireLastVersion, List<String> acknowledgeWarnings,
            boolean requireContentHash, List<String> requireSimulationReport, boolean conditionBuilder) {
        // Always published, so clients never guess: draft editing false unless the host turns it on (PD-094), and
        // the retirement mode in force (PD-080). The approver rule is always makers here, whatever the host sets
        // (#273): V2 rle_version_four_eyes refuses an approval by the author in the database.
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put(COCKPIT_DRAFTS, Boolean.TRUE.equals(cockpitDrafts));
        settings.put(ApproverMustDifferFrom.META_SETTING, ApproverMustDifferFrom.MAKERS.value());
        settings.put(RETIRE_APPROVAL, retireApproval);
        settings.put(RETIRE_LAST_VERSION, retireLastVersion);
        settings.put(ACKNOWLEDGE_WARNINGS, String.join(",", acknowledgeWarnings));
        settings.put(REQUIRE_CONTENT_HASH, requireContentHash);
        settings.put(REQUIRE_SIMULATION_REPORT, String.join(",", requireSimulationReport));
        return ModuleDescriptor.builder("rules", engineVersion(), "v1")
                .features(features(conditionBuilder))
                .settings(settings)
                .permissions(PERMISSIONS)
                .rolePermissions(rolePermissions)
                .build();
    }

    private static List<String> features(boolean conditionBuilder) {
        if (!conditionBuilder) {
            return FEATURES;
        }
        List<String> all = new java.util.ArrayList<>(FEATURES);
        all.add(CONDITION_BUILDER_FEATURE);
        return List.copyOf(all);
    }

    /**
     * The rules settings that depend on the caller's tenant, read from the masking policy on each {@code /meta}
     * request: the masking default and strict mode in force. Both are always present and boolean.
     *
     * @param policy the host's masking policy
     * @return the tenant settings of the {@code rules} module
     */
    static ModuleTenantSettings tenantSettings(MaskingPolicy policy) {
        return new ModuleTenantSettings() {
            @Override
            public String module() {
                return "rules";
            }

            @Override
            public List<String> names() {
                return List.of(MASK_DEFAULT, REQUIRE_EXPLICIT_MASK);
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
