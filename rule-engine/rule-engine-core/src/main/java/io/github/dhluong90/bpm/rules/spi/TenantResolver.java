package io.github.dhluong90.bpm.rules.spi;

/**
 * Resolves the tenant of the current call from the host's authenticated context. The engine never
 * takes a tenant from a request body (TR-SEC-03). Must be thread-safe.
 */
@FunctionalInterface
public interface TenantResolver {

    /**
     * Returns the tenant of the current call.
     *
     * @return the tenant id, or null when the call is not authenticated
     */
    String currentTenantId();
}
