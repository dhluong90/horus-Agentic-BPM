package io.github.dhluong90.bpm.rules.autoconfigure;

import io.github.dhluong90.bpm.rules.api.MaskingKey;
import io.github.dhluong90.bpm.rules.api.PersonalDataNames;
import io.github.dhluong90.bpm.rules.internal.service.EngineDefaults;
import io.github.dhluong90.bpm.rules.spi.MaskingPolicy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The default {@link MaskingPolicy}: one host key from {@code horus.bpm.rules.masking.hmac-key} (or the shared
 * {@code horus.bpm.masking.hmac-key}), from which each tenant gets its own HKDF subkey, an optional key and masking default per tenant under
 * {@code horus.bpm.rules.masking.tenants.<tenant>}, and the host masking default from
 * {@code horus.bpm.rules.decision-log.mask-default}, and the guards against unmasked personal data: strict mode
 * ({@code decision-log.require-explicit-mask}) and the personal-data names, each overridable per tenant. A value that is not a valid key is never used and never shown:
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

    /** Strict mode and personal-data names, for the host and per tenant (PD-099). */
    private final Guards guards;

    /**
     * The guards against unmasked personal data from the properties.
     *
     * @param hostStrict {@code decision-log.require-explicit-mask}
     * @param tenantStrict tenants with their own strict mode
     * @param hostNames the host's personal-data names
     * @param tenantNames tenants with their own list
     * @param tenants every tenant named under {@code masking.tenants}
     */
    private record Guards(boolean hostStrict, Map<String, Boolean> tenantStrict, PersonalDataNames hostNames,
            Map<String, PersonalDataNames> tenantNames, Set<String> tenants) {
        Guards {
            tenantStrict = Map.copyOf(tenantStrict);
            tenantNames = Map.copyOf(tenantNames);
            tenants = Set.copyOf(tenants);
        }
    }

    private PropertyMaskingPolicy(Optional<MaskingKey> hostKey, Map<String, Optional<MaskingKey>> tenantKeys,
            boolean hostDefault, Map<String, Boolean> tenantDefaults, List<String> invalidSettings,
            int hostSearchesPerMinute, Map<String, Integer> tenantSearchesPerMinute, Guards guards) {
        this.guards = guards;
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
        checkDistinct(hostKey, tenantKeys, invalid);
        Boolean maskDefault = properties.decisionLog().maskDefault();
        return new PropertyMaskingPolicy(hostKey, tenantKeys, maskDefault == null ? EngineDefaults.MASK_DEFAULT
                : maskDefault, tenantDefaults, invalid, hostLimit, tenantLimits, guards(properties));
    }

    /**
     * Reads strict mode and the personal-data names. A tenant's {@code personal-data-names} replaces the host's list
     * for that tenant only, and {@code additional-personal-data-names} extends whichever list applies. A name that is
     * blank or too long stops start-up with a message naming the setting.
     */
    private static Guards guards(RuleEngineProperties properties) {
        RuleEngineProperties.DecisionLog log = properties.decisionLog();
        String prefix = "horus.bpm.rules.decision-log.";
        PersonalDataNames hostNames = names(PersonalDataNames.builtIn(), log.personalDataNames(),
                log.additionalPersonalDataNames(), prefix);
        Map<String, Boolean> tenantStrict = new HashMap<>();
        Map<String, PersonalDataNames> tenantNames = new HashMap<>();
        properties.masking().tenants().forEach((tenant, settings) -> {
            if (settings == null) {
                return;
            }
            if (settings.requireExplicitMask() != null) {
                tenantStrict.put(tenant, settings.requireExplicitMask());
            }
            if (settings.personalDataNames() != null || settings.additionalPersonalDataNames() != null) {
                tenantNames.put(tenant, names(hostNames, settings.personalDataNames(),
                        settings.additionalPersonalDataNames(), "horus.bpm.rules.masking.tenants." + tenant + "."));
            }
        });
        return new Guards(Boolean.TRUE.equals(log.requireExplicitMask()), tenantStrict, hostNames, tenantNames,
                properties.masking().tenants().keySet());
    }

    private static PersonalDataNames names(PersonalDataNames inherited, List<String> replace, List<String> add,
            String prefix) {
        PersonalDataNames base;
        try {
            base = replace == null ? inherited : PersonalDataNames.of(replace);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(prefix + "personal-data-names: " + e.getMessage(), e);
        }
        try {
            return add == null ? base : base.plus(add);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(prefix + "additional-personal-data-names: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean requireExplicitMask(String tenantId) {
        return guards.tenantStrict().getOrDefault(tenantId, guards.hostStrict());
    }

    @Override
    public PersonalDataNames personalDataNames(String tenantId) {
        return guards.tenantNames().getOrDefault(tenantId, guards.hostNames());
    }

    /**
     * Returns every tenant named in the masking settings, so the start-up record of the masking default covers them
     * even before they have a rule set.
     *
     * @return the tenant ids
     */
    Set<String> configuredTenants() {
        return guards.tenants();
    }

    /**
     * Refuses tenant keys that would defeat the separation of tenants or make a token's key ambiguous: a tenant key
     * equal to the host key or to another tenant's key (compared in constant time), and a tenant key whose id is
     * the host key's id. Reported by setting name, never by value; the start-up check then refuses to start.
     */
    private static void checkDistinct(Optional<MaskingKey> hostKey, Map<String, Optional<MaskingKey>> tenantKeys,
            List<String> invalid) {
        List<String> tenants = new ArrayList<>(new java.util.TreeSet<>(tenantKeys.keySet()));
        for (int i = 0; i < tenants.size(); i++) {
            String tenant = tenants.get(i);
            Optional<MaskingKey> own = tenantKeys.get(tenant);
            if (own.isEmpty()) {
                continue;
            }
            String property = "horus.bpm.rules.masking.tenants." + tenant + ".hmac-key";
            if (hostKey.isPresent() && own.get().sameKeyAs(hostKey.get())) {
                invalid.add(property + " (equals the host key; a tenant key must be a key of its own)");
            }
            if (hostKey.isPresent() && own.get().keyId().equals(hostKey.get().keyId())) {
                invalid.add(property + "-id (equals the host key id " + hostKey.get().keyId()
                        + "; give the tenant key an id of its own)");
            }
            for (int j = i + 1; j < tenants.size(); j++) {
                Optional<MaskingKey> other = tenantKeys.get(tenants.get(j));
                if (other.isPresent() && own.get().sameKeyAs(other.get())) {
                    invalid.add(property + " (equals the key of tenant " + tenants.get(j)
                            + "; every tenant key must be different)");
                }
            }
        }
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
