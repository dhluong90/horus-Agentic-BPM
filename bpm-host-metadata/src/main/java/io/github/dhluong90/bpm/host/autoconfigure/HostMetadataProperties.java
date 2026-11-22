package io.github.dhluong90.bpm.host.autoconfigure;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Host-wide settings under {@code horus.bpm}, shared by every engine in the host. Engine-specific
 * settings stay under {@code horus.bpm.rules} and {@code horus.bpm.processes}.
 *
 * @param hostId the id clients show for this host, at most 40 characters; omitted from
 *     {@code /api/bpm/meta} when unset
 * @param tenancy where the tenant of a request comes from
 * @param security how engine endpoints answer unauthenticated callers, which claims name the caller, and which
 *     token audiences the host accepts
 * @param limits the read limits {@code /api/bpm/meta} reports to clients
 * @param rest cross-origin access for a standalone cockpit on another origin (ADR-0014 decision 4)
 * @param displayNames readable, localised names of roles, groups and users (#442), read by the default
 *     {@code DisplayNames}
 */
@ConfigurationProperties("horus.bpm")
public record HostMetadataProperties(
        @Nullable String hostId, @DefaultValue Tenancy tenancy, @DefaultValue Security security,
        @DefaultValue Limits limits, @DefaultValue Rest rest, @DefaultValue DisplayNamesSettings displayNames) {

    static final int HOST_ID_MAX_LENGTH = 40;
    /** ADR-0015: the largest page any engine list returns; a host may lower it, never raise it. */
    static final int MAX_PAGE_SIZE = 200;
    /** A claim name: printable, no spaces, as an identity provider writes it. */
    private static final Pattern CLAIM = Pattern.compile("^[!-~]{1,128}$");

    /** Checks the values at binding time, so a wrong value stops the start-up. */
    public HostMetadataProperties {
        if (hostId != null && (hostId.isBlank() || hostId.length() > HOST_ID_MAX_LENGTH)) {
            throw new IllegalArgumentException("horus.bpm.host-id must be 1 to " + HOST_ID_MAX_LENGTH
                    + " characters, was '" + hostId + "'.");
        }
    }

    /**
     * Tenant resolution (PD-079, ADR-0014 decision 6).
     *
     * @param claim the attribute of the authenticated principal, a token claim or a session principal
     *     attribute, that the default resolver reads and that the engines compare with the tenant any
     *     resolver returns
     * @param devTenant a fixed tenant for local work without an identity provider, used for principals
     *     that carry no tenant; accepted on the {@code dev} profile only
     * @param switching whether a caller may select another tenant with {@code X-Tenant-Id}; off by default
     * @param allowedTenantsClaim the claim or principal attribute listing the tenants the caller may select, read
     *     only while switching is on; the header alone never grants a tenant
     */
    public record Tenancy(@DefaultValue("tenant_id") String claim, @Nullable String devTenant,
            @DefaultValue Switching switching, @DefaultValue("allowed_tenants") String allowedTenantsClaim) {

        /** Checks the claim names and the format of the development tenant. */
        public Tenancy {
            if (claim.isBlank()) {
                throw new IllegalArgumentException("horus.bpm.tenancy.claim must not be blank.");
            }
            Security.requireClaim("horus.bpm.tenancy.allowed-tenants-claim", allowedTenantsClaim);
            if (allowedTenantsClaim.equals(claim)) {
                throw new IllegalArgumentException("horus.bpm.tenancy.allowed-tenants-claim must differ from "
                        + "horus.bpm.tenancy.claim ('" + claim + "').");
            }
            if (devTenant != null && !DefaultEngineAccess.TENANT.matcher(devTenant).matches()) {
                throw new IllegalArgumentException("horus.bpm.tenancy.dev-tenant must be 1 to 64 letters, digits, "
                        + "'.', '_' or '-', starting with a letter or digit, was '" + devTenant + "'.");
            }
        }
    }

    /**
     * Tenant switching (ADR-0014 decision 5, PD-037).
     *
     * @param enabled {@code true} to accept {@code X-Tenant-Id} for a tenant in the caller's allowed-tenants claim;
     *     off by default, when a header that differs from the caller's own tenant is {@code 403 TENANT_NOT_ALLOWED}
     */
    public record Switching(@DefaultValue("false") boolean enabled) {
    }

    /**
     * Security of the engine endpoints and the claims that describe the caller.
     *
     * @param challenge the {@code WWW-Authenticate} value of every {@code 401}, from the engine endpoints and from
     *     the host's chain through the registered {@code BpmProblemAuthenticationEntryPoint}; for a caller without a
     *     tenant, a {@code Bearer} challenge gets {@code error="invalid_token"}
     * @param groupsClaim the claim or principal attribute holding the caller's groups, listed by
     *     {@code /api/bpm/me}; for example {@code groups} on Keycloak and Entra ID, or
     *     {@code http://schemas.xmlsoap.org/claims/Group} on ADFS
     * @param displayNameClaims the claims tried in order for the caller's display name; the first non-blank one
     *     wins, for example {@code name} then {@code upn} on Entra ID
     * @param audiences the audiences this host accepts in a bearer token's {@code aud} claim, for example
     *     {@code loans-bpm}; a token for none of them is {@code 401}. Empty by default: Spring Boot's own
     *     {@code spring.security.oauth2.resourceserver.jwt.audiences} may be used instead, and one of the two is
     *     required whenever a CORS origin is set outside the {@code dev} profile (TR-SEC-12)
     */
    public record Security(@DefaultValue("Bearer") String challenge, @DefaultValue("groups") String groupsClaim,
            @DefaultValue({"name", "preferred_username"}) List<String> displayNameClaims,
            @DefaultValue List<String> audiences) {

        /** Checks the challenge and the claim names, so a wrong value stops the start-up naming the property. */
        public Security {
            if (challenge.isBlank() || challenge.chars().anyMatch(c -> c < 0x20)) {
                throw new IllegalArgumentException("horus.bpm.security.challenge must be a non-blank header value.");
            }
            requireClaim("horus.bpm.security.groups-claim", groupsClaim);
            if (displayNameClaims == null || displayNameClaims.isEmpty()) {
                throw new IllegalArgumentException("horus.bpm.security.display-name-claims must name at least one claim.");
            }
            for (String claim : displayNameClaims) {
                requireClaim("horus.bpm.security.display-name-claims", claim);
            }
            displayNameClaims = List.copyOf(displayNameClaims);
            audiences = audiences == null ? List.of() : List.copyOf(audiences);
            for (String audience : audiences) {
                if (audience == null || audience.isBlank() || audience.chars().anyMatch(Character::isWhitespace)) {
                    throw new IllegalArgumentException(
                            "horus.bpm.security.audiences must hold non-blank values without spaces, was '" + audience + "'.");
                }
            }
        }

        private static void requireClaim(String property, @Nullable String claim) {
            if (claim == null || !CLAIM.matcher(claim).matches()) {
                throw new IllegalArgumentException(property + " must be a claim name of 1 to 128 printable characters "
                        + "without spaces, was '" + claim + "'.");
            }
        }
    }

    /**
     * Read limits reported by {@code /api/bpm/meta}, so clients never ask for more than the host serves.
     *
     * @param maxPageSize the largest page clients may request, 1 to {@value HostMetadataProperties#MAX_PAGE_SIZE}; a
     *     host that lowers the engines' {@code query.max-page-size} lowers this too
     */
    /**
     * The names the default {@code DisplayNames} serves (#442): per identifier, a name per language, for example
     * {@code roles.CARD_OFFICER.vi: Cán bộ thẻ}. An identifier with a dot is written in brackets, as
     * {@code roles[ops.lead].en}. A host with its own {@code DisplayNames} bean ignores these.
     *
     * @param defaultLanguage the language used when the caller's has no name, two or three lower-case letters;
     *     default {@code en}
     * @param roles role identifier to language to name
     * @param groups group identifier to language to name
     * @param users user identifier to language to name, for a small host without a directory
     */
    public record DisplayNamesSettings(@DefaultValue("en") String defaultLanguage,
            @DefaultValue Map<String, Map<String, String>> roles,
            @DefaultValue Map<String, Map<String, String>> groups,
            @DefaultValue Map<String, Map<String, String>> users) {

        /** Checks the language. */
        public DisplayNamesSettings {
            if (defaultLanguage == null || !defaultLanguage.matches("^[a-z]{2,3}$")) {
                throw new IllegalArgumentException("horus.bpm.display-names.default-language must be two or three "
                        + "lower-case letters, was '" + defaultLanguage + "'.");
            }
            roles = roles == null ? Map.of() : Map.copyOf(roles);
            groups = groups == null ? Map.of() : Map.copyOf(groups);
            users = users == null ? Map.of() : Map.copyOf(users);
        }
    }

    public record Limits(@DefaultValue("200") int maxPageSize) {

        /** Checks that the limit only lowers the engines' maximum. */
        public Limits {
            if (maxPageSize < 1 || maxPageSize > MAX_PAGE_SIZE) {
                throw new IllegalArgumentException("horus.bpm.limits.max-page-size must be 1 to " + MAX_PAGE_SIZE
                        + " (it may lower the engines' maximum, never raise it), was " + maxPageSize + ".");
            }
        }
    }

    /**
     * Cross-origin access for a standalone cockpit. Off by default: with no origin listed, no CORS header is ever
     * sent and a browser refuses every cross-origin call.
     *
     * @param cors the allowed origins and what they may send
     */
    public record Rest(@DefaultValue Cors cors) {

        /**
         * The origins allowed to call the engine endpoints from a browser. Only the exact origins listed are
         * allowed; there is no wildcard and no credentials mode, because a standalone cockpit sends a bearer token,
         * never cookies (TR-SEC-09).
         *
         * @param allowedOrigins exact origins, scheme, host and optional port, for example
         *     {@code https://cockpit.bank.example}; {@code http} only for {@code localhost}, {@code 127.0.0.1} or
         *     {@code [::1]}; never {@code *}, a pattern, a path or a trailing slash
         * @param allowedHeaders request headers a cross-origin call may send
         * @param allowedMethods methods a cross-origin call may use
         * @param exposedHeaders response headers the cockpit's script may read
         * @param maxAge how long a browser may cache a preflight answer
         */
        public record Cors(@DefaultValue List<String> allowedOrigins,
                @DefaultValue({"Authorization", "Accept", "Content-Type", "X-Requested-With", "X-Tenant-Id",
                    "X-Correlation-Id", "If-None-Match", "If-Match", "Idempotency-Key"}) List<String> allowedHeaders,
                @DefaultValue({"GET", "POST", "PUT", "PATCH", "DELETE"}) List<String> allowedMethods,
                @DefaultValue({"ETag", "Location", "Retry-After", "X-Correlation-Id"}) List<String> exposedHeaders,
                @DefaultValue("PT10M") Duration maxAge) {

            private static final Pattern ORIGIN = Pattern.compile(
                    "^(https?)://((?:[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?)(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?)*"
                            + "|\\[[0-9A-Fa-f:]+\\])(?::([0-9]{1,5}))?$");

            /** Checks every origin and copies the lists, so a wrong value stops the start-up naming the property. */
            public Cors {
                allowedOrigins = copy(allowedOrigins);
                allowedHeaders = copy(allowedHeaders);
                allowedMethods = copy(allowedMethods);
                exposedHeaders = copy(exposedHeaders);
                for (String origin : allowedOrigins) {
                    requireOrigin(origin);
                }
                if (!allowedOrigins.isEmpty() && (allowedHeaders.isEmpty() || allowedMethods.isEmpty())) {
                    throw new IllegalArgumentException("horus.bpm.rest.cors.allowed-headers and allowed-methods "
                            + "must not be empty when an origin is allowed.");
                }
                if (allowedHeaders.contains("*")) {
                    throw new IllegalArgumentException(
                            "horus.bpm.rest.cors.allowed-headers must name the headers, not '*'.");
                }
                if (maxAge == null || maxAge.isNegative() || maxAge.compareTo(Duration.ofHours(24)) > 0) {
                    throw new IllegalArgumentException("horus.bpm.rest.cors.max-age must be PT0S to PT24H, was " + maxAge + ".");
                }
            }

            private static List<String> copy(@Nullable List<String> values) {
                return values == null ? List.of() : values.stream().map(String::trim).filter(v -> !v.isEmpty()).toList();
            }

            private static void requireOrigin(String origin) {
                java.util.regex.Matcher matcher = ORIGIN.matcher(origin);
                if (!matcher.matches()) {
                    throw new IllegalArgumentException("horus.bpm.rest.cors.allowed-origins must list exact origins "
                            + "such as https://cockpit.bank.example (scheme, host, optional port; no '*', path or "
                            + "trailing slash), was '" + origin + "'.");
                }
                String host = matcher.group(2);
                boolean loopback = host.equals("localhost") || host.equals("127.0.0.1") || host.equals("[::1]");
                if (matcher.group(1).equals("http") && !loopback) {
                    throw new IllegalArgumentException("horus.bpm.rest.cors.allowed-origins allows http only for "
                            + "localhost, 127.0.0.1 or [::1]; use https for '" + origin + "'.");
                }
                if (matcher.group(3) != null && Integer.parseInt(matcher.group(3)) > 65535) {
                    throw new IllegalArgumentException("horus.bpm.rest.cors.allowed-origins has an invalid port in '"
                            + origin + "'.");
                }
            }
        }
    }
}
