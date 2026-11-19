package com.horus.bpm.rules.internal.store;

import java.time.Instant;

/** Idempotency records per tenant, operation and key (ADR-0006). Joins the current transaction. */
public interface IdempotencyStore {

    /**
     * Claims a key for the current transaction.
     *
     * @param tenantId the tenant
     * @param operation the operation name
     * @param key the idempotency key
     * @param requestHash hash of the request
     * @param now the current time; records that expired before it are ignored
     * @return what to do
     */
    Claim claim(String tenantId, String operation, String key, String requestHash, Instant now);

    /**
     * Records the result of a fresh request in the current transaction.
     *
     * @param tenantId the tenant
     * @param operation the operation name
     * @param key the idempotency key
     * @param requestHash hash of the request
     * @param statusCode the REST status of the result
     * @param responseJson the result as JSON
     * @param expiresAt when the record may be removed
     */
    void record(String tenantId, String operation, String key, String requestHash, int statusCode,
            String responseJson, Instant expiresAt);

    /** Result of a claim. */
    sealed interface Claim {
    }

    /** No record: run the request and record its result. */
    record Fresh() implements Claim {
    }

    /**
     * A record with the same request hash: return the stored result.
     *
     * @param statusCode the stored status
     * @param responseJson the stored result
     */
    record Replay(int statusCode, String responseJson) implements Claim {
    }

    /** A record with a different request hash. */
    record Mismatch() implements Claim {
    }

    /** Another transaction holds the key. */
    record InFlight() implements Claim {
    }
}
