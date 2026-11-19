package io.github.dhluong90.bpm.host.spi;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * What a {@link TenantResolver} may read about the authenticated caller of one request: the principal and
 * what the host's security signed into it. JDK types only, so a resolver does not depend on the servlet or
 * Spring Security API. Request headers are deliberately not offered: the tenant never comes from what the
 * browser sends (PD-079).
 */
public interface CallerContext {

    /** The principal name, never blank. */
    String principalName();

    /**
     * The principal object as the host's security signed it in, for example the host's own user type after
     * a form or SAML sign-in, so a resolver can read the tenant from it or look the user up in the host's
     * directory.
     */
    Object principal();

    /** Every granted authority of the principal. */
    Set<String> authorities();

    /**
     * The attributes of the principal: the claims of a validated token, or the attributes of a session
     * principal from an OIDC or OAuth2 login. Empty when the principal has none.
     */
    Map<String, Object> attributes();

    /**
     * One string attribute.
     *
     * @param name the attribute or claim name
     * @return the value when it is a non-blank string, otherwise empty
     */
    default Optional<String> stringAttribute(String name) {
        return attributes().get(name) instanceof String value && !value.isBlank() ? Optional.of(value) : Optional.empty();
    }
}
