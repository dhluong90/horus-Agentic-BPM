package io.github.dhluong90.bpm.rules.internal.service;

import io.github.dhluong90.bpm.rules.internal.json.EngineValues;
import io.github.dhluong90.bpm.rules.internal.store.MaskedByDefaultVersions;
import io.github.dhluong90.bpm.rules.internal.store.RuleStore;
import io.github.dhluong90.bpm.rules.internal.store.VersionInputs;
import io.github.dhluong90.bpm.rules.spi.MaskingPolicy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import tools.jackson.databind.JsonNode;

/**
 * The start-up check of RLE-035: every tenant whose rule sets mask an input, by declaration or by the tenant's
 * default, must have a masking key. Runs once after the migrations, outside any request. Its message names the
 * setting, the tenant and the rule set, never a key or an input value.
 */
public final class MaskingStartupCheck {

    private static final System.Logger LOG = System.getLogger(MaskingStartupCheck.class.getName());

    private final RuleStore store;
    private final MaskingPolicy policy;

    /**
     * Creates the check.
     *
     * @param store the rule store
     * @param policy the host's masking policy
     */
    public MaskingStartupCheck(RuleStore store, MaskingPolicy policy) {
        this.store = Objects.requireNonNull(store, "store must not be null");
        this.policy = Objects.requireNonNull(policy, "policy must not be null");
    }

    /**
     * Fails when a tenant masks an input and has no valid key.
     *
     * @param invalidSettings settings that hold a value that is not a valid key (named, never shown), or empty
     * @throws IllegalStateException naming {@value EngineDefaults#MASKING_KEY_PROPERTY} and the rule sets
     */
    public void verify(List<String> invalidSettings) {
        Set<String> problems = new LinkedHashSet<>();
        for (VersionInputs version : store.liveVersionInputs()) {
            String masked = firstMaskedInput(version.inputsJson(), policy.maskByDefault(version.tenantId()));
            if (masked != null && policy.hmacKey(version.tenantId()).isEmpty()) {
                problems.add("rule set " + version.ruleSet() + " of tenant " + version.tenantId() + " masks input "
                        + masked);
            }
        }
        List<String> invalid = new ArrayList<>(invalidSettings);
        if (problems.isEmpty() && invalid.isEmpty()) {
            return;
        }
        StringBuilder message = new StringBuilder("The Rule Engine cannot start: ");
        if (!problems.isEmpty()) {
            message.append(String.join("; ", problems)).append(", but no valid masking key is configured. Set ")
                    .append(EngineDefaults.MASKING_KEY_PROPERTY)
                    .append(" (or the tenant's own key) to a Base64 key of at least 32 bytes from a secret store.");
        }
        if (!invalid.isEmpty()) {
            if (!problems.isEmpty()) {
                message.append(' ');
            }
            message.append("Not a Base64 key of at least 32 bytes: ").append(String.join(", ", invalid)).append('.');
        }
        throw new IllegalStateException(message.toString());
    }

    /** The setting whose changes are logged at start-up (PD-099 mitigation 4). */
    public static final String MASK_DEFAULT_SETTING = "decision-log.mask-default";

    /**
     * The tenant id of the row that holds the host default: empty, which no tenant id ever is (the resolvers refuse a
     * blank tenant). The host default is what a tenant without its own setting follows.
     */
    public static final String HOST_DEFAULT_ROW = "";

