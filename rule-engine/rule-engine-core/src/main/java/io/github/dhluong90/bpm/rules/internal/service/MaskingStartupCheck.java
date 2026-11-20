package io.github.dhluong90.bpm.rules.internal.service;

import io.github.dhluong90.bpm.rules.api.MaskingKey;
import io.github.dhluong90.bpm.rules.internal.json.EngineValues;
import io.github.dhluong90.bpm.rules.internal.store.RuleStore;
import io.github.dhluong90.bpm.rules.internal.store.TransactionRunner;
import io.github.dhluong90.bpm.rules.internal.store.VersionInputs;
import io.github.dhluong90.bpm.rules.spi.MaskingPolicy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import tools.jackson.databind.JsonNode;

/**
 * The start-up check of RLE-035: every tenant whose rule sets mask an input, by declaration or by the tenant's
 * default, must have a masking key. Then, when built with a transaction runner, the check of TR-SEC-10 on key ids:
 * each configured key's check value is recorded by scope and key id ({@code rle_masking_key_check}), and a known key
 * id that comes back with a different check value stops start-up, so one key id never names two keys across replicas
 * or over time. Runs once after the migrations, outside any request. Its messages name the setting, the tenant, the
 * rule set, the key id and check values, never a key or an input value.
 */
public final class MaskingStartupCheck {

    private final RuleStore store;
    private final MaskingPolicy policy;
    private final TransactionRunner transactions;
    private final Clock clock;

    /**
     * Creates the check without the key id check.
     *
     * @param store the rule store
     * @param policy the host's masking policy
     */
    public MaskingStartupCheck(RuleStore store, MaskingPolicy policy) {
        this.store = Objects.requireNonNull(store, "store must not be null");
        this.policy = Objects.requireNonNull(policy, "policy must not be null");
        this.transactions = null;
        this.clock = null;
    }

    /**
     * Creates the check with the key id check, which records check values in one short transaction.
     *
     * @param store the rule store
     * @param policy the host's masking policy
     * @param transactions runs the recording transaction
     * @param clock the time of first sight of a key id
     */
    public MaskingStartupCheck(RuleStore store, MaskingPolicy policy, TransactionRunner transactions, Clock clock) {
        this.store = Objects.requireNonNull(store, "store must not be null");
        this.policy = Objects.requireNonNull(policy, "policy must not be null");
        this.transactions = Objects.requireNonNull(transactions, "transactions must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
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
            verifyKeyIds();
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

    /**
     * Records the check value of every configured key by scope and key id, and fails when a known id comes back with
     * a different check value. In one transaction that a mismatch rolls back, so a refused start records nothing.
     */
    private void verifyKeyIds() {
        if (transactions == null) {
            return;
        }
        Map<String, List<MaskingKey>> byScope = new TreeMap<>(policy.configuredKeys());
        if (byScope.isEmpty()) {
            return;
        }
        transactions.inTransaction(() -> {
            List<String> mismatches = new ArrayList<>();
            byScope.forEach((scope, keys) -> {
                for (MaskingKey key : keys) {
                    String configured = key.checkValue();
                    String recorded = store.maskingKeyCheck(scope, key.keyId(), configured, clock.instant());
                    if (!MessageDigest.isEqual(recorded.getBytes(StandardCharsets.US_ASCII),
                            configured.getBytes(StandardCharsets.US_ASCII))) {
                        mismatches.add(describe(scope, key.keyId()) + " was first recorded with check value " + recorded
                                + ", but the key configured now has check value " + configured);
                    }
                }
            });
            if (!mismatches.isEmpty()) {
                throw new IllegalStateException("The Rule Engine cannot start: " + String.join("; ", mismatches)
                        + ". A key id names one key for good, so tokens made under it stay findable: restore the"
                        + " key that id was first used with, or give the new key an id of its own.");
            }
            return null;
        });
    }

    private static String describe(String scope, String keyId) {
        return MaskingPolicy.HOST_SCOPE.equals(scope)
                ? "host masking key id " + keyId + " (" + EngineDefaults.MASKING_KEY_PROPERTY + "-id or previous-keys)"
                : "masking key id " + keyId + " of tenant " + scope + " (horus.bpm.rules.masking.tenants." + scope
                        + ")";
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
