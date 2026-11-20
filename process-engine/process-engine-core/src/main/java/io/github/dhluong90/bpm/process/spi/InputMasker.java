package io.github.dhluong90.bpm.process.spi;

/**
 * Turns the value of a masked agent step input ({@code <horus:input ... mask="true"/>}, #59) into what history and the
 * review task keep instead of it (TR-SEC-05). The agent still receives the plain value; only the audit copy is masked.
 * The starter's default uses the Rule Engine's masking key of the tenant when one is configured, so the token equals
 * the one the decision log keeps for the same value ({@code hmac:<keyId>:<hex>}); without a key it is
 * {@value #REDACTED}. A host may register its own; the starter's default is {@code @ConditionalOnMissingBean}.
 *
 * <p>Runs inside the step transaction: an implementation must be pure, in-process and fast, never call the network and
 * never log the value. It must never return the plain value.
 */
@FunctionalInterface
public interface InputMasker {

    /** What a masked value becomes when no keyed fingerprint can be made. */
    String REDACTED = "***";

    /** Keeps nothing of the value. */
    InputMasker REDACT = (tenantId, plainText) -> REDACTED;

    /**
     * @param tenantId the instance's tenant
     * @param plainText the value as text: a string as it is, any other value as its JSON form
     * @return the stored replacement, for example {@code hmac:k1:<64 hex digits>}, or {@value #REDACTED}
     */
    String mask(String tenantId, String plainText);
}
