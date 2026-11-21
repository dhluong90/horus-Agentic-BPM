package io.github.dhluong90.bpm.host.autoconfigure;

import java.util.List;
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
 * @param security how engine endpoints answer unauthenticated callers, and which claims name the caller
 * @param limits the read limits {@code /api/bpm/meta} reports to clients
 */
@ConfigurationProperties("horus.bpm")
public record HostMetadataProperties(
        @Nullable String hostId, @DefaultValue Tenancy tenancy, @DefaultValue Security security,
        @DefaultValue Limits limits) {

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
     */
    public record Tenancy(@DefaultValue("tenant_id") String claim, @Nullable String devTenant) {

        /** Checks the claim name and the format of the development tenant. */
        public Tenancy {
            if (claim.isBlank()) {
                throw new IllegalArgumentException("horus.bpm.tenancy.claim must not be blank.");
            }
            if (devTenant != null && !DefaultEngineAccess.TENANT.matcher(devTenant).matches()) {
                throw new IllegalArgumentException("horus.bpm.tenancy.dev-tenant must be 1 to 64 letters, digits, "
                        + "'.', '_' or '-', starting with a letter or digit, was '" + devTenant + "'.");
            }
        }
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
     */
    public record Security(@DefaultValue("Bearer") String challenge, @DefaultValue("groups") String groupsClaim,
            @DefaultValue({"name", "preferred_username"}) List<String> displayNameClaims) {

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
    public record Limits(@DefaultValue("200") int maxPageSize) {

        /** Checks that the limit only lowers the engines' maximum. */
        public Limits {
            if (maxPageSize < 1 || maxPageSize > MAX_PAGE_SIZE) {
                throw new IllegalArgumentException("horus.bpm.limits.max-page-size must be 1 to " + MAX_PAGE_SIZE
                        + " (it may lower the engines' maximum, never raise it), was " + maxPageSize + ".");
            }
        }
    }
}
