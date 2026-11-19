package io.github.dhluong90.bpm.rules.autoconfigure;

import io.github.dhluong90.bpm.rules.api.MaskingKey;
import io.github.dhluong90.bpm.rules.internal.service.EngineDefaults;
import io.github.dhluong90.bpm.rules.spi.MaskingPolicy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The default {@link MaskingPolicy}: one host key from {@code horus.bpm.rules.masking.hmac-key} (or the shared
 * {@code horus.bpm.masking.hmac-key}), an optional key and masking default per tenant under
 * {@code horus.bpm.rules.masking.tenants.<tenant>}, and the host masking default from
 * {@code horus.bpm.rules.decision-log.mask-default}. A value that is not a valid key is never used and never shown:
 * it is reported by setting name to the start-up check, which then refuses to start. Immutable and thread-safe.
 */
final class PropertyMaskingPolicy implements MaskingPolicy {

    static final String SHARED_KEY_PROPERTY = "horus.bpm.masking.hmac-key";

    private final Optional<MaskingKey> hostKey;
    private final Map<String, Optional<MaskingKey>> tenantKeys;
    private final boolean hostDefault;
    private final Map<String, Boolean> tenantDefaults;
    private final List<String> invalidSettings;

    private PropertyMaskingPolicy(Optional<MaskingKey> hostKey, Map<String, Optional<MaskingKey>> tenantKeys,
            boolean hostDefault, Map<String, Boolean> tenantDefaults, List<String> invalidSettings) {
        this.hostKey = hostKey;
        this.tenantKeys = Map.copyOf(tenantKeys);
        this.hostDefault = hostDefault;
        this.tenantDefaults = Map.copyOf(tenantDefaults);
        this.invalidSettings = List.copyOf(invalidSettings);
    }

    static PropertyMaskingPolicy from(RuleEngineProperties properties, String sharedKey) {
        List<String> invalid = new ArrayList<>();
        RuleEngineProperties.Masking masking = properties.masking();
        String hostText = masking.hmacKey() != null ? masking.hmacKey() : sharedKey;
        String hostProperty = masking.hmacKey() != null ? EngineDefaults.MASKING_KEY_PROPERTY : SHARED_KEY_PROPERTY;
        Optional<MaskingKey> hostKey = decode(hostText, hostProperty, invalid);
        Map<String, Optional<MaskingKey>> tenantKeys = new HashMap<>();
        Map<String, Boolean> tenantDefaults = new HashMap<>();
        masking.tenants().forEach((tenant, settings) -> {
            if (settings == null) {
                return;
            }
            if (settings.hmacKey() != null) {
                tenantKeys.put(tenant, decode(settings.hmacKey(),
                        "horus.bpm.rules.masking.tenants." + tenant + ".hmac-key", invalid));
            }
            if (settings.maskDefault() != null) {
                tenantDefaults.put(tenant, settings.maskDefault());
            }
        });
        Boolean maskDefault = properties.decisionLog().maskDefault();
        return new PropertyMaskingPolicy(hostKey, tenantKeys, maskDefault == null ? EngineDefaults.MASK_DEFAULT
                : maskDefault, tenantDefaults, invalid);
    }

    private static Optional<MaskingKey> decode(String text, String property, List<String> invalid) {
        if (text == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(MaskingKey.fromBase64(text));
        } catch (IllegalArgumentException e) {
            // The message names the property and the rule, never the value.
            invalid.add(property + " (" + e.getMessage() + ")");
            return Optional.empty();
        }
    }

    @Override
    public Optional<MaskingKey> hmacKey(String tenantId) {
        // A tenant with its own key setting never falls back to the host key, even when its own key is invalid.
        return tenantKeys.getOrDefault(tenantId, hostKey);
    }

    @Override
    public boolean maskByDefault(String tenantId) {
        return tenantDefaults.getOrDefault(tenantId, hostDefault);
    }

    /**
     * Returns the settings that hold a value that is not a valid key, each with the reason.
     *
     * @return the setting names, never their values
     */
    List<String> invalidSettings() {
        return invalidSettings;
    }

    @Override
    public String toString() {
        return "PropertyMaskingPolicy[hostKey=" + (hostKey.isPresent() ? "set" : "unset") + ", tenants="
                + tenantKeys.keySet() + "]";
    }
}
