package io.github.dhluong90.bpm.host.autoconfigure;

import io.github.dhluong90.bpm.host.spi.EngineAccess;
import io.github.dhluong90.bpm.host.spi.EngineAccessDeniedException;
import io.github.dhluong90.bpm.host.spi.EngineCaller;
import io.github.dhluong90.bpm.host.spi.ModuleDescriptor;
import io.github.dhluong90.bpm.host.spi.TenantModuleSettings;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * carry a weak {@code ETag} and answer {@code 304} to a matching {@code If-None-Match}. {@code /meta}, the
 * same for every caller of one tenant (engine settings may depend on the tenant, {@link TenantModuleSettings}), is
 * {@code private, max-age=60}; {@code /me}, which carries the
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
    static final String ME_CACHE_CONTROL = "private, no-cache";
    /** ADR-0015: the largest page any engine list endpoint returns. */
    static final int MAX_PAGE_SIZE = 200;

    private final List<ModuleDescriptor> modules;
    private final List<TenantModuleSettings> tenantSettings;
    private final HostMetadataProperties properties;
    private final EngineAccess access;
    /** {@code /me} depends on the caller's credentials and on the switch request header. */
    static final String[] ME_VARY = {HttpHeaders.AUTHORIZATION, HttpHeaders.COOKIE, DefaultEngineAccess.TENANT_SWITCH_HEADER};

    HostMetadataController(List<ModuleDescriptor> modules, HostMetadataProperties properties, EngineAccess access) {
        this(modules, List.of(), properties, access);
    }

    HostMetadataController(List<ModuleDescriptor> modules, List<TenantModuleSettings> tenantSettings,
            HostMetadataProperties properties, EngineAccess access) {
        this.modules = List.copyOf(modules);
        this.tenantSettings = List.copyOf(tenantSettings);
        this.properties = properties;
        this.access = access;
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
        body.put("tenancy", Map.of("switching", false));
        body.put("limits", Map.of("maxPageSize", MAX_PAGE_SIZE));
        return cacheable(request, body, META_CACHE_CONTROL, new String[0]);
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
        tenant.put("allowed", List.of(caller.tenant()));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("schemaVersion", 1);
        body.put("user", user);
        body.put("tenant", tenant);
        body.put("permissions", permissions(caller));
        body.put("roles", roles(caller));
        body.put("groups", caller.groups());
        return cacheable(request, body, ME_CACHE_CONTROL, ME_VARY);
    }

    /**
     * A module's host settings with the settings in force for the caller's tenant on top. A tenant value that is not
     * a boolean, an integer or a string breaks the published schema, so it fails the request rather than reaching a
     * client.
     */
    private Map<String, Object> settings(ModuleDescriptor module, String tenant) {
        Map<String, Object> settings = new LinkedHashMap<>(module.settings());
        for (TenantModuleSettings extra : tenantSettings) {
            if (!extra.module().equals(module.module())) {
                continue;
            }
            extra.settings(tenant).forEach((key, value) -> {
                if (!(value instanceof Boolean || value instanceof Integer || value instanceof Long
                        || value instanceof String)) {
                    throw new IllegalStateException("Tenant setting '" + key + "' of module " + module.module()
                            + " must be a boolean, an integer or a string.");
                }
                settings.put(key, value);
            });
        }
        return settings;
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
