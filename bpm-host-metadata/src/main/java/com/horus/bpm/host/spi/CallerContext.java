package com.horus.bpm.host.spi;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * What a {@link TenantResolver} may read about the authenticated caller of one request. JDK types only, so
 * a resolver does not depend on the servlet or Spring Security API; {@link #principal()} is the host's own
 * object.
 */
public interface CallerContext {

    /** The principal name, never blank. */
    String principalName();

    /**
     * The principal object as the host's security signed it in, for example the host's own user type
     * after a form or SAML sign-in. A host-session resolver reads the tenant from here: the tenant of a
     * session caller is part of the session, never a header the browser sets.
     */
    Object principal();

    /** Every granted authority of the principal. */
    Set<String> authorities();

    /** The claims of the caller's token, or an empty map when the principal has no token. */
    Map<String, Object> claims();

    /**
     * One request header.
     *
     * @param name the header name, case-insensitive
     * @return the first value, or empty when the request does not carry the header
     */
    Optional<String> header(String name);

    /**
     * One string claim.
     *
     * @param name the claim name
     * @return the value when the claim is a non-blank string, otherwise empty
     */
    default Optional<String> stringClaim(String name) {
        return claims().get(name) instanceof String value && !value.isBlank() ? Optional.of(value) : Optional.empty();
    }
}
