package io.github.dhluong90.bpm.process.internal.exec;

import java.util.Map;
import java.util.Optional;

/** Variables resolved through the scope chain of a token: scope-local first, then process-level. */
public interface VariableScopes {

    /**
     * @param name variable name
     * @param in the token whose scope chain is searched
     * @return the value
     */
    Optional<Object> get(String name, Token in);

    /**
     * Sets a process-level variable.
     *
     * @param name name
     * @param value value
     */
    void set(String name, Object value);

    /**
     * @param in the token whose scope chain is visible
     * @return every visible variable, inner scopes shadowing outer ones
     */
    Map<String, Object> visible(Token in);
}
