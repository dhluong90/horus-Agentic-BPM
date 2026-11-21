package io.github.dhluong90.bpm.rules.autoconfigure;

import io.github.dhluong90.bpm.host.spi.ModuleTenantSettings;
import io.github.dhluong90.bpm.rules.internal.service.FunctionStartupCheck;
import io.github.dhluong90.bpm.rules.internal.service.HostFunctions;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * The start-up check of the host functions (RLE-058, PD-119), run by {@link RuleEngineSchemaInitializer} after the
 * migrations: one WARN for each function whose version label is not the one published rule set versions were approved
 * with, naming the function, both labels and the published versions that call it. The changes are kept for the
 * {@code rules} settings of {@code /api/bpm/meta}, which show a tenant only the changes in its own versions. A host
 * bean of this type replaces it.
 */
public final class RuleEngineFunctionCheck {

    /** The {@code /meta} setting: the version label policy in force. */
    static final String VERSION_CHANGE_SETTING = "evaluation.function-version-change";

    /** The {@code /meta} setting: changed functions that the caller's tenant's published versions call. */
    static final String CHANGED_SETTING = "evaluation.functions-changed";

    private static final Log LOG = LogFactory.getLog(RuleEngineFunctionCheck.class);

    private final Supplier<List<FunctionStartupCheck.Change>> check;
    private final AtomicReference<List<FunctionStartupCheck.Change>> changes = new AtomicReference<>(List.of());

    RuleEngineFunctionCheck(Supplier<List<FunctionStartupCheck.Change>> check) {
        this.check = Objects.requireNonNull(check, "check must not be null");
    }

    static RuleEngineFunctionCheck of(FunctionStartupCheck check) {
        Objects.requireNonNull(check, "check must not be null");
        return new RuleEngineFunctionCheck(check::changes);
    }

    void verify() {
        List<FunctionStartupCheck.Change> found = check.get();
        changes.set(found);
        for (FunctionStartupCheck.Change change : found) {
            LOG.warn("Host function " + change.function() + " was approved with version " + change.approvedVersion()
                    + " and is registered with "
                    + (change.currentVersion() == null ? "no version (it is no longer registered)"
                    : "version " + change.currentVersion()) + ". Published versions that call it: "
                    + change.describedUses() + ". "
                    + "Under evaluation.function-version-change=warn they are evaluated; each decision records the version"
                    + " it used.");
        }
    }

    /**
     * The changes found at start-up.
     *
     * @return the changes, empty before the check ran
     */
    List<FunctionStartupCheck.Change> changes() {
        return changes.get();
    }

    /**
     * The function settings of the rules module in {@code /meta}: the policy, and the changed functions of the
     * caller's tenant as {@code name:approved>current}, comma separated.
     *
     * @param policy the policy in force
     * @return the settings
     */
    ModuleTenantSettings tenantSettings(HostFunctions.VersionChange policy) {
        return new ModuleTenantSettings() {
            @Override
            public String module() {
                return "rules";
            }

            @Override
            public List<String> names() {
                return List.of(VERSION_CHANGE_SETTING, CHANGED_SETTING);
            }

            @Override
            public Map<String, Object> settings(String tenantId) {
                Map<String, Object> settings = new LinkedHashMap<>();
                settings.put(VERSION_CHANGE_SETTING, policy.name().toLowerCase(java.util.Locale.ROOT));
                settings.put(CHANGED_SETTING, String.join(",", changes.get().stream()
                        .filter(change -> change.uses().stream().anyMatch(use -> use.tenantId().equals(tenantId)))
                        .map(change -> change.function() + ":" + change.approvedVersion() + ">"
                                + (change.currentVersion() == null ? "-" : change.currentVersion()))
                        .toList()));
                return settings;
            }
        };
    }
}
