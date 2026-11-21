package io.github.dhluong90.bpm.host.spi;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * One engine's entry in the host contract: what {@code GET /api/bpm/meta} says about the engine and
 * which permissions {@code GET /api/bpm/me} may list for it.
 *
 * <p>Each engine starter registers one bean of this type. The host metadata module collects them; a host
 * never implements it. The canonical constructor checks every value against the published schema
 * {@code bpm-meta.v1.schema.json} and the permission names of ADR-0010, so a wrong entry stops the host
 * at start-up instead of reaching a client. Immutable and safe for concurrent use.
 *
 * <p>Engine starters build it with {@link #builder(String, String, String)}, so optional fields added in a later
 * release leave their code unchanged; the canonical constructor stays for existing callers.
 *
 * <p><b>Closed set.</b> The modules are exactly {@code rules} and {@code processes}, and every permission matches
 * {@code (rules|processes)(:[a-z]+){1,2}}. Both are closed on purpose: they are the {@code module} enum of the
 * published schema {@code bpm-meta.v1.schema.json} and the permission names of ADR-0010. A third engine therefore
 * needs a release of this module and a new schema version; widening a v1 enum ships behind a feature flag, so a
 * client on the old schema never meets a value it cannot read.
 *
 * <pre>{@code
 * ModuleDescriptor.builder("rules", "0.2.0", "v1")
 *         .permissions(List.of("rules:read", "rules:publish:approve"))
 *         .rolePermissions(Map.of("ROLE_PRICING_APPROVER", List.of("rules:read", "rules:publish:approve")))
 *         .build();
 * }</pre>
 *
 * @param module the REST path segment and permission prefix: {@code rules} or {@code processes}
 * @param engineVersion the engine release, semantic version with an optional pre-release suffix
 * @param apiVersion the REST API major version, for example {@code v1}
 * @param features feature flags the engine has turned on in this host, each starting with
 *     {@code <module>.}; empty when none
 * @param settings host settings clients must respect; values are booleans, integers or strings; empty
 *     when none
 * @param permissions every permission the engine checks, in display order, each {@code <module>:<action>}
 *     or {@code <module>:<resource>:<action>}
 * @param rolePermissions host authority (for example {@code ROLE_PRICING_APPROVER}) to the permissions
 *     it grants; every granted permission must be one of {@code permissions}; empty when the host
 *     issues permission strings directly
 */
public record ModuleDescriptor(
        String module,
        String engineVersion,
        String apiVersion,
        List<String> features,
        Map<String, Object> settings,
        List<String> permissions,
        Map<String, List<String>> rolePermissions) {

    private static final Set<String> MODULES = Set.of("rules", "processes");
    private static final Pattern ENGINE_VERSION = Pattern.compile("^[0-9]+\\.[0-9]+\\.[0-9]+(-[0-9A-Za-z.-]+)?$");
    private static final Pattern API_VERSION = Pattern.compile("^v[0-9]+$");
    private static final Pattern FEATURE = Pattern.compile("^[a-z]+(\\.[a-z0-9-]+)+$");
    private static final Pattern PERMISSION = Pattern.compile("^(rules|processes)(:[a-z]+){1,2}$");

    /** Validates every field and copies the collections. */
    public ModuleDescriptor {
        Objects.requireNonNull(module, "module");
        if (!MODULES.contains(module)) {
            throw new IllegalArgumentException("Unknown engine module '" + module + "': expected rules or processes.");
        }
        requireMatch(ENGINE_VERSION, engineVersion, "engineVersion");
        requireMatch(API_VERSION, apiVersion, "apiVersion");
        features = uniqueList(features, "features");
        for (String feature : features) {
            requireMatch(FEATURE, feature, "feature");
            if (!feature.startsWith(module + ".")) {
                throw new IllegalArgumentException("Feature '" + feature + "' must start with '" + module + ".'.");
            }
        }
        settings = copySettings(settings);
        permissions = uniqueList(permissions, "permissions");
        for (String permission : permissions) {
            requireMatch(PERMISSION, permission, "permission");
            if (!permission.startsWith(module + ":")) {
                throw new IllegalArgumentException("Permission '" + permission + "' must start with '" + module + ":'.");
            }
        }
        rolePermissions = copyRolePermissions(rolePermissions, permissions, module);
    }

    /**
     * Starts a descriptor with its required fields; features, settings, permissions and role mappings are empty
     * until set.
     *
     * @param module {@code rules} or {@code processes}
     * @param engineVersion the engine release, for example {@code 0.2.0}
     * @param apiVersion the REST API major version, for example {@code v1}
     * @return a new builder; not safe for concurrent use
     */
    public static Builder builder(String module, String engineVersion, String apiVersion) {
        return new Builder(module, engineVersion, apiVersion);
    }

    /** Collects the fields of a {@link ModuleDescriptor}; {@link #build()} validates them as the constructor does. */
    public static final class Builder {

        private final String module;
        private final String engineVersion;
        private final String apiVersion;
        private List<String> features = List.of();
        private Map<String, Object> settings = Map.of();
        private List<String> permissions = List.of();
        private Map<String, List<String>> rolePermissions = Map.of();

        private Builder(String module, String engineVersion, String apiVersion) {
            this.module = module;
            this.engineVersion = engineVersion;
            this.apiVersion = apiVersion;
        }

        /**
         * @param features feature flags, each starting with {@code <module>.}
         * @return this builder
         */
        public Builder features(List<String> features) {
            this.features = Objects.requireNonNull(features, "features");
            return this;
        }

        /**
         * @param settings settings clients must respect; booleans, integers or strings
         * @return this builder
         */
        public Builder settings(Map<String, Object> settings) {
            this.settings = Objects.requireNonNull(settings, "settings");
            return this;
        }

        /**
         * @param permissions every permission the engine checks, in display order
         * @return this builder
         */
        public Builder permissions(List<String> permissions) {
            this.permissions = Objects.requireNonNull(permissions, "permissions");
            return this;
        }

        /**
         * @param rolePermissions host authority to the permissions it grants
         * @return this builder
         */
        public Builder rolePermissions(Map<String, List<String>> rolePermissions) {
            this.rolePermissions = Objects.requireNonNull(rolePermissions, "rolePermissions");
            return this;
        }

        /**
         * @return the validated descriptor
         * @throws IllegalArgumentException when a value breaks the published schema or the permission names
         */
        public ModuleDescriptor build() {
            return new ModuleDescriptor(module, engineVersion, apiVersion, features, settings, permissions,
                    rolePermissions);
        }
    }

    private static void requireMatch(Pattern pattern, String value, String field) {
        Objects.requireNonNull(value, field);
        if (!pattern.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid " + field + " '" + value + "': must match " + pattern.pattern());
        }
    }

    private static List<String> uniqueList(List<String> values, String field) {
        Objects.requireNonNull(values, field);
        List<String> copy = List.copyOf(values);
        if (new LinkedHashSet<>(copy).size() != copy.size()) {
            throw new IllegalArgumentException("Duplicate entry in " + field + ": " + copy);
        }
        return copy;
    }

    private static Map<String, Object> copySettings(Map<String, Object> settings) {
        Objects.requireNonNull(settings, "settings");
        Map<String, Object> copy = new LinkedHashMap<>();
        settings.forEach((key, value) -> {
            if (!(value instanceof Boolean || value instanceof Integer || value instanceof Long || value instanceof String)) {
                throw new IllegalArgumentException("Setting '" + key + "' must be a boolean, an integer or a string.");
            }
            copy.put(key, value);
        });
        return Collections.unmodifiableMap(copy);
    }

    private static Map<String, List<String>> copyRolePermissions(
            Map<String, List<String>> rolePermissions, List<String> permissions, String module) {
        Objects.requireNonNull(rolePermissions, "rolePermissions");
        Map<String, List<String>> copy = new LinkedHashMap<>();
        rolePermissions.forEach((authority, granted) -> {
            if (authority == null || authority.isBlank()) {
                throw new IllegalArgumentException("A role mapping of module " + module + " has a blank authority.");
            }
            List<String> grantedCopy = List.copyOf(Objects.requireNonNull(granted, authority));
            for (String permission : grantedCopy) {
                if (!permissions.contains(permission)) {
                    throw new IllegalArgumentException("Role mapping " + authority + " grants unknown permission '"
                            + permission + "'. Known " + module + " permissions: " + permissions);
                }
            }
            copy.put(authority, grantedCopy);
        });
        return Collections.unmodifiableMap(copy);
    }
}
