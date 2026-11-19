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
 */
@ConfigurationProperties("horus.bpm")
public record HostMetadataProperties(@Nullable String hostId, @DefaultValue Tenancy tenancy) {

    static final int HOST_ID_MAX_LENGTH = 40;

    /** Checks the values at binding time, so a wrong value stops the start-up. */
    public HostMetadataProperties {
        if (hostId != null && (hostId.isBlank() || hostId.length() > HOST_ID_MAX_LENGTH)) {
            throw new IllegalArgumentException("horus.bpm.host-id must be 1 to " + HOST_ID_MAX_LENGTH
                    + " characters, was '" + hostId + "'.");
        }
    }

    /**
     * Tenant resolution.
     *
     * @param resolver {@code security-context} (the tenant is a claim of the caller's token) or
     *     {@code header:<name>} (a trusted gateway sets the header after authentication)
     * @param claim the token claim that carries the tenant in {@code security-context} mode
     */
    public record Tenancy(
            @DefaultValue(SECURITY_CONTEXT) String resolver,
            @DefaultValue("tenant_id") String claim) {

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
}
