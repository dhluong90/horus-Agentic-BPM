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
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import tools.jackson.databind.JsonNode;

/**
 * The start-up check of RLE-035: every tenant whose rule sets mask an input, by declaration or by the tenant's
 * default, must have a masking key. Then, when built with a transaction runner, the check of TR-SEC-10 on key ids:
 * every key in use for a live tenant is declared by the policy, each configured key's check value is recorded by
 * scope and key id ({@code rle_masking_key_check}), and a known key id that comes back with a different check value,
 * or a key id that moves between the host and a tenant, stops start-up, so one key id never names two keys across
 * replicas or over time. Runs once after the migrations, outside any request. Its messages name the setting, the tenant, the
 * rule set, the key id and check values, never a key or an input value.
 */
public final class MaskingStartupCheck {

    private static final System.Logger LOG = System.getLogger(MaskingStartupCheck.class.getName());

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
        Set<String> liveTenants = new java.util.TreeSet<>();
        for (VersionInputs version : store.liveVersionInputs()) {
            liveTenants.add(version.tenantId());
            String masked = firstMaskedInput(version.inputsJson(), policy.maskByDefault(version.tenantId()));
            if (masked != null && policy.hmacKey(version.tenantId()).isEmpty()) {
                problems.add("rule set " + version.ruleSet() + " of tenant " + version.tenantId() + " masks input "
                        + masked);
            }
        }
        List<String> invalid = new ArrayList<>(invalidSettings);
        if (problems.isEmpty() && invalid.isEmpty()) {
            verifyKeyIds(liveTenants);
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
     * Checks the keys of TR-SEC-10, each problem named by scope and key id, never by the key:
     * <ol>
     *   <li>every key the policy returns for a tenant with live rule sets, current or for search, is declared by
     *       {@link MaskingPolicy#configuredKeys()}: under the tenant as it is, or as a host key under
     *       {@value MaskingPolicy#HOST_SCOPE} whose tenant subkey it is. A policy that keeps a key out of that map
     *       would leave its id unchecked, so start-up stops;</li>
     *   <li>each configured key's check value is recorded by scope and key id, and a known id that comes back with a
     *       different check value stops start-up;</li>
     *   <li>a key id recorded for the host is never a tenant's key id, and the reverse, even after the key is retired,
     *       so one {@code hmac:<keyId>:} prefix never names two keys of one tenant over time.</li>
     * </ol>
     * Steps 2 and 3 run in one transaction that a problem rolls back, so a refused start records nothing. Scopes are
     * visited in order and keys by id, so replicas that start together take the row locks in the same order.
     */
    private void verifyKeyIds(Set<String> liveTenants) {
        if (transactions == null) {
            return;
        }
        Map<String, List<MaskingKey>> byScope = new TreeMap<>();
        policy.configuredKeys().forEach((scope, keys) -> {
            List<MaskingKey> sorted = new ArrayList<>(keys);
            sorted.sort(java.util.Comparator.comparing(MaskingKey::keyId));
            byScope.put(scope, sorted);
        });
        requireDeclared(byScope, liveTenants);
        if (byScope.isEmpty()) {
            return;
        }
        transactions.inTransaction(() -> {
            List<String> problems = new ArrayList<>();
            byScope.forEach((scope, keys) -> {
                for (MaskingKey key : keys) {
                    String configured = key.checkValue();
                    String recorded = store.maskingKeyCheck(scope, key.keyId(), configured, clock.instant());
                    if (!MessageDigest.isEqual(recorded.getBytes(StandardCharsets.US_ASCII),
                            configured.getBytes(StandardCharsets.US_ASCII))) {
                        problems.add(describe(scope, key.keyId()) + " was first recorded with check value " + recorded
                                + ", but the key configured now has check value " + configured);
                    }
                }
            });
            byScope.forEach((scope, keys) -> {
                for (MaskingKey key : keys) {
                    Set<String> scopes = new java.util.TreeSet<>(store.maskingKeyScopes(key.keyId()));
                    boolean host = MaskingPolicy.HOST_SCOPE.equals(scope);
                    if (host) {
                        scopes.remove(MaskingPolicy.HOST_SCOPE);
                        if (!scopes.isEmpty()) {
                            problems.add(describe(scope, key.keyId()) + " is already recorded as a key id of tenant "
                                    + String.join(", ", scopes));
                        }
                    } else if (scopes.contains(MaskingPolicy.HOST_SCOPE)) {
                        problems.add(describe(scope, key.keyId()) + " is already recorded as a host key id");
                    }
                }
            });
            if (!problems.isEmpty()) {
                throw new IllegalStateException("The Rule Engine cannot start: " + String.join("; ", problems)
                        + ". A key id names one key for good, so tokens made under it stay findable: restore the"
                        + " key that id was first used with, or give the new key an id of its own.");
            }
            return null;
        });
    }

    /** Step 1 of {@link #verifyKeyIds(Set)}: every key in use for a live tenant is declared. */
    private void requireDeclared(Map<String, List<MaskingKey>> byScope, Set<String> liveTenants) {
        List<String> undeclared = new ArrayList<>();
        for (String tenant : liveTenants) {
            if (MaskingPolicy.HOST_SCOPE.equals(tenant)) {
                undeclared.add("tenant id " + MaskingPolicy.HOST_SCOPE + " is reserved for the host keys");
                continue;
            }
            for (MaskingKey key : policy.searchKeys(tenant)) {
                if (!declared(byScope, tenant, key)) {
                    undeclared.add("key id " + key.keyId() + " of tenant " + tenant);
                }
            }
        }
        if (!undeclared.isEmpty()) {
            throw new IllegalStateException("The Rule Engine cannot start: the masking policy "
                    + policy.getClass().getName() + " uses keys it does not declare in configuredKeys(): "
                    + String.join(", ", undeclared) + ". Return every key, current and previous, from"
                    + " configuredKeys(): a tenant's own keys under the tenant id, host keys under "
                    + MaskingPolicy.HOST_SCOPE + ", so a key id that comes back with other key bytes is caught.");
        }
    }

    private static boolean declared(Map<String, List<MaskingKey>> byScope, String tenant, MaskingKey key) {
        for (MaskingKey own : byScope.getOrDefault(tenant, List.of())) {
            if (own.keyId().equals(key.keyId()) && own.sameKeyAs(key)) {
                return true;
            }
        }
        for (MaskingKey host : byScope.getOrDefault(MaskingPolicy.HOST_SCOPE, List.of())) {
            if (host.keyId().equals(key.keyId()) && host.forTenant(tenant).sameKeyAs(key)) {
                return true;
            }
        }
        return false;
    }

    private static String describe(String scope, String keyId) {
        return MaskingPolicy.HOST_SCOPE.equals(scope)
                ? "host masking key id " + keyId + " (" + EngineDefaults.MASKING_KEY_PROPERTY + "-id or previous-keys)"
                : "masking key id " + keyId + " of tenant " + scope + " (horus.bpm.rules.masking.tenants." + scope
                        + ")";
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
        store.recordSettingSeen(tenant, MASK_DEFAULT_SETTING, now, at);
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
