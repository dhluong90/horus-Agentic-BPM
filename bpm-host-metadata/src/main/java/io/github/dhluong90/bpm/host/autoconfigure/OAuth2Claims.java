package io.github.dhluong90.bpm.host.autoconfigure;

import java.util.Map;
import java.util.function.Function;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.ClaimAccessor;
import org.springframework.security.oauth2.core.OAuth2AuthenticatedPrincipal;

/**
 * Claims of an OAuth2 principal: a JWT ({@code Jwt}), an OIDC user, or an introspected opaque token.
 * Loaded only when Spring Security OAuth2 is on the host's classpath.
 */
final class OAuth2Claims implements Function<Authentication, Map<String, Object>> {

    static final String CLAIM_ACCESSOR = "org.springframework.security.oauth2.core.ClaimAccessor";

    /** True for a principal that carries token claims: a JWT, an OIDC user or an introspected token. */
    static boolean isToken(Object principal) {
        return principal instanceof ClaimAccessor || principal instanceof OAuth2AuthenticatedPrincipal;
    }

    @Override
    public Map<String, Object> apply(Authentication authentication) {
        Object principal = authentication.getPrincipal();
        if (principal instanceof ClaimAccessor accessor) {
            return accessor.getClaims();
        }
        if (principal instanceof OAuth2AuthenticatedPrincipal oauth2) {
            return oauth2.getAttributes();
        }
        return Map.of();
    }
}
