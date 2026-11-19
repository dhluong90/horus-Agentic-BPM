package io.github.dhluong90.bpm.rules.internal.service;

import io.github.dhluong90.bpm.rules.internal.json.EngineValues;
import io.github.dhluong90.bpm.rules.internal.store.RuleStore;
import io.github.dhluong90.bpm.rules.internal.store.VersionInputs;
import io.github.dhluong90.bpm.rules.spi.MaskingPolicy;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/**
 * The start-up check of RLE-035: every tenant whose rule sets mask an input, by declaration or by the tenant's
 * default, must have a masking key. Runs once after the migrations, outside any request. Its message names the
 * setting, the tenant and the rule set, never a key or an input value.
 */
public final class MaskingStartupCheck {

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
