package io.github.dhluong90.bpm.process.internal.exec;

import java.util.List;
import java.util.Optional;

/** Walks the execution tree upward: error and escalation propagation, variable resolution. */
public interface ScopeWalker {

    /**
     * @param token a token
     * @return its enclosing scope token
     */
    Optional<Token> enclosing(Token token);

    /**
     * @param token a token
     * @return enclosing scope tokens, innermost first, ending at the root
     */
    List<Token> ancestors(Token token);
}
