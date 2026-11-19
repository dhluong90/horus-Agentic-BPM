package io.github.dhluong90.bpm.host.spi;

/**
 * Who is calling an engine endpoint, in which tenant, and whether they may do it: the one place every
 * engine controller and {@code GET /api/bpm/me} ask (ADR-0014 decision 6, ADR-0010).
 *
 * <p>{@code /me} lists exactly the permissions for which {@link #hasPermission} answers {@code true}, so
 * what a client is shown is what the engine endpoints allow. Engine starters must not resolve the tenant,
 * read the security context or apply a role mapping themselves; they call this type.
 *
 * <p>The host metadata module registers a default unless the host declares its own bean of this type. The
 * default takes the caller from the host's Spring Security context, finds the tenant with the
 * {@link TenantResolver}, then applies the engines' tenant checks whatever the resolver: when the
 * principal carries the {@code horus.bpm.tenancy.claim} attribute the resolved tenant must equal it, and
 * while tenant switching is off an {@code X-Tenant-Id} header must equal it too. A refusal writes a
 * {@code tenant.switch.refused} or {@code tenant.unresolved} entry on the {@code io.github.dhluong90.bpm.security}
 * logger. A permission is held when the caller has the permission string as an authority, or a host
 * authority that the engine's role mapping grants it for.
 * <pre>{@code
 * @PostMapping("/api/bpm/rules/v1/rule-sets/{name}/versions/{version}:approve")
 * ResponseEntity<?> approve(@PathVariable String name, @PathVariable int version) {
 *     EngineCaller caller = access.requirePermission("rules:approve");
 *     return ok(versions.approve(caller.tenant(), name, version, caller.userId()));
 * }
 * }</pre>
 *
 * <p>Implementations must be thread-safe and must not perform network I/O.
 *
 * @since 0.1.0
 */
public interface EngineAccess {

    /**
     * The caller of the current request, after the tenant checks.
     *
     * @return the caller, never {@code null}
     * @throws EngineAccessDeniedException {@code UNAUTHENTICATED} without an authenticated principal or
     *     without a tenant; {@code TENANT_NOT_ALLOWED} for a tenant the caller is not signed in for
     */
    EngineCaller currentCaller();

    /**
     * Whether the caller holds a permission.
     *
     * @param caller a caller returned by {@link #currentCaller()}
     * @param permission an engine permission, {@code <module>:<action>} or
     *     {@code <module>:<resource>:<action>}, for example {@code rules:approve}
     * @return {@code true} when the caller holds it
     * @throws IllegalArgumentException when no installed engine declares the permission, which is a
     *     programming error in the calling engine, never a caller's fault
     */
    boolean hasPermission(EngineCaller caller, String permission);

    /**
     * The caller of the current request, refused unless it holds the permission.
     *
     * @param permission an engine permission, for example {@code rules:approve}
     * @return the caller, never {@code null}
     * @throws EngineAccessDeniedException as {@link #currentCaller()}, or {@code PERMISSION_DENIED} when the
     *     caller lacks the permission
     * @throws IllegalArgumentException when no installed engine declares the permission
     */
    default EngineCaller requirePermission(String permission) {
        EngineCaller caller = currentCaller();
        if (!hasPermission(caller, permission)) {
            throw new EngineAccessDeniedException(EngineAccessDeniedException.Reason.PERMISSION_DENIED, null, null);
        }
        return caller;
    }
}
