package com.horus.bpm.rules.autoconfigure;

import java.util.Map;
import java.util.function.Function;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.ClaimAccessor;
import org.springframework.security.oauth2.core.OAuth2AuthenticatedPrincipal;

/** Claims of an OAuth2 principal (a JWT, an OIDC user, an introspected token). Loaded only when OAuth2 is present. */
final class OAuth2PrincipalClaims implements Function<Authentication, Map<String, Object>> {

    static final String CLAIM_ACCESSOR = "org.springframework.security.oauth2.core.ClaimAccessor";

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
