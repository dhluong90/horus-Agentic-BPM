package io.github.dhluong90.bpm.host.spi;

import java.util.List;
import java.util.Map;

/**
 * Settings of one engine that depend on the caller's tenant, published in that engine's {@code settings} of
 * {@code GET /api/bpm/meta} next to the fixed settings of its {@link ModuleDescriptor}.
 *
 * <p>An engine starter registers a bean of this type; a host never implements it. The host metadata module asks
 * for the settings of the authenticated caller's tenant only, as {@link EngineAccess} resolved it, never a tenant
 * named in the request, so one tenant never sees another's values. The names are fixed at start-up: a name that
 * is also a fixed setting of the module, or that another contributor declares, stops the host. At request time
 * only declared names are published, and a value must be a boolean, an integer or a string. Implementations must be
 * thread-safe and must never return a secret: a secret appears only as {@code set} or {@code unset}.
 *
 * @since 0.2.0
 */
public interface ModuleTenantSettings {

    /**
     * Returns the module these settings belong to.
     *
     * @return {@code rules} or {@code processes}; the module must have a {@link ModuleDescriptor}
     */
    String module();

    /**
     * Returns every setting name {@link #settings(String)} may return, in display order.
     *
     * @return the names, not empty, fixed for the life of the host
     */
    List<String> names();

    /**
     * Returns the settings of one tenant.
     *
     * @param tenantId the caller's tenant, as {@link EngineAccess} resolved it
     * @return values by name; a declared name that is absent is left out of the response
     */
    Map<String, Object> settings(String tenantId);
}
