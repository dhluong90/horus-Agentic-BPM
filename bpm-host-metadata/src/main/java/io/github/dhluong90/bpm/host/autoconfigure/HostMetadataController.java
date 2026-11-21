package io.github.dhluong90.bpm.host.autoconfigure;

import io.github.dhluong90.bpm.host.internal.CorrelationIds;
import io.github.dhluong90.bpm.host.spi.EngineAccess;
import io.github.dhluong90.bpm.host.spi.EngineAccessDeniedException;
import io.github.dhluong90.bpm.host.spi.EngineCaller;
import io.github.dhluong90.bpm.host.spi.ModuleDescriptor;
import io.github.dhluong90.bpm.host.spi.ModuleTenantSettings;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/bpm/meta} and {@code GET /api/bpm/me}, schema version 1 (ADR-0014, X-F-22).
 *
 * <p>Both need an authenticated caller and no other permission. Responses follow
 * {@code cockpit/api/schema/bpm-meta.v1.schema.json} and {@code bpm-me.v1.schema.json} field for field,
 * carry a weak {@code ETag} and answer {@code 304} to a matching {@code If-None-Match}. {@code /meta} is
 * {@code private, max-age=60}; when an engine contributes {@link ModuleTenantSettings}, its settings follow the
 * caller's tenant, so {@code /meta} is then {@code private, no-cache} like {@code /me} and varies by the caller's
 * credentials. A contributor that throws or returns a value of another type is left out of the response and
 * logged, so it never breaks {@code /meta} for anyone. {@code /me}, which carries the
 * caller's permissions, is {@code private, no-cache}, so a revoked role or a sign-out on a shared machine
 * is never served from the browser cache without asking the host. Bodies are built as ordered maps and
 * written by the host's own JSON converter, so absent optional fields are omitted rather than written as
 * {@code null}.
 *
 * <p>The caller, the tenant and every listed permission come from {@link EngineAccess}, the same seam the
 * engine endpoints enforce with, so {@code /me} cannot list a permission the endpoints refuse or leave out
 * one they allow.
 */
@RestController
@RequestMapping("/api/bpm")
final class HostMetadataController {

    static final String META_CACHE_CONTROL = "private, max-age=60";
    /** {@code /meta} with settings of the caller's tenant: as {@code /me}, so a sign-out is never served from cache. */
    static final String META_TENANT_CACHE_CONTROL = "private, no-cache";
    static final String ME_CACHE_CONTROL = "private, no-cache";
    /** Package of the engines; only their starters may contribute {@link ModuleTenantSettings} before 1.0. */
    static final String ENGINE_PACKAGE = "io.github.dhluong90.bpm.";
    private static final Log LOG = LogFactory.getLog(HostMetadataController.class);
    /** Contributors already reported at ERROR; later failures of the same kind go to DEBUG, so polling never floods. */
    private final Set<String> reported = ConcurrentHashMap.newKeySet();

    private final List<ModuleDescriptor> modules;
    private final List<ModuleTenantSettings> tenantSettings;
    private final HostMetadataProperties properties;
    private final EngineAccess access;
    /** {@code /me} depends on the caller's credentials and on the switch request header. */
    static final String[] ME_VARY = {HttpHeaders.AUTHORIZATION, HttpHeaders.COOKIE, DefaultEngineAccess.TENANT_SWITCH_HEADER};
    /**
     * {@code /meta} with settings of the caller's tenant: the tenant comes from the credentials, and from the switch
     * header once tenant switching is offered, so the response varies by the same headers as {@code /me}.
     */
    static final String[] META_VARY = {HttpHeaders.AUTHORIZATION, HttpHeaders.COOKIE,
        DefaultEngineAccess.TENANT_SWITCH_HEADER};

    HostMetadataController(List<ModuleDescriptor> modules, HostMetadataProperties properties, EngineAccess access) {
        this(modules, List.of(), properties, access);
    }

