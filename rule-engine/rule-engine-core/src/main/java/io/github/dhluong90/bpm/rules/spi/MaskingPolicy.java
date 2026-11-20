package io.github.dhluong90.bpm.rules.spi;

import io.github.dhluong90.bpm.rules.api.MaskingKey;
import io.github.dhluong90.bpm.rules.api.PersonalDataNames;
import java.util.Optional;

/**
 * How the decision log masks inputs, per tenant (TR-SEC-05, RLE-012). A rule set chooses which inputs are personal
 * data with {@code mask: true}; this policy supplies the key their fingerprints are made with and whether inputs
 * that declare nothing are masked too. The Spring Boot starter provides a default backed by
 * {@code horus.bpm.rules.masking.*}; a host replaces it with its own bean, for example to read one key per tenant
 * from a secret store. The key returned for a tenant is used as it is; a host that shares one root key between
 * tenants derives each tenant's key with {@link MaskingKey#forTenant(String)}, as the default does. Implementations
 * must be thread-safe and must never log a key.
 *
 * <p>It also carries the tenant's guards against unmasked personal data (PD-099): strict mode and the list of input
 * names that look like personal data.
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
     * value with each of them, so rows written before a key rotation are still found. Only {@link #hmacKey(String)}
     * is ever used for writing; an earlier key is used for search only, and a key no longer returned here is no
     * longer searched.
     *
     * @param tenantId the tenant
     * @return the keys, possibly empty; the default is the current key only
     */
    default java.util.List<MaskingKey> searchKeys(String tenantId) {
        return hmacKey(tenantId).map(java.util.List::of).orElse(java.util.List.of());
    }

    /** Scope of the host keys in {@link #configuredKeys()}: keys shared by every tenant without a key of its own. */
    String HOST_SCOPE = "*";

    /**
     * Returns every key this policy is configured with, current and earlier, by scope: {@value #HOST_SCOPE} for the
     * host keys as configured (before any tenant derivation) and a tenant id for a tenant's own keys. At start-up the
     * engine records each key's check value ({@link MaskingKey#checkValue()}) by scope and key id, and refuses to
     * start when a known key id comes back with a different check value, or when a key id moves between the host and
     * a tenant, so one id never names two keys.
     *
     * <p>Returning keys here is how a policy of the host's own takes part in that check, and it must: at start-up,
     * every key that {@link #hmacKey(String)} or {@link #searchKeys(String)} returns for a tenant with live rule sets
     * must be declared here, either under the tenant id as it is or as a host key under {@value #HOST_SCOPE} whose
     * {@link MaskingKey#forTenant(String) tenant subkey} it is; otherwise the engine does not start. The map must
     * include the previous keys too: a key left out is never checked, so a reused id would go unnoticed. A tenant must
     * not be named {@value #HOST_SCOPE}. Called once at start-up, outside any request.
     *
     * @return the keys by scope; the default is empty, which only a policy without keys may keep
     */
    default java.util.Map<String, java.util.List<MaskingKey>> configuredKeys() {
        return java.util.Map.of();
    }

    /**
     * Returns how many masked-input searches one caller of a tenant may run per minute on this replica. The search
     * is a test of whether a person was decided on, so it is always limited: a value below 1 counts as 1.
     *
     * @param tenantId the tenant
     * @return the limit; the default is {@value #DEFAULT_SEARCHES_PER_MINUTE}
     */
    default int searchesPerMinute(String tenantId) {
        return DEFAULT_SEARCHES_PER_MINUTE;
    }

    /** Default of {@code masking.search.max-per-minute}. */
    int DEFAULT_SEARCHES_PER_MINUTE = 30;

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
     * Tells whether every input of a tenant's rule sets must declare {@code mask: true} or {@code mask: false}
     * ({@code decision-log.require-explicit-mask}, PD-099 mitigation 2, RLE-050). When true, an import, a draft
     * replacement or a publication request with an undeclared input is refused with {@code VALIDATION_FAILED} naming
     * the input.
     *
     * @param tenantId the tenant
     * @return true for strict mode; the default is false
     */
    default boolean requireExplicitMask(String tenantId) {
        return false;
    }

    /**
     * Returns the input names that look like personal data for a tenant (PD-099 mitigation 1, RLE-049). An undeclared
     * input whose name matches one gets a warning at import and at the publication request.
     *
     * @param tenantId the tenant
     * @return the names; the default is {@link PersonalDataNames#builtIn()}
     */
    default PersonalDataNames personalDataNames(String tenantId) {
        return PersonalDataNames.builtIn();
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
     * Returns a policy with one host key shared by every tenant. The key is never used directly: each tenant gets its
     * own subkey, {@link MaskingKey#forTenant(String)}, so two tenants never get the same token for one value. The
     * host key is declared under {@value #HOST_SCOPE} in {@link #configuredKeys()}.
     *
     * @param hostKey the host key
     * @return the policy
     */
    static MaskingPolicy withKey(MaskingKey hostKey) {
        java.util.Objects.requireNonNull(hostKey, "hostKey must not be null");
        return new MaskingPolicy() {
            @Override
            public Optional<MaskingKey> hmacKey(String tenantId) {
                return Optional.of(hostKey.forTenant(tenantId));
            }

            @Override
            public java.util.Map<String, java.util.List<MaskingKey>> configuredKeys() {
                return java.util.Map.of(HOST_SCOPE, java.util.List.of(hostKey));
            }
        };
    }
}
