package com.horus.bpm.host.autoconfigure;

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
 * @param security how engine endpoints answer unauthenticated callers
 */
@ConfigurationProperties("horus.bpm")
public record HostMetadataProperties(
        @Nullable String hostId, @DefaultValue Tenancy tenancy, @DefaultValue Security security) {

    static final int HOST_ID_MAX_LENGTH = 40;

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
     * Security of the engine endpoints.
     *
     * @param challenge the {@code WWW-Authenticate} value of a {@code 401} the endpoints raise themselves;
     *     for a caller without a tenant, a {@code Bearer} challenge gets {@code error="invalid_token"}
     */
    public record Security(@DefaultValue("Bearer") String challenge) {

        /** Checks the challenge. */
        public Security {
            if (challenge.isBlank() || challenge.chars().anyMatch(c -> c < 0x20)) {
                throw new IllegalArgumentException("horus.bpm.security.challenge must be a non-blank header value.");
            }
        }
    }
}
