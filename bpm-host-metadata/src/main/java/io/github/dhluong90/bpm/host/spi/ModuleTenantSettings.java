package io.github.dhluong90.bpm.host.spi;

import java.util.List;
import java.util.Map;

/**
 * Settings of one engine that depend on the caller's tenant, published in that engine's {@code settings} of
 * {@code GET /api/bpm/meta} next to the fixed settings of its {@link ModuleDescriptor}.
 *
 * <p><strong>Engine-only and unstable until 1.0.</strong> Only the engine starters implement this interface. It may
 * change in any release before 1.0, and the 1.0 API review decides whether it becomes a public surface. A bean of
 * this type whose class is not part of the engines stops the host at start-up.
 *
 * <p>The host metadata module asks for the settings of the authenticated caller's tenant only, as
 * {@link EngineAccess} resolved it, never a tenant named in the request, so one tenant never sees another's values.
 * The names are fixed at start-up: a name that is also a fixed setting of the module, or that another contributor
 * declares, stops the host. At request time only declared names are published, and a value must be a boolean, an
 * integer or a string.
 *
 * <p>Failures are contained. When {@link #settings(String)} throws, or returns a value of another type for a declared
 * name, every setting of this contributor is left out of that response and the problem is logged at {@code ERROR}
 * (once, then at {@code DEBUG}) with the class, the module and the name, never the value. {@code /meta} still answers
 * {@code 200} with the fixed settings and the other contributors' settings.
 *
 * <p>Implementations must be thread-safe, must never return a secret (a secret appears only as {@code set} or
 * {@code unset}), and must not do I/O or block: {@link #settings(String)} runs on every {@code /meta} request, and
 * the cockpit polls {@code /meta}. Compute from state held in memory, such as a key already loaded at start-up.
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
     * Returns the settings of one tenant. Runs on every {@code /meta} request: no I/O, no blocking, no secret.
     *
     * @param tenantId the caller's tenant, as {@link EngineAccess} resolved it
     * @return values by name, each a boolean, an integer or a string; a declared name that is absent is left out of
     *     the response
     */
    Map<String, Object> settings(String tenantId);
}
