package com.horus.bpm.host.spi;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The caller of one engine request as {@link EngineAccess} resolved it: who, in which tenant, and what the
 * host's security granted. Immutable and safe for concurrent use.
 *
 * <p>Built by {@link EngineAccess#currentCaller()} only after the tenant checks have passed, so the tenant
 * here is the one every engine endpoint and {@code GET /api/bpm/me} use for this request.
 *
 * @param userId the principal name, never blank; the identity for maker-checker and the audit trail
 * @param displayName the caller's display name from the principal's attributes, or {@code null} when it
 *     has none
 * @param tenant the tenant the request resolved to, 1 to 64 letters, digits, {@code .}, {@code _} or
 *     {@code -}
 * @param authorities every granted authority of the principal, as the host's security issued them
 * @param groups the principal's groups, if any; empty when none
 * @since 0.1.0
 */
public record EngineCaller(
        String userId, @Nullable String displayName, String tenant, Set<String> authorities, List<String> groups) {

    /**
     * Checks the identity and copies the collections.
     *
     * @throws IllegalArgumentException when {@code userId} or {@code tenant} is blank
     */
    public EngineCaller {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(tenant, "tenant");
        if (userId.isBlank()) {
            throw new IllegalArgumentException("userId must not be blank.");
        }
        if (tenant.isBlank()) {
            throw new IllegalArgumentException("tenant must not be blank.");
        }
        authorities = Set.copyOf(authorities);
        groups = List.copyOf(groups);
    }
}
