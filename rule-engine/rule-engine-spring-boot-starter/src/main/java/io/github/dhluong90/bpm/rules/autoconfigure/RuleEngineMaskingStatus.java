package io.github.dhluong90.bpm.rules.autoconfigure;

import io.github.dhluong90.bpm.host.spi.ModuleTenantSettings;
import io.github.dhluong90.bpm.rules.api.MaskingKey;
import io.github.dhluong90.bpm.rules.spi.MaskingPolicy;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The masking key status of the caller's tenant in the rules {@code settings} of {@code GET /api/bpm/meta}
 * (TR-EXT-08, TR-SEC-10): whether a key is in force ({@code set} or {@code unset}), its key id, and its check value.
 * The key is the one the tenant writes with, as the active {@link MaskingPolicy} returns it: the tenant's own key, or
 * its subkey of the host key. The key itself never leaves {@link MaskingKey}; the check value is not secret. Only the
 * caller's tenant is ever described.
 */
final class RuleEngineMaskingStatus implements ModuleTenantSettings {

    static final String KEY = "masking.hmac-key";
    static final String KEY_ID = "masking.hmac-key-id";
    static final String KEY_CHECK = "masking.hmac-key-check";
    static final String SET = "set";
    static final String UNSET = "unset";

    private final Supplier<MaskingPolicy> policy;

    RuleEngineMaskingStatus(Supplier<MaskingPolicy> policy) {
        this.policy = policy;
    }

    @Override
    public String module() {
        return "rules";
    }

    @Override
    public List<String> names() {
        return List.of(KEY, KEY_ID, KEY_CHECK);
    }

    @Override
    public Map<String, Object> settings(String tenantId) {
        Optional<MaskingKey> key = currentKey(tenantId);
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put(KEY, key.isPresent() ? SET : UNSET);
        key.ifPresent(k -> {
            settings.put(KEY_ID, k.keyId());
            settings.put(KEY_CHECK, k.checkValue());
        });
        return settings;
    }

    private Optional<MaskingKey> currentKey(String tenantId) {
        MaskingPolicy active = policy.get();
        if (active == null) {
            return Optional.empty();
        }
        try {
            return active.hmacKey(tenantId);
        } catch (IllegalArgumentException e) {
            // A tenant id the policy cannot derive a key for has no key in force; the message is not echoed.
            return Optional.empty();
        }
    }
}