    HostMetadataController(List<ModuleDescriptor> modules, List<ModuleTenantSettings> tenantSettings,
            HostMetadataProperties properties, EngineAccess access) {
        this.modules = List.copyOf(modules);
        this.tenantSettings = List.copyOf(tenantSettings);
        this.properties = properties;
        this.access = access;
        checkTenantSettings(this.modules, this.tenantSettings);
    }

    /**
     * Stops the host when a tenant settings bean does not come from an engine starter, names a module without an
     * entry, repeats a name, or reuses the name of a fixed setting, so a response never holds two values for one
     * name and a host never builds on the unstable SPI.
     */
    static void checkTenantSettings(List<ModuleDescriptor> modules, List<ModuleTenantSettings> tenantSettings) {
        Map<String, Set<String>> taken = new LinkedHashMap<>();
        modules.forEach(module -> taken.put(module.module(), new HashSet<>(module.settings().keySet())));
        for (ModuleTenantSettings contributor : tenantSettings) {
            if (!contributor.getClass().getName().startsWith(ENGINE_PACKAGE)) {
                throw new IllegalStateException("ModuleTenantSettings bean " + contributor.getClass().getName()
                        + " is refused: this interface is for the engine starters only and may change before 1.0."
                        + " Remove the bean; a host publishes its own information outside /api/bpm/meta.");
            }
            Set<String> names = taken.get(contributor.module());
            if (names == null) {
                throw new IllegalStateException("Tenant settings for module '" + contributor.module()
                        + "', which has no engine entry. Installed modules: " + taken.keySet());
            }
            if (contributor.names().isEmpty()) {
                throw new IllegalStateException("Tenant settings for module '" + contributor.module()
                        + "' declare no setting name.");
            }
            for (String name : contributor.names()) {
                if (name == null || name.isBlank() || !names.add(name)) {
                    throw new IllegalStateException("Tenant setting '" + name + "' of module '" + contributor.module()
                            + "' is blank or already a setting of that module.");
                }
            }
        }
    }

