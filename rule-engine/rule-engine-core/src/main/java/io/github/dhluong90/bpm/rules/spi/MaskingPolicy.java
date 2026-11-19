package io.github.dhluong90.bpm.rules.spi;

import io.github.dhluong90.bpm.rules.api.MaskingKey;
import java.util.Optional;

/**
 * How the decision log masks inputs, per tenant (TR-SEC-05, RLE-012). A rule set chooses which inputs are personal
 * data with {@code mask: true}; this policy supplies the key their fingerprints are made with and whether inputs
 * that declare nothing are masked too. The Spring Boot starter provides a default backed by
 * {@code horus.bpm.rules.masking.*}; a host replaces it with its own bean, for example to read one key per tenant
 * from a secret store. Implementations must be thread-safe and must never log a key.
 */
public interface MaskingPolicy {

    /**
     * Returns the key of a tenant.
     *
     * @param tenantId the tenant
     * @return the key, or empty when the tenant has none; a decision that would mask an input then fails closed
     */
    Optional<MaskingKey> hmacKey(String tenantId);

    /**
     * Returns every key a tenant still holds, the current one first. A masked-input search fingerprints the plain
     * value with each of them, so rows written before a key rotation are still found.
     *
     * @param tenantId the tenant
     * @return the keys, possibly empty; the default is the current key only
     */
    default java.util.List<MaskingKey> searchKeys(String tenantId) {
        return hmacKey(tenantId).map(java.util.List::of).orElse(java.util.List.of());
    }

    /**
     * Tells whether an input that does not declare {@code mask} is masked for a tenant. An input with
     * {@code mask: false} is never masked by this default; one with {@code mask: true} always is.
     *
     * @param tenantId the tenant
     * @return true to mask undeclared inputs; the default is false
     */
    default boolean maskByDefault(String tenantId) {
        return false;
    }

    /**
     * Returns the policy with no key and no default masking: inputs with {@code mask: true} fail closed.
     *
     * @return the policy
     */
    static MaskingPolicy none() {
        return tenantId -> Optional.empty();
    }

    /**
     * Returns a policy with one key for every tenant.
     *
     * @param key the key
     * @return the policy
     */
    static MaskingPolicy withKey(MaskingKey key) {
        Optional<MaskingKey> held = Optional.of(key);
        return tenantId -> held;
    }
}
