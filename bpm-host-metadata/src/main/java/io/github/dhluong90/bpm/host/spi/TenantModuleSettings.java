package io.github.dhluong90.bpm.host.spi;

import java.util.Map;

/**
 * Settings of one engine whose value depends on the caller's tenant, added to that engine's {@code settings} in
 * {@code GET /api/bpm/meta} for the caller's tenant only. An engine starter registers at most one bean of this type
 * per module, beside its {@link ModuleDescriptor}; a host never implements it. A tenant setting with the same name as
 * a host setting of the descriptor replaces it.
 *
 * <p>Implementations are called on every {@code /meta} request, so they must be cheap and thread-safe, with no
 * database query per request: read settings held in memory. They must return only values clients may see: never
 * a key, a secret or another tenant's data. Because these values differ per tenant, {@code /meta} varies like
 * {@code /me} and carries the tenant in its ETag.
 */
public interface TenantModuleSettings {

    /**
     * Returns the module the settings belong to.
     *
     * @return {@code rules} or {@code processes}, as in the module's {@link ModuleDescriptor}
     */
    String module();

    /**
     * Returns the settings in force for a tenant.
     *
     * @param tenantId the caller's tenant, never blank
     * @return setting name to a boolean, an integer or a string; empty when none
     */
    Map<String, Object> settings(String tenantId);
}