    @GetMapping("/meta")
    ResponseEntity<Map<String, Object>> meta(HttpServletRequest request) {
        EngineCaller caller = access.currentCaller();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("schemaVersion", 1);
        if (properties.hostId() != null) {
            body.put("hostId", properties.hostId());
        }
        List<Map<String, Object>> entries = new ArrayList<>();
        for (ModuleDescriptor module : modules) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("module", module.module());
            entry.put("engineVersion", module.engineVersion());
            entry.put("apiVersion", module.apiVersion());
            entry.put("features", module.features());
            Map<String, Object> settings = settings(module, caller.tenant());
            if (!settings.isEmpty()) {
                entry.put("settings", settings);
            }
            entries.add(entry);
        }
        body.put("modules", entries);
        body.put("tenancy", Map.of("switching", properties.tenancy().switching().enabled()));
        body.put("limits", Map.of("maxPageSize", properties.limits().maxPageSize()));
        return tenantSettings.isEmpty() ? cacheable(request, body, META_CACHE_CONTROL, new String[0])
                : cacheable(request, body, META_TENANT_CACHE_CONTROL, META_VARY);
    }

    /**
     * The module's fixed settings, then the declared tenant settings of the caller's tenant. A contributor that
     * throws, or returns a value that is not a boolean, an integer or a string for a declared name, is left out
     * whole and logged with its class, the module and the name, never the value; the other contributors and the
     * fixed settings are still published.
     */
    private Map<String, Object> settings(ModuleDescriptor module, String tenant) {
        Map<String, Object> settings = new LinkedHashMap<>(module.settings());
        for (ModuleTenantSettings contributor : tenantSettings) {
            if (!contributor.module().equals(module.module())) {
                continue;
            }
            Map<String, Object> values;
            try {
                values = contributor.settings(tenant);
            } catch (RuntimeException e) {
                report(contributor, "threw " + e.getClass().getName(), e);
                continue;
            }
            Map<String, Object> accepted = new LinkedHashMap<>();
            String unsupported = null;
            for (String name : contributor.names()) {
                Object value = values == null ? null : values.get(name);
                if (value == null) {
                    continue;
                }
                if (value instanceof Boolean || value instanceof Integer || value instanceof Long
                        || value instanceof String) {
                    accepted.put(name, value);
                } else {
                    unsupported = "returned a " + value.getClass().getName() + " for '" + name
                            + "', which is not a boolean, an integer or a string";
                    break;
                }
            }
            if (unsupported != null) {
                report(contributor, unsupported, null);
                continue;
            }
            settings.putAll(accepted);
        }
        return settings;
    }

    private void report(ModuleTenantSettings contributor, String problem, RuntimeException cause) {
        String message = "Tenant settings " + contributor.getClass().getName() + " of module '" + contributor.module()
                + "' " + problem + "; its settings are left out of /api/bpm/meta.";
        if (reported.add(contributor.getClass().getName() + " " + problem)) {
            LOG.error(message, cause);
        } else if (LOG.isDebugEnabled()) {
            LOG.debug(message, cause);
        }
    }

    @GetMapping("/me")
    ResponseEntity<Map<String, Object>> me(HttpServletRequest request) {
        EngineCaller caller = access.currentCaller();
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("id", caller.userId());
        if (caller.displayName() != null) {
            user.put("displayName", caller.displayName());
        }
        Map<String, Object> tenant = new LinkedHashMap<>();
        tenant.put("current", caller.tenant());
        tenant.put("allowed", caller.allowedTenants());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("schemaVersion", 1);
        body.put("user", user);
        body.put("tenant", tenant);
        body.put("permissions", permissions(caller));
        body.put("roles", roles(caller));
        body.put("groups", caller.groups());
        return cacheable(request, body, ME_CACHE_CONTROL, ME_VARY);
    }

    @ExceptionHandler(EngineAccessDeniedException.class)
    ResponseEntity<String> refused(EngineAccessDeniedException exception, HttpServletRequest request) {
        return EngineAccessProblems.response(exception, request);
    }

    /**
     * The caller's permissions in each engine's display order: exactly those for which {@link EngineAccess}
     * answers yes. Nothing outside the installed engines' vocabularies is ever listed.
     */
    private List<String> permissions(EngineCaller caller) {
        List<String> granted = new ArrayList<>();
        for (ModuleDescriptor module : modules) {
            for (String permission : module.permissions()) {
                if (access.hasPermission(caller, permission)) {
                    granted.add(permission);
                }
            }
        }
        return granted;
    }

    /**
     * The caller's host roles that some engine maps to permissions, sorted. Host roles unrelated to the
     * engines stay out of the response, so the host's role model does not reach the browser.
     */
    private List<String> roles(EngineCaller caller) {
        return caller.authorities().stream()
                .filter(authority -> modules.stream().anyMatch(module -> module.rolePermissions().containsKey(authority)))
                .sorted()
                .toList();
    }

    /**
     * Wraps a body with the caching headers. The weak ETag is a hash of the body's content, so it changes
     * exactly when the data changes; Spring MVC answers {@code 304} when {@code If-None-Match} matches.
     */
    private static ResponseEntity<Map<String, Object>> cacheable(
            HttpServletRequest request, Map<String, Object> body, String cacheControl, String[] vary) {
        ResponseEntity.BodyBuilder response = ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, cacheControl)
                .eTag("W/\"" + contentHash(body) + "\"")
                .header(CorrelationIds.HEADER, CorrelationIds.of(request));
        if (vary.length > 0) {
            response.varyBy(vary);
        }
        return response.body(body);
    }

    private static String contentHash(Map<String, Object> body) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(body.toString().getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(Arrays.copyOf(digest, 16));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by every Java platform", impossible);
        }
    }
}
