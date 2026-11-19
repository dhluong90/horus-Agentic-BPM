package io.github.dhluong90.bpm.process.spi;

/**
 * Resolves the tenant of the current call from the authenticated principal, never from a request body
 * (TR-SEC-03). Must be thread-safe.
 */
@FunctionalInterface
public interface TenantResolver {

    /**
     * @return the tenant id, or null when the call is not authenticated
     */
    String currentTenantId();
}
