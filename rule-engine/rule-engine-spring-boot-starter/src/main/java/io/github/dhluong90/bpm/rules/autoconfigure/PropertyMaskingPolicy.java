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
 * {@code horus.bpm.masking.hmac-key}), from which each tenant gets its own HKDF subkey, an optional key and masking default per tenant under
 * {@code horus.bpm.rules.masking.tenants.<tenant>}, and the host masking default from
 * {@code horus.bpm.rules.decision-log.mask-default}. A value that is not a valid key is never used and never shown:
 * it is reported by setting name to the start-up check, which then refuses to start. Immutable and thread-safe.
 */
final class PropertyMaskingPolicy implements MaskingPolicy {

    static final String SHARED_KEY_PROPERTY = "horus.bpm.masking.hmac-key";
    static final String SHARED_KEY_ID_PROPERTY = "horus.bpm.masking.hmac-key-id";

    private final Optional<MaskingKey> hostKey;
    private final Map<String, Optional<MaskingKey>> tenantKeys;
    private final boolean hostDefault;
    private final Map<String, Boolean> tenantDefaults;
    private final List<String> invalidSettings;
    private final int hostSearchesPerMinute;
    private final Map<String, Integer> tenantSearchesPerMinute;

    private PropertyMaskingPolicy(Optional<MaskingKey> hostKey, Map<String, Optional<MaskingKey>> tenantKeys,
            boolean hostDefault, Map<String, Boolean> tenantDefaults, List<String> invalidSettings,
            int hostSearchesPerMinute, Map<String, Integer> tenantSearchesPerMinute) {
        this.hostSearchesPerMinute = hostSearchesPerMinute;
        this.tenantSearchesPerMinute = Map.copyOf(tenantSearchesPerMinute);
        this.hostKey = hostKey;
        this.tenantKeys = Map.copyOf(tenantKeys);
        this.hostDefault = hostDefault;
        this.tenantDefaults = Map.copyOf(tenantDefaults);
        this.invalidSettings = List.copyOf(invalidSettings);
    }

    static PropertyMaskingPolicy from(RuleEngineProperties properties, String sharedKey) {
        return from(properties, sharedKey, null);
    }

    static PropertyMaskingPolicy from(RuleEngineProperties properties, String sharedKey, String sharedKeyId) {
        List<String> invalid = new ArrayList<>();
        RuleEngineProperties.Masking masking = properties.masking();
        String hostText = masking.hmacKey() != null ? masking.hmacKey() : sharedKey;
        String hostProperty = masking.hmacKey() != null ? EngineDefaults.MASKING_KEY_PROPERTY : SHARED_KEY_PROPERTY;
        String hostKeyId = masking.hmacKey() != null ? masking.hmacKeyId()
                : masking.hmacKeyId() != null ? masking.hmacKeyId() : sharedKeyId;
        Optional<MaskingKey> hostKey = decode(hostText, hostKeyId, hostProperty, invalid);
        Map<String, Optional<MaskingKey>> tenantKeys = new HashMap<>();
        Map<String, Boolean> tenantDefaults = new HashMap<>();
        Map<String, Integer> tenantLimits = new HashMap<>();
        int hostLimit = searchLimit(masking.search(), "horus.bpm.rules.masking.search.max-per-minute");
        masking.tenants().forEach((tenant, settings) -> {
            if (settings == null) {
                return;
            }
            if (settings.hmacKey() != null) {
                tenantKeys.put(tenant, decode(settings.hmacKey(), settings.hmacKeyId(),
                        "horus.bpm.rules.masking.tenants." + tenant + ".hmac-key", invalid));
            }
            if (settings.maskDefault() != null) {
                tenantDefaults.put(tenant, settings.maskDefault());
            }
            if (settings.search() != null && settings.search().maxPerMinute() != null) {
                tenantLimits.put(tenant, searchLimit(settings.search(),
                        "horus.bpm.rules.masking.tenants." + tenant + ".search.max-per-minute"));
            }
        });
        Boolean maskDefault = properties.decisionLog().maskDefault();
        return new PropertyMaskingPolicy(hostKey, tenantKeys, maskDefault == null ? EngineDefaults.MASK_DEFAULT
                : maskDefault, tenantDefaults, invalid, hostLimit, tenantLimits);
    }

    /** A search limit below 1 stops start-up: the search cannot be left unlimited. */
    private static int searchLimit(RuleEngineProperties.Search search, String property) {
        Integer value = search == null ? null : search.maxPerMinute();
        if (value == null) {
            return DEFAULT_SEARCHES_PER_MINUTE;
        }
        if (value < 1) {
            throw new IllegalArgumentException(property + " must be at least 1 (the masked-input search cannot be"
                    + " unlimited), was " + value);
        }
        return value;
    }

    @Override
    public int searchesPerMinute(String tenantId) {
        return tenantSearchesPerMinute.getOrDefault(tenantId, hostSearchesPerMinute);
    }

    private static Optional<MaskingKey> decode(String text, String keyId, String property, List<String> invalid) {
        if (text == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(MaskingKey.fromBase64(keyId == null ? MaskingKey.DEFAULT_KEY_ID : keyId, text));
        } catch (IllegalArgumentException e) {
            // The message names the property and the rule, never the value.
            invalid.add(property + " (" + e.getMessage() + ")");
            return Optional.empty();
        }
    }

    /** Tenant subkeys of the host key, derived once per tenant; bounded, then derived on each call. */
    private final java.util.concurrent.ConcurrentHashMap<String, MaskingKey> derived =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final int DERIVED_CACHE_MAX = 10_000;

    @Override
    public Optional<MaskingKey> hmacKey(String tenantId) {
        // A tenant with its own key setting uses it as it is and never falls back to the host key, even when its own
        // key is invalid. Every other tenant gets its own HKDF subkey of the host key, never the host key itself.
        if (tenantKeys.containsKey(tenantId)) {
            return tenantKeys.get(tenantId);
        }
        return hostKey.map(host -> {
            MaskingKey cached = derived.get(tenantId);
            if (cached != null) {
                return cached;
            }
            MaskingKey subkey = host.forTenant(tenantId);
            if (derived.size() < DERIVED_CACHE_MAX) {
                derived.put(tenantId, subkey);
            }
            return subkey;
        });
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
