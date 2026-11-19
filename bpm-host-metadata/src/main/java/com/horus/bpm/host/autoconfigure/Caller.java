package com.horus.bpm.host.autoconfigure;

import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The caller of one request as the host sees it.
 *
 * @param userId the principal name
 * @param displayName from the {@code name} or {@code preferred_username} claim, if any
 * @param tenant the tenant the request resolved to
 * @param authorities every granted authority of the principal
 * @param groups the {@code groups} claim, if any
 */
record Caller(String userId, @Nullable String displayName, String tenant, Set<String> authorities, List<String> groups) {

    Caller {
        authorities = Set.copyOf(authorities);
        groups = List.copyOf(groups);
    }
}
