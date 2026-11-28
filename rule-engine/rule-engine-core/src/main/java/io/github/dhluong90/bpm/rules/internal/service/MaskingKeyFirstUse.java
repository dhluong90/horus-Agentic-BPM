package io.github.dhluong90.bpm.rules.internal.service;

import io.github.dhluong90.bpm.rules.api.MaskingKey;
import io.github.dhluong90.bpm.rules.internal.store.RuleStore;
import io.github.dhluong90.bpm.rules.internal.store.TransactionRunner;
import io.github.dhluong90.bpm.rules.spi.MaskingPolicy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The check of a masking key at its first use to write a decision (TR-SEC-10, #340). Start-up checks the keys a policy
 * declares and the keys of tenants with live rule sets; a host that declares nothing and has no rule sets at start-up
 * is only warned. So the first time {@link MaskingPolicy#hmacKey(String)} gives a tenant a key in this process, its
 * check value is recorded or compared by scope and key id, as at start-up, and a key id that was first seen with other
 * bytes is refused. The result is remembered by tenant, key id and check value, and by the key object itself, so the
 * next decisions cost no database round trip. The one insert-or-read runs in a transaction of its own and holds no lock
 * beyond itself.
 *
 * <p>The scope is the one the policy declares the key under: the tenant for a key declared as the tenant's own, the
 * host ({@value MaskingPolicy#HOST_SCOPE}) for a host key whose tenant subkey it is. A key the policy does not declare is
 * recorded under the tenant it is used for; when its id is already recorded for the host, it must be that host key as
 * returned, and is compared with the host's check value. The caller learns only that the key does not match; the check values are never put in a message.
 */
final class MaskingKeyFirstUse {

    private final MaskingPolicy policy;
    private final RuleStore store;
    private final TransactionRunner transactions;
    private final Clock clock;
    private final Map<String, MaskingKey> lastByTenant = new ConcurrentHashMap<>();
    private final Set<String> accepted = ConcurrentHashMap.newKeySet();
    private volatile Map<String, List<MaskingKey>> declared;

    MaskingKeyFirstUse(MaskingPolicy policy, RuleStore store, TransactionRunner transactions, Clock clock) {
        this.policy = policy;
        this.store = store;
        this.transactions = transactions;
        this.clock = clock;
    }

    /**
     * Checks a key that is about to write a decision of a tenant.
     *
     * @param tenant the tenant
     * @param key the key {@link MaskingPolicy#hmacKey(String)} returned
     * @return null when the key is the one first seen under its id, otherwise the id of the key that does not match;
     *     the check values stay inside this class, because they are confirmation values derived from the key and must
     *     reach neither a log nor a caller
     */
    String mismatchedKeyId(String tenant, MaskingKey key) {
        if (lastByTenant.get(tenant) == key) {
            return null;
        }
        String check = key.checkValue();
        String seen = tenant + '\u0000' + key.keyId() + '\u0000' + check;
        if (accepted.contains(seen)) {
            lastByTenant.put(tenant, key);
            return null;
        }
        Scoped scoped = scopeOf(tenant, key);
        String mismatch = transactions.inIndependentTransaction(() -> recordOrCompare(tenant, key, scoped));
        if (mismatch != null) {
            return key.keyId();
        }
        accepted.add(seen);
        lastByTenant.put(tenant, key);
        return null;
    }

    /** The scope a key is recorded under and its check value; {@code declared} is false for a key the policy keeps secret. */
    private record Scoped(String scope, String check, boolean declared) {
    }

    /** Returns the check value first recorded when it differs from the key's, else null. */
    private String recordOrCompare(String tenant, MaskingKey key, Scoped scoped) {
        String keyId = key.keyId();
        if (!scoped.declared() && store.maskingKeyScopes(keyId).contains(MaskingPolicy.HOST_SCOPE)) {
            // An undeclared key under a host key id: it must be that host key as it is, which records nothing more.
            String host = store.maskingKeyCheck(MaskingPolicy.HOST_SCOPE, keyId, scoped.check(), clock.instant());
            return same(host, scoped.check()) ? null : host;
        }
        String recorded = store.maskingKeyCheck(scoped.scope(), keyId, scoped.check(), clock.instant());
        return same(recorded, scoped.check()) ? null : recorded;
    }

    private static boolean same(String recorded, String check) {
        return MessageDigest.isEqual(recorded.getBytes(StandardCharsets.US_ASCII),
                check.getBytes(StandardCharsets.US_ASCII));
    }

    private Scoped scopeOf(String tenant, MaskingKey key) {
        Map<String, List<MaskingKey>> byScope = declared();
        for (MaskingKey own : byScope.getOrDefault(tenant, List.of())) {
            if (own.keyId().equals(key.keyId()) && own.sameKeyAs(key)) {
                return new Scoped(tenant, own.checkValue(), true);
            }
        }
        for (MaskingKey host : byScope.getOrDefault(MaskingPolicy.HOST_SCOPE, List.of())) {
            if (host.keyId().equals(key.keyId()) && host.forTenant(tenant).sameKeyAs(key)) {
                return new Scoped(MaskingPolicy.HOST_SCOPE, host.checkValue(), true);
            }
        }
        return new Scoped(tenant, key.checkValue(), false);
    }

    private Map<String, List<MaskingKey>> declared() {
        Map<String, List<MaskingKey>> current = declared;
        if (current == null) {
            Map<String, List<MaskingKey>> copy = new TreeMap<>();
            policy.configuredKeys().forEach((scope, keys) -> copy.put(scope, new ArrayList<>(keys)));
            declared = current = copy;
        }
        return current;
    }
}
