package com.horus.bpm.host.autoconfigure;

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
     * Tenant resolution. Ignored when the host declares its own {@code TenantResolver} bean, except
     * {@code claim}, which the engines always compare with the resolved tenant.
     *
     * @param resolver {@code security-context} (the tenant is a claim of the caller's token) or
     *     {@code header:<name>} (a trusted gateway sets the header after authentication)
     * @param claim the token claim that carries the tenant
     * @param trustedGateway required outside the {@code dev} profile before {@code header:<name>} starts:
     *     the operator confirms that a gateway authenticates every request and overwrites the header
     */
    public record Tenancy(
            @DefaultValue(SECURITY_CONTEXT) String resolver,
            @DefaultValue("tenant_id") String claim,
            @DefaultValue("false") boolean trustedGateway) {

        static final String SECURITY_CONTEXT = "security-context";
        static final String HEADER_PREFIX = "header:";
        private static final Pattern HEADER_NAME = Pattern.compile("^[A-Za-z0-9-]{1,64}$");

        /** Checks the resolver and claim names. */
        public Tenancy {
            if (!SECURITY_CONTEXT.equals(resolver)
                    && !(resolver.startsWith(HEADER_PREFIX)
                            && HEADER_NAME.matcher(resolver.substring(HEADER_PREFIX.length())).matches())) {
                throw new IllegalArgumentException("horus.bpm.tenancy.resolver must be 'security-context' or "
                        + "'header:<header name>', was '" + resolver + "'.");
            }
            if (claim.isBlank()) {
                throw new IllegalArgumentException("horus.bpm.tenancy.claim must not be blank.");
            }
        }

        /** The header that carries the tenant in gateway mode, or {@code null} in token mode. */
        @Nullable
        String tenantHeader() {
            return resolver.startsWith(HEADER_PREFIX) ? resolver.substring(HEADER_PREFIX.length()) : null;
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
