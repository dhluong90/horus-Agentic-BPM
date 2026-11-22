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
 * {@code horus.bpm.rules.decision-log.mask-default}, the guards against unmasked personal data: strict mode
 * ({@code decision-log.require-explicit-mask}) and the personal-data names, each overridable per tenant, and earlier
 * keys for search only under {@code masking.previous-keys} and {@code masking.tenants.<tenant>.previous-keys}
 * (RLE-012). A value that is not a valid key is never used and never shown: it is reported by setting name to the
 * start-up check, which then refuses to start. Immutable and thread-safe.
 */
final class PropertyMaskingPolicy implements MaskingPolicy {

    static final String SHARED_KEY_PROPERTY = "horus.bpm.masking.hmac-key";
    static final String SHARED_KEY_ID_PROPERTY = "horus.bpm.masking.hmac-key-id";
    private static final String PREFIX = "horus.bpm.rules.masking.";

    private final Optional<MaskingKey> hostKey;
    private final Map<String, Optional<MaskingKey>> tenantKeys;
    private final boolean hostDefault;
    private final Map<String, Boolean> tenantDefaults;
    private final List<String> invalidSettings;
    private final int hostSearchesPerMinute;
    private final Map<String, Integer> tenantSearchesPerMinute;
    private final List<MaskingKey> hostPrevious;
    private final Map<String, List<MaskingKey>> tenantPrevious;

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
            Map<String, PersonalDataNames> tenantNames, Set<String> tenants, boolean warnUnclassified) {
        Guards {
            tenantStrict = Map.copyOf(tenantStrict);
            tenantNames = Map.copyOf(tenantNames);
            tenants = Set.copyOf(tenants);
        }
    }

    private PropertyMaskingPolicy(Optional<MaskingKey> hostKey, Map<String, Optional<MaskingKey>> tenantKeys,
            boolean hostDefault, Map<String, Boolean> tenantDefaults, List<String> invalidSettings,
            int hostSearchesPerMinute, Map<String, Integer> tenantSearchesPerMinute, Guards guards,
            List<MaskingKey> hostPrevious, Map<String, List<MaskingKey>> tenantPrevious) {
        this.guards = guards;
        this.hostPrevious = List.copyOf(hostPrevious);
        Map<String, List<MaskingKey>> previous = new HashMap<>();
        tenantPrevious.forEach((tenant, keys) -> previous.put(tenant, List.copyOf(keys)));
        this.tenantPrevious = Map.copyOf(previous);
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
        List<Entry> hostSet = new ArrayList<>();
        hostKey.ifPresent(key -> hostSet.add(new Entry(hostProperty, "the host key", key, true)));
        List<MaskingKey> hostPrevious = previous(masking.previousKeys(), PREFIX + "previous-keys", "the previous host key",
                invalid, hostSet);
        Map<String, List<Entry>> tenantSets = new java.util.TreeMap<>();
        Map<String, List<MaskingKey>> tenantPrevious = new HashMap<>();
        Map<String, Optional<MaskingKey>> tenantKeys = new HashMap<>();
        Map<String, Boolean> tenantDefaults = new HashMap<>();
        Map<String, Integer> tenantLimits = new HashMap<>();
        int hostLimit = searchLimit(masking.search(), "horus.bpm.rules.masking.search.max-per-minute");
        masking.tenants().forEach((tenant, settings) -> {
            if (settings == null) {
                return;
            }
            if (HOST_SCOPE.equals(tenant)) {
                // '*' is the scope of the host keys in rle_masking_key_check; a tenant so named would share its rows.
                invalid.add(PREFIX + "tenants.[" + HOST_SCOPE + "] (the tenant id " + HOST_SCOPE
                        + " is reserved for the host keys; give the tenant its real id)");
                return;
            }
            List<Entry> tenantSet = new ArrayList<>();
            if (settings.hmacKey() != null) {
                String property = PREFIX + "tenants." + tenant + ".hmac-key";
                Optional<MaskingKey> own = decode(settings.hmacKey(), settings.hmacKeyId(), property, invalid);
                tenantKeys.put(tenant, own);
                own.ifPresent(key -> tenantSet.add(new Entry(property, "the key of tenant " + tenant, key, true)));
            }
            List<MaskingKey> earlier = previous(settings.previousKeys(), PREFIX + "tenants." + tenant + ".previous-keys",
                    "the previous key of tenant " + tenant, invalid, tenantSet);
            if (!earlier.isEmpty()) {
                tenantPrevious.put(tenant, earlier);
            }
            if (!tenantSet.isEmpty()) {
                tenantSets.put(tenant, tenantSet);
            }
            if (settings.maskDefault() != null) {
                tenantDefaults.put(tenant, settings.maskDefault());
            }
            if (settings.search() != null && settings.search().maxPerMinute() != null) {
                tenantLimits.put(tenant, searchLimit(settings.search(),
                        "horus.bpm.rules.masking.tenants." + tenant + ".search.max-per-minute"));
            }
        });
        checkDistinct(hostSet, tenantSets, invalid);
        Boolean maskDefault = properties.decisionLog().maskDefault();
        return new PropertyMaskingPolicy(hostKey, tenantKeys, maskDefault == null ? EngineDefaults.MASK_DEFAULT
                : maskDefault, tenantDefaults, invalid, hostLimit, tenantLimits, guards(properties), hostPrevious,
                tenantPrevious);
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
                properties.masking().tenants().keySet(),
                properties.classification() == null || properties.classification().warnUnclassified());
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

    /** {@code horus.bpm.rules.classification.warn-unclassified}, one value for the deployment (PD-146). */
    @Override
    public boolean warnUnclassified(String tenantId) {
        return guards.warnUnclassified();
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

    /** One configured key with the setting it came from, for the distinctness checks. */
    private record Entry(String property, String label, MaskingKey key, boolean current) {
    }

    /**
     * Decodes earlier keys; each needs its own id, because the id is what its tokens carry. Invalid entries are
     * reported by setting name and left out.
     */
    private static List<MaskingKey> previous(List<RuleEngineProperties.PreviousKey> configured, String prefix,
            String label, List<String> invalid, List<Entry> set) {
        List<MaskingKey> keys = new ArrayList<>();
        for (int i = 0; i < configured.size(); i++) {
            RuleEngineProperties.PreviousKey previous = configured.get(i);
            String property = prefix + "[" + i + "].hmac-key";
            if (previous == null || previous.hmacKey() == null) {
                invalid.add(property + " (a previous key needs the key itself)");
                continue;
            }
            if (previous.hmacKeyId() == null) {
                invalid.add(property + "-id (a previous key needs the id its tokens carry)");
                continue;
            }
            Optional<MaskingKey> key = decode(previous.hmacKey(), previous.hmacKeyId(), property, invalid);
            key.ifPresent(k -> {
                keys.add(k);
                set.add(new Entry(property, label + " " + k.keyId(), k, false));
            });
        }
        return keys;
    }

    /**
     * Refuses keys that would defeat the separation of tenants or make a token's key ambiguous (TR-SEC-10): any two
     * configured keys with the same bytes, current or previous, host or tenant (compared in constant time); two keys
     * with one id within the host's keys or within one tenant's keys; and a tenant key whose id is one of the host's
     * key ids. Reported by setting name, never by value; the start-up check then refuses to start.
     */
    private static void checkDistinct(List<Entry> hostSet, Map<String, List<Entry>> tenantSets, List<String> invalid) {
        List<Entry> all = new ArrayList<>(hostSet);
        tenantSets.values().forEach(all::addAll);
        for (int i = 0; i < all.size(); i++) {
            for (int j = i + 1; j < all.size(); j++) {
                if (all.get(i).key().sameKeyAs(all.get(j).key())) {
                    // Name the tenant's setting when the other one is the host key, as an operator fixes that one.
                    boolean hostFirst = all.get(i).current() && hostSet.contains(all.get(i));
                    Entry subject = hostFirst ? all.get(j) : all.get(i);
                    Entry other = hostFirst ? all.get(i) : all.get(j);
                    invalid.add(subject.property() + " (equals " + other.label()
                            + "; every masking key must be a key of its own)");
                }
            }
        }
        uniqueIds(hostSet, "the host", invalid);
        java.util.Set<String> hostIds = new java.util.HashSet<>();
        hostSet.forEach(entry -> hostIds.add(entry.key().keyId()));
        tenantSets.forEach((tenant, set) -> {
            uniqueIds(set, "tenant " + tenant, invalid);
            for (Entry entry : set) {
                if (hostIds.contains(entry.key().keyId())) {
                    invalid.add(entry.property() + "-id (equals the host key id " + entry.key().keyId()
                            + "; give the tenant key an id of its own)");
                }
            }
        });
    }

    private static void uniqueIds(List<Entry> set, String owner, List<String> invalid) {
        Map<String, Entry> seen = new HashMap<>();
        for (Entry entry : set) {
            Entry first = seen.putIfAbsent(entry.key().keyId(), entry);
            if (first != null) {
                invalid.add(entry.property() + "-id (key id " + entry.key().keyId() + " is already used by "
                        + first.property() + "; every key of " + owner + " needs an id of its own)");
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
        return hostKey.map(host -> hostSubkey(host, tenantId));
    }

    private MaskingKey hostSubkey(MaskingKey host, String tenantId) {
        MaskingKey cached = derived.get(tenantId);
        if (cached != null) {
            return cached;
        }
        MaskingKey subkey = host.forTenant(tenantId);
        if (derived.size() < DERIVED_CACHE_MAX) {
            derived.put(tenantId, subkey);
        }
        return subkey;
    }

    /**
     * The current key first, then the earlier ones. A tenant on the host key: its subkey of each previous host key,
     * then its own previous keys as configured. A tenant with its own key: its own previous keys, then its subkey of
     * the current host key and of each previous host key, so the rows it wrote before it moved off the host key stay
     * findable. Host key ids and tenant key ids never overlap, so a host subkey only ever matches the tenant's rows
     * from its time on the host key. Never used for writing.
     */
    @Override
    public List<MaskingKey> searchKeys(String tenantId) {
        List<MaskingKey> keys = new ArrayList<>();
        hmacKey(tenantId).ifPresent(keys::add);
        boolean ownKey = tenantKeys.containsKey(tenantId);
        List<MaskingKey> ownPrevious = tenantPrevious.getOrDefault(tenantId, List.of());
        if (ownKey) {
            keys.addAll(ownPrevious);
            hostKey.ifPresent(host -> keys.add(hostSubkey(host, tenantId)));
        }
        for (MaskingKey previous : hostPrevious) {
            keys.add(previous.forTenant(tenantId));
        }
        if (!ownKey) {
            keys.addAll(ownPrevious);
        }
        return List.copyOf(keys);
    }

    /** Host keys as configured under {@value MaskingPolicy#HOST_SCOPE}, each tenant's own keys under its id. */
    @Override
    public Map<String, List<MaskingKey>> configuredKeys() {
        Map<String, List<MaskingKey>> byScope = new HashMap<>();
        List<MaskingKey> host = new ArrayList<>();
        hostKey.ifPresent(host::add);
        host.addAll(hostPrevious);
        if (!host.isEmpty()) {
            byScope.put(HOST_SCOPE, List.copyOf(host));
        }
        java.util.Set<String> tenants = new java.util.HashSet<>(tenantKeys.keySet());
        tenants.addAll(tenantPrevious.keySet());
        for (String tenant : tenants) {
            List<MaskingKey> keys = new ArrayList<>();
            tenantKeys.getOrDefault(tenant, Optional.empty()).ifPresent(keys::add);
            keys.addAll(tenantPrevious.getOrDefault(tenant, List.of()));
            if (!keys.isEmpty()) {
                byScope.put(tenant, List.copyOf(keys));
            }
        }
        return Map.copyOf(byScope);
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
        return "PropertyMaskingPolicy[hostKey=" + (hostKey.isPresent() ? "set" : "unset") + ", previousHostKeys="
                + hostPrevious.size() + ", tenants=" + tenantKeys.keySet() + "]";
    }
}