    /**
     * Logs every change of {@code decision-log.mask-default} since the previous start-up, with the old and the new
     * value, and records the values in force (RLE-052): one line per tenant whose value changed, and one for the host
     * default when it changed. A tenant with no record of its own is compared with the host default recorded at the
     * previous start-up, which it followed then; with no record at all it is recorded with one information line.
     * Turning masking off stops masking undeclared inputs, so that change is a warning. Run once after
     * {@link #verify}, outside any request.
     *
     * @param configuredTenants tenants named in the host's settings, besides those that have rule sets or were
     *     recorded before
     * @param at the start-up time
     * @return the lines logged: the host default first, then by tenant
     */
    public List<String> recordMaskDefaults(Set<String> configuredTenants, Instant at) {
        Map<String, String> seen = store.settingsSeen(MASK_DEFAULT_SETTING);
        String hostBefore = seen.get(HOST_DEFAULT_ROW);
        Set<String> tenants = new TreeSet<>(configuredTenants);
        tenants.addAll(seen.keySet());
        store.liveVersionInputs().forEach(version -> tenants.add(version.tenantId()));
        tenants.remove(HOST_DEFAULT_ROW);
        List<String> lines = new ArrayList<>();
        record(HOST_DEFAULT_ROW, "the host default", hostBefore, at, lines);
        for (String tenant : tenants) {
            record(tenant, "tenant " + tenant, seen.getOrDefault(tenant, hostBefore), at, lines);
        }
        return lines;
    }

    private void record(String tenant, String label, String before, Instant at, List<String> lines) {
        String now = Boolean.toString(policy.maskByDefault(tenant));
        if (now.equals(before)) {
            store.recordSettingSeen(tenant, MASK_DEFAULT_SETTING, now, at);
            return;
        }
        String line;
        System.Logger.Level level;
        if (before == null) {
            line = MASK_DEFAULT_SETTING + " for " + label + " is " + now + " (first recorded)";
            level = System.Logger.Level.INFO;
        } else {
            line = MASK_DEFAULT_SETTING + " for " + label + " changed from " + before + " to " + now
                    + " since the last start-up";
            level = Boolean.parseBoolean(now) ? System.Logger.Level.INFO : System.Logger.Level.WARNING;
        }
        LOG.log(level, line);
        lines.add(line);
        if (!tenant.equals(HOST_DEFAULT_ROW) && "true".equals(before) && "false".equals(now)) {
            affectedVersions(tenant).ifPresent(affected -> {
                LOG.log(System.Logger.Level.WARNING, affected);
                lines.add(affected);
            });
        }
        store.recordSettingSeen(tenant, MASK_DEFAULT_SETTING, now, at);
    }

    /** Most versions the WARN after a masking default turned off lists. */
    static final int AFFECTED_VERSIONS_MAX = 20;

    /**
     * After a tenant's masking default goes from true to false, names what is affected (PD-104, security review of
     * #316): the tenant's published versions whose latest approval recorded inputs masked only by the default. Their
     * values reach the decision log in plain text from now on. At most {@value #AFFECTED_VERSIONS_MAX}, then
     * "and N more". One read-only query.
     */
    private java.util.Optional<String> affectedVersions(String tenant) {
        MaskedByDefaultVersions affected = store.publishedMaskedByDefault(tenant, AFFECTED_VERSIONS_MAX);
        if (affected.versions().isEmpty()) {
            return java.util.Optional.empty();
        }
        StringBuilder line = new StringBuilder("Tenant ").append(tenant)
                .append(" no longer masks undeclared inputs; these published versions now log in plain text inputs"
                        + " that only the default masked: ");
        line.append(affected.versions().stream()
                .map(v -> v.ruleSet() + " v" + v.version() + " " + v.inputs())
                .collect(java.util.stream.Collectors.joining(", ")));
        if (affected.total() > affected.versions().size()) {
            line.append(" and ").append(affected.total() - affected.versions().size()).append(" more");
        }
        return java.util.Optional.of(line.toString());
    }

    /** The first input of a version that is masked, or null. */
    static String firstMaskedInput(String inputsJson, boolean maskByDefault) {
        if (inputsJson == null) {
            return null;
        }
        JsonNode inputs = EngineValues.read(inputsJson);
        for (JsonNode input : inputs) {
            JsonNode mask = input.get("mask");
            boolean masked = mask == null || mask.isNull() ? maskByDefault : mask.asBoolean();
            if (masked) {
                return input.get("name").stringValue();
            }
        }
        return null;
    }
}
