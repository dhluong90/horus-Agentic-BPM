package io.github.dhluong90.bpm.process.internal.exec;

import java.util.List;
import java.util.Optional;

/** Read view of the execution tree of the working copy. */
public interface TokenTree {

    /** @return every token in creation order */
    List<Token> all();

    /**
     * @param id token id
     * @return the token
     */
    Optional<Token> get(String id);

    /** @return the root scope token */
    Optional<Token> root();

    /**
     * @param scopeTokenId a scope token id
     * @return tokens directly under it
     */
    List<Token> children(String scopeTokenId);

    /**
     * @param scopeTokenId a scope token id
     * @return live tokens directly under it
     */
    List<Token> live(String scopeTokenId);
}
