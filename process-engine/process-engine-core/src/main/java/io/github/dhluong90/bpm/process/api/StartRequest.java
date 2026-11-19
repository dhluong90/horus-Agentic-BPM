package io.github.dhluong90.bpm.process.api;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Starts an instance on the effective version of a definition key in the caller's tenant.
 *
 * @param definitionKey the definition key
 * @param businessKey optional; unique per tenant and key, a repeat returns the existing instance (PRE-002)
 * @param variables initial instance variables: strings, booleans, numbers ({@code Long}, {@code BigDecimal}),
 *     {@code Money}, lists and maps of these
 * @param idempotencyKey optional; a replay with the same key and request returns the original result
 */
public record StartRequest(String definitionKey, String businessKey, Map<String, Object> variables,
        String idempotencyKey) {

    public StartRequest {
        Objects.requireNonNull(definitionKey, "definitionKey must not be null");
        variables = Collections.unmodifiableMap(new LinkedHashMap<>(variables == null ? Map.of() : variables));
    }

    /**
     * @param definitionKey the definition key
     * @return a request with no business key and no variables
     */
    public static StartRequest of(String definitionKey) {
        return new StartRequest(definitionKey, null, Map.of(), null);
    }

    /**
     * @param key the business key
     * @return a copy with it
     */
    public StartRequest withBusinessKey(String key) {
        return new StartRequest(definitionKey, key, variables, idempotencyKey);
    }

    /**
     * @param values the initial variables
     * @return a copy with them
     */
    public StartRequest withVariables(Map<String, ?> values) {
        return new StartRequest(definitionKey, businessKey, new LinkedHashMap<>(values), idempotencyKey);
    }

    /**
     * @param key the idempotency key
     * @return a copy with it
     */
    public StartRequest withIdempotencyKey(String key) {
        return new StartRequest(definitionKey, businessKey, variables, key);
    }
}
